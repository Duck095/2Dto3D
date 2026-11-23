import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { OrbitControls } from '@react-three/drei';
import * as THREE from 'three';
import { Box3, Box3Helper } from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader';
import AdminProductMeshConfiguration from './AdminProductMeshConfiguration';
import { useAuth } from '../../context/AuthContext';
import AdminTable from '../../components/admin/AdminTable';
import StatusBadge from '../../components/admin/StatusBadge';
import Button from '../../components/ui/Button';
import EmptyState from '../../components/ui/EmptyState';
import ErrorState from '../../components/ui/ErrorState';
import { archiveAdminProduct, createAdminProductDraft, getAdminProduct, getPublishedAdminProductMedia, listAdminProducts, publishAdminProduct, updateAdminProduct } from '../../features/admin/products/adminProductApi';
import { buildMetadataPayload, classifyAdminApiError, getAdminMutationErrorMessage, inspectGlbScene, validateGlbFile, validateOfflineGlb } from '../../features/admin/products/adminProductContract';
import './admin-products.css';

const PAGE_SIZE = 20;
const statusLabel = { PENDING: 'Bản nháp', ACTIVE: 'Đang hoạt động', INACTIVE: 'Đã lưu trữ' };
const statusTone = { PENDING: 'warning', ACTIVE: 'success', INACTIVE: 'neutral' };
const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const IMAGE_EXTENSIONS = /\.(jpe?g|png|webp)$/i;
const MAX_IMAGE = 10 * 1024 * 1024;

function dateLabel(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(date);
}

function AdminTextField({ label, hint, as = 'input', ...props }) {
  const id = `admin-product-${props.name}`;
  const Control = as;
  return <label className="admin-product-field" htmlFor={id}><span>{label}</span><Control id={id} {...props} aria-describedby={hint ? `${id}-hint` : undefined} />{hint && <small id={`${id}-hint`}>{hint}</small>}</label>;
}

function SelectionBounds({ objects }) {
  const helper = useMemo(() => new Box3Helper(new Box3(), '#7556d8'), []);
  useFrame(() => {
    helper.box.makeEmpty();
    objects.forEach(object => {
      object.updateWorldMatrix(true, false);
      helper.box.expandByObject(object);
    });
    helper.updateMatrixWorld(true);
  });
  return <primitive object={helper} />;
}

function InspectorScene({ gltf, meshIndex, onSelectObject }) {
  const selected = meshIndex.meshes.find(mesh => mesh.id === meshIndex.selectedId);
  const controls = useRef(null);
  const camera = useThree(state => state.camera);
  useEffect(() => {
    gltf.scene.updateMatrixWorld(true);
    const bounds = new Box3().setFromObject(gltf.scene);
    if (bounds.isEmpty()) return;
    const center = bounds.getCenter(new THREE.Vector3());
    const size = bounds.getSize(new THREE.Vector3());
    const maxDimension = Math.max(size.x, size.y, size.z) || 1;
    const fovRadians = THREE.MathUtils.degToRad(camera.fov || 45);
    const distance = (maxDimension / (2 * Math.tan(fovRadians / 2))) * 1.65;
    camera.position.set(center.x + distance * 0.68, center.y + distance * 0.48, center.z + distance);
    camera.near = Math.max(distance / 1000, 0.001);
    camera.far = Math.max(distance * 100, 1000);
    camera.lookAt(center);
    camera.updateProjectionMatrix();
    if (controls.current) {
      controls.current.target.copy(center);
      controls.current.update();
    }
  }, [camera, gltf]);
  return <>
    <ambientLight intensity={1.25} />
    <directionalLight position={[4, 6, 5]} intensity={1.1} />
    <primitive object={gltf.scene} onClick={event => { event.stopPropagation(); onSelectObject(event.object); }} />
    {selected && <SelectionBounds objects={selected.objects} />}
    <OrbitControls ref={controls} makeDefault />
  </>;
}

