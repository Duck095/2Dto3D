import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useThree } from '@react-three/fiber';
import { OrbitControls } from '@react-three/drei';
import { Box3, Box3Helper, MathUtils, Vector3 } from 'three';
import { useAuth } from '../../context/AuthContext';
import { getAdminProductMeshConfiguration, getAdminProductMeshStructure, listAdminMaterials, putAdminProductMeshConfiguration } from '../../features/admin/products/adminProductApi';
import { buildMeshConfigurationPayload, classifyAdminApiError, isLocalPreviewProvenanceCurrent, mapBackendOccurrencesToLocalMeshes, meshConfigurationColorsExist } from '../../features/admin/products/adminProductContract';

const identityKey = item => `${item.nodeIndex}:${item.meshIndex}:${item.primitiveIndex}`;
const errorMessage = error => error?.response || error?.code
  ? classifyAdminApiError(error).message
  : error?.message || classifyAdminApiError(error).message;

function localOccurrenceCandidates(gltf) {
  if (!gltf?.scene || !gltf?.parser?.associations) return [];
  const associations = gltf.parser.associations;
  const result = [];
  gltf.scene.traverse(object => {
    if (!object.isMesh || !object.geometry) return;
    const primitiveAssociation = associations.get(object);
    if (!Number.isInteger(primitiveAssociation?.primitives)) return;
    let nodeIndex;
    let meshIndex = primitiveAssociation.meshes;
    for (let parent = object.parent; parent; parent = parent.parent) {
      const parentAssociation = associations.get(parent);
      if (!Number.isInteger(meshIndex) && Number.isInteger(parentAssociation?.meshes)) meshIndex = parentAssociation.meshes;
      if (Number.isInteger(parentAssociation?.nodes)) { nodeIndex = parentAssociation.nodes; break; }
    }
    if (Number.isInteger(nodeIndex) && Number.isInteger(meshIndex)) result.push({
      object, nodeIndex, meshIndex, primitiveIndex: primitiveAssociation.primitives,
    });
  });
  return result;
}

function SelectionBounds({ object }) {
  const helper = useMemo(() => new Box3Helper(new Box3(), '#7556d8'), []);
  useEffect(() => {
    if (!object) return;
    object.updateWorldMatrix(true, false);
    helper.box.makeEmpty().expandByObject(object);
    helper.updateMatrixWorld(true);
  }, [helper, object]);
  return <primitive object={helper} />;
}

function MeshPreviewScene({ gltf, mappedObjects, selectedIdentity, onSelect }) {
  const selected = selectedIdentity ? mappedObjects.get(selectedIdentity) : null;
  const camera = useThree(state => state.camera);
  const controls = useRef(null);
  useEffect(() => {
    gltf.scene.updateMatrixWorld(true);
    const bounds = new Box3().setFromObject(gltf.scene);
    if (bounds.isEmpty()) return;
    const center = bounds.getCenter(new Vector3());
    const size = bounds.getSize(new Vector3());
    const maxDimension = Math.max(size.x, size.y, size.z) || 1;
    const distance = (maxDimension / (2 * Math.tan(MathUtils.degToRad(camera.fov || 45) / 2))) * 1.65;
    camera.position.set(center.x + distance * 0.68, center.y + distance * 0.48, center.z + distance);
    camera.near = Math.max(distance / 1000, 0.001);
    camera.far = Math.max(distance * 100, 1000);
    camera.lookAt(center);
    camera.updateProjectionMatrix();
    if (controls.current) { controls.current.target.copy(center); controls.current.update(); }
  }, [camera, gltf]);
  return <>
    <color attach="background" args={['#f4f3f8']} />
    <ambientLight intensity={1.25} /><directionalLight position={[4, 6, 5]} intensity={1.1} />
    <primitive object={gltf.scene} onClick={event => {
      event.stopPropagation();
      for (const [identity, object] of mappedObjects) if (object === event.object) { onSelect(identity); break; }
    }} />
    {selected && <SelectionBounds object={selected} />}
    <OrbitControls ref={controls} makeDefault />
  </>;
}

function MeshPreview({ gltf, mappedObjects, selectedIdentity, onSelect }) {
  return <Canvas camera={{ position: [3, 2.5, 4], fov: 45 }} dpr={[1, 1.5]} aria-label="Xem trước mô hình GLB">
    <MeshPreviewScene gltf={gltf} mappedObjects={mappedObjects} selectedIdentity={selectedIdentity} onSelect={onSelect} />
  </Canvas>;
}

function configurationToDraft(configuration, occurrences, materialId) {
  if (configuration?.configured) return {
    materialId: configuration.materialId,
    meshes: configuration.meshes.map(mesh => ({ nodeIndex: mesh.nodeIndex, meshIndex: mesh.meshIndex,
      primitiveIndex: mesh.primitiveIndex, customizable: mesh.customizable,
      defaultColorId: mesh.defaultSelection.colorId,
      allowedColorIds: mesh.allowedSelections.map(selection => selection.colorId) })),
  };
  return { materialId: materialId || null, meshes: occurrences.map(occurrence => ({
    nodeIndex: occurrence.nodeIndex, meshIndex: occurrence.meshIndex, primitiveIndex: occurrence.primitiveIndex,
    customizable: false, defaultColorId: null, allowedColorIds: [],
  })) };
}

function configurationMatchesDraft(configuration, draft) {
  if (!configuration?.configured || configuration.materialId !== draft.materialId || configuration.meshes.length !== draft.meshes.length) return false;
  const saved = new Map(configuration.meshes.map(item => [identityKey(item), item]));
  return draft.meshes.every(item => {
    const row = saved.get(identityKey(item));
    return row && row.customizable === item.customizable && row.defaultSelection.colorId === item.defaultColorId
      && JSON.stringify(row.allowedSelections.map(value => value.colorId).sort((a, b) => a - b))
        === JSON.stringify([...item.allowedColorIds].sort((a, b) => a - b));
  });
}