function MeshInspector({ file, gltf, result, loading, error, selectedId, displayNames, onSelect, onDisplayNameChange }) {
  const meshLookup = useMemo(() => {
    const lookup = new WeakMap();
    result?.meshes.forEach(mesh => mesh.objects.forEach(object => lookup.set(object, mesh.id)));
    return lookup;
  }, [result]);
  const viewerIndex = useMemo(() => ({ meshes: result?.meshes || [], selectedId }), [result, selectedId]);
  const displayMeshes = result?.meshes.map(mesh => ({ ...mesh, displayName: displayNames[mesh.id] ?? mesh.name })) || [];
  if (!file) return null;
  return <section className="admin-glb-inspector" aria-labelledby="admin-glb-inspector-title">
    <div className="admin-product-section-heading"><div><p className="admin-products-kicker">KIỂM TRA CỤC BỘ</p><h4 id="admin-glb-inspector-title">GLB Inspector</h4></div><span className="admin-glb-inspector__local">Chỉ xem trong phiên này</span></div>
    {loading && <p role="status">Đang đọc cấu trúc GLB…</p>}
    {error && <p className="admin-product-error" role="alert">{error}</p>}
    {result && <>
      {result.warnings.length > 0 && <section className="admin-glb-warnings" aria-label="Cảnh báo cấu trúc"><strong>Cảnh báo cấu trúc</strong><ul>{result.warnings.map((warning, index) => <li key={`${warning.code}-${warning.meshId || 'global'}-${index}`}>{warning.message}</li>)}</ul></section>}
      <div className="admin-glb-inspector__layout">
        <div className="admin-glb-viewer" aria-label="Xem trước mô hình GLB">
          {gltf && <Canvas camera={{ position: [3, 2.5, 4], fov: 45 }} dpr={[1, 1.5]}>
            <color attach="background" args={['#f4f3f8']} />
            <InspectorScene gltf={gltf} meshIndex={viewerIndex} onSelectObject={object => onSelect(meshLookup.get(object))} />
          </Canvas>}
          {!loading && !error && result.meshes.length === 0 && <p className="admin-glb-viewer__empty">Không có mesh hình học để xem.</p>}
        </div>
        <div className="admin-glb-mesh-list" aria-label="Danh sách mesh">
          {displayMeshes.length === 0 ? <p>Không tìm thấy mesh có geometry.</p> : displayMeshes.map((mesh, index) => <article className="admin-glb-mesh" key={mesh.id}>
            <button type="button" aria-pressed={selectedId === mesh.id} onClick={() => onSelect(mesh.id)} className="admin-glb-mesh__select">
              <strong>{mesh.displayName || mesh.name || `Mesh ${index + 1}`}</strong><span>{selectedId === mesh.id ? 'Đang chọn' : 'Chọn mesh'}</span>
            </button>
            <label className="admin-product-field"><span>Tên hiển thị trong phiên</span><input value={displayNames[mesh.id] ?? mesh.name} maxLength={120} onChange={event => onDisplayNameChange(mesh.id, event.target.value)} /></label>
            <dl><div><dt>Tên GLB</dt><dd>{mesh.name}</dd></div><div><dt>Primitive</dt><dd>{mesh.primitiveCount}</dd></div><div><dt>Material</dt><dd>{mesh.materialNames.length ? mesh.materialNames.join(', ') : 'Không có material'}</dd></div></dl>
          </article>)}
        </div>
      </div>
      <p className="admin-product-note">ID inspect được tạo từ đường dẫn node trong scene, chỉ có hiệu lực trong phiên này và chưa ánh xạ với cấu hình sản xuất. Preview không sửa material, texture hay màu trong GLB.</p>
    </>}
  </section>;
}