export default function AdminProductMeshConfiguration({ productId, backendProduct, canPreviewGltf, publishedPreviewGltf, onPreviewProvenanceObserved, onUnsavedChange }) {
  const { user, currentUser, sessionVersion } = useAuth();
  const sessionIdentity = `${currentUser?.id ?? user?.id ?? user?.email ?? 'unknown-session'}:${sessionVersion}`;
  const [state, setState] = useState({ key: '', status: 'loading', error: '', structure: null, configuration: null, materials: [], draft: null, savedNotice: '' });
  const [saving, setSaving] = useState(false);
  const [stale, setStale] = useState(false);
  const [reconciliationRequired, setReconciliationRequired] = useState(false);
  const [unknownOutcome, setUnknownOutcome] = useState(false);
  const [selectedIdentity, setSelectedIdentity] = useState('');
  const requestVersion = useRef(0);
  const previewRef = useRef(canPreviewGltf);
  const productRef = useRef(backendProduct);
  const provenanceCallbackRef = useRef(onPreviewProvenanceObserved);
  previewRef.current = canPreviewGltf;
  productRef.current = backendProduct;
  provenanceCallbackRef.current = onPreviewProvenanceObserved;
  const key = `${sessionIdentity}:${productId}`;
  const currentState = state.key === key ? state : { key, status: 'loading', error: '', structure: null, configuration: null, materials: [], draft: null, savedNotice: '' };
  const selectedMaterial = currentState.materials.find(item => item.id === currentState.draft?.materialId);
  const materialAvailable = selectedMaterial?.isAvailable === 'AVAILABLE';
  const colors = selectedMaterial?.colors || [];
  const colorsById = useMemo(() => new Map(colors.map(color => [color.colorId, color])), [colors]);
  const isDirty = useMemo(() => Boolean(currentState.draft && currentState.configuration
    && JSON.stringify(currentState.draft) !== JSON.stringify(configurationToDraft(currentState.configuration, currentState.structure?.primitiveOccurrences || [], currentState.draft.materialId))), [currentState]);
  const publishedPreviewSafe = publishedPreviewGltf?.productId === Number(productId)
    && backendProduct?.status === 'ACTIVE' && Boolean(publishedPreviewGltf.gltf);
  const activePreviewGltf = publishedPreviewSafe ? publishedPreviewGltf.gltf : canPreviewGltf?.gltf;
  const localCandidates = useMemo(() => localOccurrenceCandidates(activePreviewGltf), [activePreviewGltf]);
  const provenanceSafe = useMemo(() => isLocalPreviewProvenanceCurrent(canPreviewGltf, backendProduct, currentState.structure), [canPreviewGltf, backendProduct, currentState.structure]);
  const mapping = useMemo(() => (publishedPreviewSafe || provenanceSafe)
    ? mapBackendOccurrencesToLocalMeshes(currentState.structure?.primitiveOccurrences || [], localCandidates)
    : { mapped: false, byIdentity: new Map() }, [currentState.structure, localCandidates, provenanceSafe, publishedPreviewSafe]);
  const mappedObjects = mapping.mapped ? new Map([...mapping.byIdentity].map(([identity, object]) => [identity, object])) : new Map();
  const isConfigurationValid = useMemo(() => {
    if (!currentState.structure || !currentState.draft || !selectedMaterial || selectedMaterial.isAvailable !== 'AVAILABLE') return false;
    try { buildMeshConfigurationPayload(currentState.structure, currentState.configuration?.revision ?? 0, currentState.draft); return true; }
    catch { return false; }
  }, [currentState, selectedMaterial]);

  useEffect(() => { onUnsavedChange?.(isDirty); }, [isDirty, onUnsavedChange]);

  useEffect(() => {
    if (!isDirty) return undefined;
    const warnBeforeUnload = event => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warnBeforeUnload);
    return () => window.removeEventListener('beforeunload', warnBeforeUnload);
  }, [isDirty]);

  const reloadFromBackend = useCallback(async (signal, preserveDraft = false) => {
    const version = requestVersion.current;
    setState(value => ({ ...value, key, status: 'loading', error: '' }));
    const structure = await getAdminProductMeshStructure(productId, { signal });
    const [configuration, materials] = await Promise.all([
      getAdminProductMeshConfiguration(productId, structure, { signal }), listAdminMaterials({ signal }),
    ]);
    if (signal.aborted || requestVersion.current !== version) return;
    if (!meshConfigurationColorsExist(configuration, materials)) throw new Error('Cấu hình Backend tham chiếu màu không có trong danh sách màu của material. Dữ liệu bị giữ ở chế độ chỉ đọc cho đến khi được sửa.');
    const localPreview = previewRef.current;
    const loadedProduct = productRef.current;
    if (localPreview?.uploadConfirmed === true && localPreview.sourceFile
      && localPreview.productId === structure.productId && loadedProduct?.id === structure.productId
      && typeof localPreview.filePath3d === 'string' && localPreview.filePath3d
      && loadedProduct.filePath3d === localPreview.filePath3d
      && (!localPreview.mediaAssetId || !localPreview.structureSnapshotId)) {
      provenanceCallbackRef.current?.({ productId: structure.productId, mediaAssetId: structure.mediaAssetId,
        structureSnapshotId: structure.structureSnapshotId, filePath3d: localPreview.filePath3d });
    }
    setSelectedIdentity(structure.primitiveOccurrences[0] ? identityKey(structure.primitiveOccurrences[0]) : '');
    const available = materials.filter(material => material.isAvailable === 'AVAILABLE');
    const initialMaterial = configuration.configured ? configuration.materialId : available[0]?.id || null;
    setState(value => {
      const draft = preserveDraft && value.key === key ? value.draft
        : configurationToDraft(configuration, structure.primitiveOccurrences, initialMaterial);
      return { key, status: 'ready', error: '', structure, configuration, materials, draft, savedNotice: value.key === key ? value.savedNotice : '' };
    });
    setStale(false);
    setUnknownOutcome(false);
    setReconciliationRequired(false);
  }, [key, productId]);

  useEffect(() => {
    const controller = new AbortController();
    const version = ++requestVersion.current;
    setState({ key, status: 'loading', error: '', structure: null, configuration: null, materials: [], draft: null, savedNotice: '' });
    setStale(false); setUnknownOutcome(false); setReconciliationRequired(false); setSaving(false); setSelectedIdentity('');
    reloadFromBackend(controller.signal).catch(error => {
      if (!controller.signal.aborted && requestVersion.current === version) setState({ key, status: 'error', error: errorMessage(error), structure: null, configuration: null, materials: [], draft: null, savedNotice: '' });
    });
    return () => { controller.abort(); requestVersion.current += 1; };
  }, [key, reloadFromBackend]);

  const updateDraft = updater => setState(value => value.key !== key ? value : ({ ...value, draft: updater(value.draft), savedNotice: '' }));
  const chooseMaterial = materialId => {
    const material = currentState.materials.find(item => item.id === Number(materialId));
    updateDraft(draft => ({ materialId: material?.id || null, meshes: (draft?.meshes || []).map(mesh => ({ ...mesh, customizable: false, defaultColorId: null, allowedColorIds: [] })) }));
  };
  const chooseColor = (identity, colorId) => updateDraft(draft => ({ ...draft, meshes: draft.meshes.map(mesh => identityKey(mesh) === identity ? {
    ...mesh, defaultColorId: Number(colorId), allowedColorIds: mesh.customizable
      ? [...new Set([...mesh.allowedColorIds, Number(colorId)])] : [],
  } : mesh) }));
  const toggleAllowedColor = (identity, colorId, enabled) => updateDraft(draft => ({ ...draft, meshes: draft.meshes.map(mesh => identityKey(mesh) === identity ? {
    ...mesh, allowedColorIds: enabled ? [...new Set([...mesh.allowedColorIds, colorId])] : mesh.allowedColorIds.filter(id => id !== colorId),
  } : mesh) }));
  const toggleCustomizable = (identity, enabled) => updateDraft(draft => ({ ...draft, meshes: draft.meshes.map(mesh => identityKey(mesh) === identity ? {
    ...mesh, customizable: enabled, allowedColorIds: enabled ? [mesh.defaultColorId].filter(Boolean) : [],
  } : mesh) }));

  const refreshConfiguration = async ({ confirmDiscard = false } = {}) => {
    if (confirmDiscard && isDirty && !window.confirm('Tải lại sẽ bỏ các thay đổi chưa lưu. Bạn có muốn tiếp tục?')) return;
    const controller = new AbortController();
    try {
      await reloadFromBackend(controller.signal);
      setState(value => ({ ...value, savedNotice: 'Đã tải lại cấu hình và revision được xác nhận từ Backend.' }));
    } catch (error) { if (!controller.signal.aborted) setState(value => ({ ...value, status: 'error', error: errorMessage(error) })); }
  };

  const reconcileUnknown = async () => {
    const version = requestVersion.current;
    setSaving(true);
    try {
      const configuration = await getAdminProductMeshConfiguration(productId, currentState.structure);
      if (version !== requestVersion.current) return;
      if (configurationMatchesDraft(configuration, currentState.draft)) {
        setState(value => ({ ...value, configuration, draft: configurationToDraft(configuration, currentState.structure.primitiveOccurrences, configuration.materialId), savedNotice: `Đã xác minh revision ${configuration.revision} từ Backend.` }));
        setUnknownOutcome(false); setReconciliationRequired(false);
      } else if (configuration.revision === currentState.configuration.revision) {
        setState(value => ({ ...value, configuration, savedNotice: 'Backend xác nhận revision chưa đổi. Có thể thử lưu lại thủ công.' }));
        setUnknownOutcome(false); setReconciliationRequired(false);
      } else {
        setState(value => ({ ...value, configuration, error: 'Revision đã thay đổi và không khớp bản nháp. Hãy tải lại sau khi xử lý thay đổi hiện có.' }));
        setStale(true); setUnknownOutcome(false); setReconciliationRequired(true);
      }
    } catch (error) { if (version === requestVersion.current) setState(value => ({ ...value, error: `Chưa đọc được trạng thái mới nhất: ${errorMessage(error)}` })); }
    finally { setSaving(false); }
  };

  const save = async () => {
    if (!isConfigurationValid || saving || stale || reconciliationRequired) return;
    const version = requestVersion.current;
    setSaving(true);
    setState(value => ({ ...value, error: '', savedNotice: '' }));
    let payload;
    try { payload = buildMeshConfigurationPayload(currentState.structure, currentState.configuration.revision, currentState.draft); }
    catch (error) { setState(value => ({ ...value, error: error.message })); setSaving(false); return; }
    try {
      await putAdminProductMeshConfiguration(productId, payload, currentState.structure);
      if (version !== requestVersion.current) return;
      const latest = await getAdminProductMeshConfiguration(productId, currentState.structure);
      if (version !== requestVersion.current) return;
      if (!configurationMatchesDraft(latest, currentState.draft)) throw new Error('Backend đã phản hồi PUT nhưng bản tải lại chưa khớp với dữ liệu vừa gửi.');
      setState(value => ({ ...value, configuration: latest, draft: configurationToDraft(latest, currentState.structure.primitiveOccurrences, latest.materialId), savedNotice: `Đã lưu và xác minh revision ${latest.revision} từ Backend.` }));
      setStale(false); setReconciliationRequired(false); setUnknownOutcome(false);
    } catch (error) {
      if (version !== requestVersion.current) return;
      const status = error?.response?.status;
      if (status === 409) { setStale(true); setReconciliationRequired(true); setState(value => ({ ...value, error: 'Cấu trúc, media hoặc revision đã thay đổi (409). Bản nháp được giữ lại; hãy tải lại sau khi quyết định bỏ thay đổi chưa lưu.' })); }
      else if (!error?.response || status >= 500) { setUnknownOutcome(true); setReconciliationRequired(true); setState(value => ({ ...value, error: 'Chưa xác định kết quả lưu. Đọc lại trạng thái Backend trước khi gửi lại.' })); }
      else setState(value => ({ ...value, error: errorMessage(error) }));
    } finally { setSaving(false); }
  };

  const structure = currentState.structure;
  const nodeByIndex = useMemo(() => new Map((structure?.nodes || []).map(item => [item.nodeIndex, item])), [structure]);
  const meshByIndex = useMemo(() => new Map((structure?.meshes || []).map(item => [item.meshIndex, item])), [structure]);
  const rows = (structure?.primitiveOccurrences || []).map(occurrence => ({
    ...occurrence, identity: identityKey(occurrence), draft: currentState.draft?.meshes?.find(item => identityKey(item) === identityKey(occurrence)),
    nodeName: nodeByIndex.get(occurrence.nodeIndex)?.nodeName || `Node ${occurrence.nodeIndex}`,
    meshName: meshByIndex.get(occurrence.meshIndex)?.meshName || `Mesh ${occurrence.meshIndex}`,
  }));

  return <section className="admin-mesh-config" aria-labelledby="admin-mesh-config-title">
    <div className="admin-product-section-heading"><div><p className="admin-products-kicker">CẤU HÌNH SẢN XUẤT</p><h4 id="admin-mesh-config-title">Cấu hình bộ phận &amp; màu</h4></div>
      <button type="button" className="admin-mesh-config__reload" onClick={() => refreshConfiguration({ confirmDiscard: true })} disabled={currentState.status === 'loading' || saving}>Tải lại từ Backend</button>
    </div>
    {currentState.status === 'loading' && <p role="status">Đang tải cấu trúc, revision và màu từ Backend…</p>}
    {currentState.status === 'error' && <div role="alert" className="admin-product-error">{currentState.error}<button type="button" onClick={() => refreshConfiguration()}>Thử tải lại</button></div>}
    {currentState.status === 'ready' && <>
      <dl className="admin-mesh-config__meta"><div><dt>Revision</dt><dd>{currentState.configuration.revision}</dd></div><div><dt>Snapshot</dt><dd>{structure.structureSnapshotId}</dd></div><div><dt>Occurrences</dt><dd>{structure.primitiveOccurrences.length}</dd></div></dl>
      {!currentState.configuration.configured && <p className="admin-product-note">Chưa có cấu hình được lưu. Chọn material và xác định màu mặc định cho từng primitive.</p>}
      <label className="admin-product-field admin-mesh-config__material"><span>Material áp dụng cho revision này</span><select value={currentState.draft?.materialId ?? ''} onChange={event => chooseMaterial(event.target.value)} disabled={saving || stale || reconciliationRequired}>
        <option value="">Chọn material đang khả dụng</option>{currentState.materials.map(material => <option key={material.id} value={material.id} disabled={material.isAvailable !== 'AVAILABLE'}>{material.name}{material.isAvailable !== 'AVAILABLE' ? ' (không khả dụng)' : ''}</option>)}
      </select><small>Chỉ các màu do Backend trả về cho material đã chọn được dùng trong cấu hình.</small></label>
      {currentState.configuration.configured && selectedMaterial?.isAvailable !== 'AVAILABLE' && <p className="admin-product-error" role="alert">Material đã lưu hiện không khả dụng. Có thể đọc revision, nhưng không thể tạo revision mới bằng material này.</p>}
      {activePreviewGltf && <div className="admin-mesh-config__preview"><MeshPreview gltf={activePreviewGltf} mappedObjects={mappedObjects} selectedIdentity={selectedIdentity} onSelect={setSelectedIdentity} /></div>}
      {!mapping.mapped && <p className="admin-product-note">{publishedPreviewSafe
        ? 'GLB đã xuất bản không ánh xạ duy nhất với occurrence Backend; chọn bộ phận từ danh sách bên dưới.'
        : canPreviewGltf?.gltf
        ? provenanceSafe
          ? 'Preview GLB cục bộ không ánh xạ duy nhất với occurrence Backend; chọn bộ phận từ danh sách bên dưới.'
          : 'Preview cục bộ chưa được liên kết đủ với media asset và structure snapshot Backend hiện tại; highlight đã tắt.'
        : 'GLB lưu trữ chưa khả dụng. Sau khi tải lại trang, cấu hình vẫn đọc được nhưng 3D preview bị giới hạn.'}</p>}
      {mapping.mapped && selectedIdentity && <p className="admin-product-note" role="status">Primitive {selectedIdentity} đang được highlight trong preview.</p>}
      <div className="admin-mesh-config__rows">{rows.map(row => <article className={`admin-mesh-config__row${selectedIdentity === row.identity ? ' is-selected' : ''}`} key={row.identity}>
        <button type="button" className="admin-mesh-config__select" onClick={() => setSelectedIdentity(row.identity)} aria-pressed={selectedIdentity === row.identity}>{mapping.mapped ? 'Chọn trong preview' : 'Chọn occurrence Backend'}</button>
        <h5>{row.originalName || row.meshName}</h5><p>Node: {row.nodeName} · Mesh: {row.meshName} · Primitive: {row.primitiveIndex}</p>
        {row.draft && <>
          <label className="admin-mesh-config__toggle"><input type="checkbox" checked={row.draft.customizable} disabled={!materialAvailable || !colors.length || saving || stale || reconciliationRequired} onChange={event => toggleCustomizable(row.identity, event.target.checked)} />Khách được đổi màu</label>
          <label className="admin-product-field"><span>Màu mặc định</span><select value={row.draft.defaultColorId ?? ''} disabled={!materialAvailable || !colors.length || saving || stale || reconciliationRequired} onChange={event => chooseColor(row.identity, event.target.value)}><option value="">Chọn màu</option>{colors.map(color => <option key={color.colorId} value={color.colorId}>{color.name}</option>)}</select>{row.draft.defaultColorId && colorsById.has(row.draft.defaultColorId) && <small className="admin-mesh-config__selected-color"><span className="admin-material-swatch" style={colorsById.get(row.draft.defaultColorId).hexCode ? { backgroundColor: colorsById.get(row.draft.defaultColorId).hexCode } : undefined} aria-hidden="true" />{colorsById.get(row.draft.defaultColorId).name}</small>}</label>
          {row.draft.customizable && <fieldset className="admin-mesh-config__allowed"><legend>Màu được phép</legend>{colors.map(color => <label key={color.colorId}><input type="checkbox" checked={row.draft.allowedColorIds.includes(color.colorId)} disabled={!materialAvailable || saving || stale || reconciliationRequired || color.colorId === row.draft.defaultColorId} onChange={event => toggleAllowedColor(row.identity, color.colorId, event.target.checked)} /><span className="admin-material-swatch" style={color.hexCode ? { backgroundColor: color.hexCode } : undefined} aria-hidden="true" />{color.name}{color.colorId === row.draft.defaultColorId ? ' (màu mặc định)' : ''}</label>)}</fieldset>}
        </>}
      </article>)}</div>
      {rows.length === 0 && <p>Backend chưa xác nhận primitive occurrence nào cho snapshot này.</p>}
      {currentState.error && <p role="alert" className="admin-product-error">{currentState.error}</p>}
      {currentState.savedNotice && <p role="status" className="admin-product-notice">{currentState.savedNotice}</p>}
      {isDirty && <p className="admin-mesh-config__dirty" role="status">Có thay đổi chưa lưu.</p>}
      {isDirty && !isConfigurationValid && <p className="admin-product-note" role="status">Chọn material khả dụng, màu mặc định cho mọi primitive và ít nhất một màu được phép khi bật tùy chỉnh.</p>}
      {stale && <p className="admin-product-error" role="alert">Revision hoặc snapshot đã cũ. Đọc lại trước khi tiếp tục.</p>}
      {unknownOutcome && <button type="button" onClick={reconcileUnknown} disabled={saving}>Đọc lại để xác minh kết quả lưu</button>}
      {reconciliationRequired && !unknownOutcome && <button type="button" onClick={() => refreshConfiguration({ confirmDiscard: true })} disabled={saving}>Tải revision mới (xác nhận bỏ draft)</button>}
      <div className="admin-product-actions"><button type="button" className="admin-mesh-config__save" onClick={save} disabled={!isDirty || !isConfigurationValid || saving || stale || reconciliationRequired}>{saving ? 'Đang lưu…' : 'Lưu cấu hình'}</button><span>Backend sẽ xác nhận revision và khả năng tương thích material/color.</span></div>
    </>}
  </section>;
}