export default function AdminProductsPage() {
  const { user, currentUser, sessionVersion } = useAuth();
  const sessionIdentity = `${currentUser?.id ?? user?.id ?? user?.email ?? 'unknown-session'}:${sessionVersion}`;
  const [filters, setFilters] = useState({ q: '', status: '' });
  const [draftFilters, setDraftFilters] = useState(filters);
  const [pageIndex, setPageIndex] = useState(0);
  const [reload, setReload] = useState(0);
  const [page, setPage] = useState({ items: [], totalPages: 0, totalElements: 0, loading: true, error: null });
  const [createOpen, setCreateOpen] = useState(false);
  const [createValues, setCreateValues] = useState({ name: '', description: '', category: '' });
  const [images, setImages] = useState([]);
  const [modelFile, setModelFile] = useState(null);
  const [glbInspection, setGlbInspection] = useState({ file: null, gltf: null, result: null, loading: false, error: '' });
  const [selectedMeshId, setSelectedMeshId] = useState(null);
  const [meshDisplayNames, setMeshDisplayNames] = useState({});
  const [createError, setCreateError] = useState('');
  const [creating, setCreating] = useState(false);
  const [selectedId, setSelectedId] = useState(null);
  const [meshHasUnsavedChanges, setMeshHasUnsavedChanges] = useState(false);
  const [localUploadedModel, setLocalUploadedModel] = useState(null);
  const [publishedPreview, setPublishedPreview] = useState({ productId: null, status: 'idle', gltf: null, error: '' });
  const [publishedMediaRetry, setPublishedMediaRetry] = useState(0);
  const [detail, setDetail] = useState({ product: null, loading: false, error: null });
  const [editValues, setEditValues] = useState(null);
  const [editing, setEditing] = useState(false);
  const [mutation, setMutation] = useState({ id: null, action: '', message: '', error: '' });

  const refresh = useCallback(() => setReload(value => value + 1), []);
  const requestSelectProduct = useCallback(nextId => {
    if (nextId !== selectedId && meshHasUnsavedChanges && !window.confirm('Có thay đổi cấu hình mesh chưa lưu. Bạn có muốn bỏ thay đổi và chuyển sản phẩm?')) return;
    setSelectedId(nextId);
  }, [meshHasUnsavedChanges, selectedId]);
  const rememberLocalPreviewProvenance = useCallback(proof => setLocalUploadedModel(current => {
    if (!current || current.sessionIdentity !== sessionIdentity || current.productId !== proof.productId
      || current.filePath3d !== proof.filePath3d || current.mediaAssetId || current.structureSnapshotId) return current;
    return { ...current, mediaAssetId: proof.mediaAssetId, structureSnapshotId: proof.structureSnapshotId };
  }), [sessionIdentity]);
  const params = useMemo(() => ({ page: pageIndex, size: PAGE_SIZE, ...filters }), [pageIndex, filters]);

  useEffect(() => {
    setSelectedId(null);
    setDetail({ product: null, loading: false, error: null });
    setEditValues(null);
    setLocalUploadedModel(null);
    setMeshHasUnsavedChanges(false);
    setReload(value => value + 1);
  }, [sessionIdentity]);

  useEffect(() => {
    if (!modelFile) {
      setGlbInspection({ file: null, gltf: null, result: null, loading: false, error: '' });
      setSelectedMeshId(null);
      setMeshDisplayNames({});
      return undefined;
    }
    let cancelled = false;
    setGlbInspection({ file: modelFile, gltf: null, result: null, loading: true, error: '' });
    setSelectedMeshId(null);
    setMeshDisplayNames({});
    const validationError = validateGlbFile(modelFile);
    if (validationError) {
      setGlbInspection({ file: modelFile, gltf: null, result: null, loading: false, error: validationError });
      return undefined;
    }
    (async () => {
      try {
        const buffer = await modelFile.arrayBuffer();
        const offlineError = validateOfflineGlb(buffer);
        if (offlineError) throw Object.assign(new Error(offlineError), { offlineValidation: true });
        const gltf = await new Promise((resolve, reject) => new GLTFLoader().parse(buffer, '', resolve, reject));
        if (cancelled) return;
        const result = inspectGlbScene(gltf.scene, gltf.parser?.associations);
        setGlbInspection({ file: modelFile, gltf, result, loading: false, error: '' });
        setSelectedMeshId(result.meshes[0]?.id || null);
      } catch (error) {
        if (!cancelled) setGlbInspection({ file: modelFile, gltf: null, result: null, loading: false, error: error.offlineValidation ? error.message : 'Không thể đọc GLB. Hãy kiểm tra lại tệp hoặc chọn một GLB hợp lệ.' });
      }
    })();
    return () => { cancelled = true; };
  }, [modelFile]);

  useEffect(() => {
    const controller = new AbortController();
    setPage(current => ({ ...current, loading: true, error: null }));
    listAdminProducts(params, { signal: controller.signal }).then(result => {
      if (!controller.signal.aborted) setPage({ ...result, loading: false, error: null });
    }).catch(error => {
      if (!controller.signal.aborted) setPage({ items: [], totalPages: 0, totalElements: 0, loading: false, error });
    });
    return () => controller.abort();
  }, [params, reload, sessionIdentity]);

  useEffect(() => {
    if (selectedId == null) { setDetail({ product: null, loading: false, error: null }); return undefined; }
    const controller = new AbortController();
    setDetail({ product: null, loading: true, error: null });
    getAdminProduct(selectedId, { signal: controller.signal }).then(product => {
      if (!controller.signal.aborted) { setDetail({ product, loading: false, error: null }); setEditValues(buildMetadataPayload(product)); }
    }).catch(error => {
      if (!controller.signal.aborted) setDetail({ product: null, loading: false, error });
    });
    return () => controller.abort();
  }, [selectedId, reload, sessionIdentity]);

  useEffect(() => {
    const product = detail.product;
    if (!product || Number(product.id) !== Number(selectedId)) {
      setPublishedPreview({ productId: null, status: 'idle', gltf: null, error: '' });
      return undefined;
    }
    if (product.status !== 'ACTIVE') {
      setPublishedPreview({ productId: Number(product.id), status: 'unavailable', gltf: null, error: '' });
      return undefined;
    }

    const controller = new AbortController();
    let cancelled = false;
    setPublishedPreview({ productId: Number(product.id), status: 'loading', gltf: null, error: '' });
    getPublishedAdminProductMedia(product.id, { signal: controller.signal }).then(({ model3dUrl }) =>
      new GLTFLoader().loadAsync(model3dUrl)).then(gltf => {
      if (!cancelled && !controller.signal.aborted) {
        setPublishedPreview({ productId: Number(product.id), status: 'ready', gltf, error: '' });
      }
    }).catch(() => {
      if (!cancelled && !controller.signal.aborted) {
        setPublishedPreview({ productId: Number(product.id), status: 'error', gltf: null, error: 'Không thể tải GLB công khai đã xác thực. Hãy thử tải lại.' });
      }
    });
    return () => { cancelled = true; controller.abort(); };
  }, [detail.product, selectedId, sessionIdentity, publishedMediaRetry]);

  const retryPublishedMedia = useCallback(() => setPublishedMediaRetry(value => value + 1), []);

  const applyFilters = event => {
    event.preventDefault();
    setPageIndex(0);
    setFilters({ q: draftFilters.q.trim(), status: draftFilters.status });
  };

  const validateFiles = () => {
    if (modelFile && (glbInspection.file !== modelFile || glbInspection.loading)) return 'Đang kiểm tra GLB. Hãy đợi quá trình phân tích hoàn tất.';
    if (modelFile && glbInspection.error) return glbInspection.error;
    if (modelFile && !glbInspection.result) return 'Chưa xác nhận được cấu trúc GLB. Hãy chọn lại tệp hợp lệ.';
    if (images.length < 1 || images.length > 8) return 'Chọn từ 1 đến 8 ảnh sản phẩm.';
    if (images.some(file => !IMAGE_TYPES.has(file.type) || !IMAGE_EXTENSIONS.test(file.name) || file.size <= 0 || file.size > MAX_IMAGE)) return 'Ảnh phải là JPG, PNG hoặc WebP, có dữ liệu và không vượt quá 10 MiB mỗi tệp.';
    return validateGlbFile(modelFile) || '';
  };

  const chooseModelFile = file => {
    setModelFile(file);
    setGlbInspection({ file, gltf: null, result: null, loading: Boolean(file), error: '' });
  };

  const submitCreate = async event => {
    event.preventDefault();
    const fileError = validateFiles();
    if (!createValues.name.trim()) { setCreateError('Tên sản phẩm là bắt buộc.'); return; }
    if (createValues.name.length > 150 || createValues.description.length > 255 || createValues.category.length > 100) { setCreateError('Tên tối đa 150 ký tự, mô tả 255 ký tự và danh mục 100 ký tự.'); return; }
    if (fileError) { setCreateError(fileError); return; }
    setCreating(true); setCreateError('');
    try {
      const created = await createAdminProductDraft({ ...createValues, name: createValues.name.trim() }, images, modelFile);
      if (Number.isInteger(Number(created?.id)) && Number(created.id) > 0 && glbInspection.file === modelFile && glbInspection.gltf) {
        setLocalUploadedModel({ uploadConfirmed: true, productId: Number(created.id), filePath3d: created.filePath3d,
          sourceFile: modelFile, sessionIdentity, gltf: glbInspection.gltf });
      }
      setCreateOpen(false); setCreateValues({ name: '', description: '', category: '' }); setImages([]); setModelFile(null); refresh();
      setMutation({ id: null, action: '', message: 'Backend đã tạo bản nháp. Danh sách vừa được tải lại từ máy chủ.', error: '' });
    } catch (error) { setCreateError(getAdminMutationErrorMessage(error, 'create')); }
    finally { setCreating(false); }
  };

  const submitEdit = async event => {
    event.preventDefault();
    if (!detail.product || editing) return;
    if (!editValues.name.trim() || editValues.name.length > 150 || editValues.description.length > 255 || editValues.category.length > 100) { setMutation({ id: selectedId, action: 'edit', message: '', error: 'Tên là bắt buộc; giới hạn lần lượt là 150, 255 và 100 ký tự.' }); return; }
    setEditing(true); setMutation({ id: selectedId, action: 'edit', message: '', error: '' });
    try {
      const updated = await updateAdminProduct(selectedId, { ...editValues, name: editValues.name.trim() });
      setDetail({ product: updated, loading: false, error: null }); setMutation({ id: selectedId, action: '', message: 'Thông tin đã được Backend cập nhật.', error: '' }); refresh();
    } catch (error) { setMutation({ id: selectedId, action: '', message: '', error: getAdminMutationErrorMessage(error, 'edit') }); }
    finally { setEditing(false); }
  };

  const runAction = async (product, action) => {
    const verb = action === 'publish' ? 'xuất bản' : 'lưu trữ';
    if (!window.confirm(`Bạn có chắc muốn ${verb} “${product.name}”?`)) return;
    setMutation({ id: product.id, action, message: '', error: '' });
    try {
      if (action === 'publish') await publishAdminProduct(product.id); else await archiveAdminProduct(product.id);
      refresh();
      setMutation({ id: product.id, action: '', message: `Backend đã xác nhận yêu cầu ${verb}; trạng thái đang được tải lại.`, error: '' });
    } catch (error) {
      const classified = classifyAdminApiError(error);
      if (classified.kind === 'unknown-outcome') {
        try {
          const latest = await getAdminProduct(product.id);
          refresh();
          const reached = action === 'publish' ? latest.status === 'ACTIVE' : latest.status === 'INACTIVE';
          setMutation({ id: product.id, action: '', message: reached ? `Đã xác minh trạng thái mới từ Backend: ${statusLabel[latest.status]}.` : `Chưa thể xác minh kết quả. Trạng thái mới nhất: ${statusLabel[latest.status]}.`, error: '' });
        } catch { setMutation({ id: product.id, action: '', message: '', error: 'Kết nối bị gián đoạn và chưa xác minh được trạng thái. Hãy tải lại danh sách trước khi thử lại.' }); }
      } else setMutation({ id: product.id, action: '', message: '', error: classified.kind === 'conflict' && action === 'publish' ? 'Chưa thể xuất bản do sản phẩm chưa đáp ứng điều kiện của Backend.' : classified.message });
    }
  };

  const currentPage = page.page ?? pageIndex;
  const totalPages = page.totalPages || 0;

  return <div className="admin-products-page">
    <header className="admin-page-heading"><p className="admin-page-heading__eyebrow">KINH DOANH</p><h2>Quản lý sản phẩm</h2><p>Quản lý bản thiết kế do người dùng gửi lên và trạng thái hiển thị do Backend xác nhận.</p></header>
    <div className="admin-products-toolbar"><div><p className="admin-products-kicker">DANH MỤC QUẢN TRỊ</p><p>{page.totalElements ?? 0} sản phẩm trong kết quả hiện tại</p></div><Button type="button" onClick={() => { setCreateOpen(value => !value); setCreateError(''); }}>{createOpen ? 'Đóng biểu mẫu' : 'Tạo bản nháp'}</Button></div>

    {createOpen && <section className="admin-product-card" aria-labelledby="admin-product-create-title"><div className="admin-product-section-heading"><div><p className="admin-products-kicker">TẠO MỚI</p><h3 id="admin-product-create-title">Thông tin bản nháp</h3></div></div>
      <form className="admin-product-form" onSubmit={submitCreate} noValidate>
        <AdminTextField label="Tên sản phẩm" name="name" value={createValues.name} maxLength={150} required hint="Tối đa 150 ký tự." onChange={event => setCreateValues(value => ({ ...value, name: event.target.value }))} />
        <AdminTextField label="Danh mục" name="category" value={createValues.category} maxLength={100} hint="Tối đa 100 ký tự." onChange={event => setCreateValues(value => ({ ...value, category: event.target.value }))} />
        <AdminTextField as="textarea" label="Mô tả" name="description" value={createValues.description} maxLength={255} hint="Tối đa 255 ký tự." onChange={event => setCreateValues(value => ({ ...value, description: event.target.value }))} />
        <AdminTextField as="input" type="file" label="Ảnh sản phẩm" name="images" multiple accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" hint="Chọn 1–8 ảnh JPG, PNG hoặc WebP; tối đa 10 MiB mỗi ảnh." onChange={event => setImages(Array.from(event.target.files || []))} />
        <AdminTextField as="input" type="file" label="Mô hình 3D (GLB)" name="model" accept=".glb,model/gltf-binary,application/octet-stream" hint="Một tệp .glb, tối đa 50 MiB. Trình duyệt phải nhận diện MIME là model/gltf-binary hoặc application/octet-stream; Backend vẫn kiểm tra nội dung GLB thực tế." onChange={event => chooseModelFile(event.target.files?.[0] || null)} />
        <MeshInspector file={modelFile} gltf={glbInspection.gltf} result={glbInspection.result} loading={glbInspection.loading} error={glbInspection.error} selectedId={selectedMeshId} displayNames={meshDisplayNames} onSelect={id => id && setSelectedMeshId(id)} onDisplayNameChange={(id, name) => setMeshDisplayNames(current => ({ ...current, [id]: name }))} />
        {createError && <p className="admin-product-error" role="alert">{createError}</p>}
        <div className="admin-product-actions"><Button type="submit" loading={creating} disabled={creating || glbInspection.loading || Boolean(modelFile && (glbInspection.file !== modelFile || glbInspection.error || !glbInspection.result))}>Tạo bản nháp</Button>{glbInspection.loading && <span role="status">Đang phân tích GLB; chưa thể gửi biểu mẫu.</span>}<span>Thao tác gửi tệp lên Backend; không tạo giá bán hoặc đơn hàng.</span></div>
      </form>
    </section>}

    {mutation.message && <p className="admin-product-notice" role="status">{mutation.message}</p>}
    {mutation.error && <p className="admin-product-error" role="alert">{mutation.error}</p>}

    <form className="admin-product-filters" onSubmit={applyFilters}>
      <AdminTextField label="Tìm theo tên" name="q" value={draftFilters.q} maxLength={100} hint="Tối đa 100 ký tự." onChange={event => setDraftFilters(value => ({ ...value, q: event.target.value }))} />
      <AdminTextField as="select" label="Trạng thái" name="status" value={draftFilters.status} onChange={event => setDraftFilters(value => ({ ...value, status: event.target.value }))}><option value="">Tất cả trạng thái</option><option value="PENDING">Bản nháp</option><option value="ACTIVE">Đang hoạt động</option><option value="INACTIVE">Đã lưu trữ</option></AdminTextField>
      <div className="admin-product-actions"><Button type="submit" variant="outline" disabled={page.loading}>Lọc danh sách</Button><Button type="button" variant="ghost" onClick={() => { const empty = { q: '', status: '' }; setDraftFilters(empty); setFilters(empty); setPageIndex(0); }} disabled={!filters.q && !filters.status}>Xóa lọc</Button></div>
    </form>

    {page.error ? <ErrorState title="Không thể tải sản phẩm" message={classifyAdminApiError(page.error).message} onRetry={refresh} /> : page.loading ? <div className="admin-product-loading" role="status" aria-live="polite">Đang tải danh sách sản phẩm…</div> : !page.items.length ? <EmptyState title="Chưa có sản phẩm phù hợp" description="Kết quả sẽ xuất hiện khi Backend trả về sản phẩm phù hợp với bộ lọc." /> : <>
      <AdminTable label="Danh sách sản phẩm"><thead><tr><th>Sản phẩm</th><th>Danh mục</th><th>Trạng thái</th><th>Ngày tạo</th><th>Thao tác</th></tr></thead><tbody>{page.items.map(product => <tr key={product.id}>
        <td><strong>{product.name || 'Chưa đặt tên'}</strong><small className="admin-product-id">ID: {product.id}</small></td><td>{product.category || '—'}</td><td><StatusBadge tone={statusTone[product.status] || 'neutral'}>{statusLabel[product.status] || product.status}</StatusBadge></td><td>{dateLabel(product.createdAt)}</td>
        <td><div className="admin-product-row-actions"><Button type="button" size="sm" variant="outline" onClick={() => { requestSelectProduct(product.id); setMutation({ id: null, action: '', message: '', error: '' }); }}>Chỉnh sửa</Button>{product.status === 'PENDING' && <Button type="button" size="sm" onClick={() => runAction(product, 'publish')} disabled={mutation.id === product.id && mutation.action !== ''}>{mutation.id === product.id && mutation.action === 'publish' ? 'Đang xuất bản…' : 'Xuất bản'}</Button>}{product.status !== 'INACTIVE' && <Button type="button" size="sm" variant="danger" onClick={() => runAction(product, 'archive')} disabled={mutation.id === product.id && mutation.action !== ''}>{mutation.id === product.id && mutation.action === 'archive' ? 'Đang lưu trữ…' : 'Lưu trữ'}</Button>}</div></td>
      </tr>)}</tbody></AdminTable>
      <nav className="admin-product-pagination" aria-label="Phân trang sản phẩm"><span>Trang {currentPage + 1}{totalPages ? ` / ${totalPages}` : ''} · {page.totalElements} sản phẩm</span><div><Button type="button" size="sm" variant="outline" disabled={page.loading || currentPage <= 0} onClick={() => setPageIndex(value => Math.max(0, value - 1))}>Trước</Button><Button type="button" size="sm" variant="outline" disabled={page.loading || currentPage + 1 >= totalPages} onClick={() => setPageIndex(value => value + 1)}>Tiếp</Button></div></nav>
    </>}

    {selectedId != null && <section className="admin-product-card" aria-labelledby="admin-product-edit-title"><div className="admin-product-section-heading"><div><p className="admin-products-kicker">CHỈNH SỬA</p><h3 id="admin-product-edit-title">{detail.product?.name || `Sản phẩm ${selectedId}`}</h3></div><Button type="button" size="sm" variant="ghost" onClick={() => { if (meshHasUnsavedChanges && !window.confirm('Có thay đổi cấu hình mesh chưa lưu. Bạn có muốn đóng và bỏ thay đổi?')) return; setSelectedId(null); setEditValues(null); }}>Đóng</Button></div>
      {detail.loading ? <p role="status">Đang tải chi tiết sản phẩm…</p> : detail.error ? <ErrorState title="Không thể tải chi tiết" message={classifyAdminApiError(detail.error).message} onRetry={refresh} /> : detail.product && editValues && <>
        <dl className="admin-product-file-info"><div><dt>ID</dt><dd>{detail.product.id}</dd></div><div><dt>Mô hình hiện tại</dt><dd>{detail.product.filePath3d ? 'Đã có tệp mô hình' : 'Backend chưa cung cấp thông tin tệp'}</dd></div><div><dt>Ảnh</dt><dd>{Array.isArray(detail.product.modelImages) ? `${detail.product.modelImages.length} ảnh` : 'Backend chưa cung cấp thông tin ảnh'}</dd></div></dl>
        {detail.product.status === 'ACTIVE' && publishedPreview.productId === Number(selectedId) && publishedPreview.status === 'loading' && <p role="status">Đang tải lại GLB đã xuất bản…</p>}
        {detail.product.status === 'ACTIVE' && publishedPreview.productId === Number(selectedId) && publishedPreview.status === 'error' && <div className="admin-product-error" role="alert">{publishedPreview.error} <Button type="button" size="sm" variant="outline" onClick={retryPublishedMedia}>Thử tải lại GLB</Button></div>}
        {detail.product.status !== 'ACTIVE' && <p className="admin-product-note">GLB lưu trữ chỉ được tải lại sau khi sản phẩm được xuất bản. Bản nháp chỉ dùng preview cục bộ trong phiên đã tải lên.</p>}
        <p className="admin-product-note">Thay ảnh hoặc file GLB chưa được hỗ trợ trong phiên bản này.</p>
        <form className="admin-product-form" onSubmit={submitEdit} noValidate><AdminTextField label="Tên sản phẩm" name="edit-name" value={editValues.name || ''} maxLength={150} required onChange={event => setEditValues(value => ({ ...value, name: event.target.value }))} /><AdminTextField label="Danh mục" name="edit-category" value={editValues.category || ''} maxLength={100} onChange={event => setEditValues(value => ({ ...value, category: event.target.value }))} /><AdminTextField as="textarea" label="Mô tả" name="edit-description" value={editValues.description || ''} maxLength={255} onChange={event => setEditValues(value => ({ ...value, description: event.target.value }))} />
          {mutation.id === selectedId && mutation.error && <p role="alert" className="admin-product-error">{mutation.error}</p>}<div className="admin-product-actions"><Button type="submit" loading={editing}>Lưu thông tin</Button></div></form>
      </>}
      {detail.product && <AdminProductMeshConfiguration key={`${selectedId}:${sessionIdentity}`} productId={selectedId} backendProduct={detail.product} canPreviewGltf={localUploadedModel?.productId === Number(selectedId) && localUploadedModel?.sessionIdentity === sessionIdentity ? localUploadedModel : null} publishedPreviewGltf={publishedPreview.productId === Number(selectedId) && publishedPreview.status === 'ready' ? { productId: publishedPreview.productId, gltf: publishedPreview.gltf } : null} onPreviewProvenanceObserved={rememberLocalPreviewProvenance} onUnsavedChange={setMeshHasUnsavedChanges} />}
    </section>}
  </div>;
}
