const PRODUCT_STATUSES = new Set(['PENDING', 'ACTIVE', 'INACTIVE']);
const HEX_COLOR = /^#(?:[\da-f]{3}|[\da-f]{6})$/i;
export const MAX_GLB_BYTES = 50 * 1024 * 1024;
const GLB_MIME_TYPES = new Set(['model/gltf-binary', 'application/octet-stream']);

export function isAdminProduct(product) {
  return Boolean(product && (Number.isInteger(product.id) || (typeof product.id === 'string' && product.id.length > 0))
    && product.sourceType === 'DESIGNER_MODEL'
    && typeof product.name === 'string'
    && PRODUCT_STATUSES.has(product.status));
}

export function isAdminProductPage(page) {
  return Boolean(page && Array.isArray(page.items) && page.items.every(isAdminProduct)
    && Number.isInteger(page.page) && page.page >= 0
    && Number.isInteger(page.size) && page.size > 0
    && Number.isInteger(page.totalElements) && page.totalElements >= 0
    && Number.isInteger(page.totalPages) && page.totalPages >= 0);
}

export function isMaterialList(materials) {
  return Array.isArray(materials) && materials.every(material => material
    && (Number.isInteger(material.id) || (typeof material.id === 'string' && material.id.length > 0))
    && typeof material.name === 'string'
    && Object.hasOwn(material, 'type')
    && (typeof material.type === 'string' || material.type === null)
    && ['AVAILABLE', 'UNAVAILABLE'].includes(material.isAvailable)
    && Array.isArray(material.colors)
    && material.colors.every(color => color
      && positiveInteger(color.colorId)
      && typeof color.name === 'string'
      && (color.hexCode == null || HEX_COLOR.test(color.hexCode)))
    && new Set(material.colors.map(color => color.colorId)).size === material.colors.length);
}

const nonNegativeInteger = value => Number.isInteger(value) && value >= 0;
const positiveInteger = value => Number.isInteger(value) && value > 0;
const isIdentity = item => item && nonNegativeInteger(item.nodeIndex)
  && nonNegativeInteger(item.meshIndex) && nonNegativeInteger(item.primitiveIndex);
const identityKey = item => `${item.nodeIndex}:${item.meshIndex}:${item.primitiveIndex}`;
const isUuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);

export function isMeshStructureResponse(value, expectedProductId) {
  if (!value || !positiveInteger(value.productId) || value.productId !== Number(expectedProductId)
    || !positiveInteger(value.mediaAssetId) || !isUuid(value.structureSnapshotId)
    || typeof value.parserVersion !== 'string' || !value.parserVersion.trim()
    || !nonNegativeInteger(value.defaultSceneIndex)) return false;
  if (!['scenes', 'nodes', 'meshes', 'primitiveOccurrences', 'materials', 'textures', 'images'].every(key => Array.isArray(value[key]))) return false;
  if (!value.scenes.length || !value.nodes.length || !value.meshes.length || !value.primitiveOccurrences.length) return false;
  const uniqueIndices = (items, field) => items.every(item => item && nonNegativeInteger(item[field]))
    && new Set(items.map(item => item[field])).size === items.length;
  if (!uniqueIndices(value.scenes, 'sceneIndex') || !uniqueIndices(value.nodes, 'nodeIndex')
    || !uniqueIndices(value.meshes, 'meshIndex') || !uniqueIndices(value.materials, 'materialIndex')
    || !uniqueIndices(value.textures, 'textureIndex') || !uniqueIndices(value.images, 'imageIndex')) return false;
  const sceneIds = new Set(value.scenes.map(item => item.sceneIndex));
  const nodeIds = new Set(value.nodes.map(item => item.nodeIndex));
  const meshIds = new Set(value.meshes.map(item => item.meshIndex));
  const materialIds = new Set(value.materials.map(item => item.materialIndex));
  const textureIds = new Set(value.textures.map(item => item.textureIndex));
  const imageIds = new Set(value.images.map(item => item.imageIndex));
  if (!sceneIds.has(value.defaultSceneIndex)) return false;
  if (!value.scenes.every(item => (item.name == null || typeof item.name === 'string')
    && Array.isArray(item.rootNodeIndices) && item.rootNodeIndices.every(index => nodeIds.has(index)))) return false;
  if (!value.nodes.every(item => (item.nodeName == null || typeof item.nodeName === 'string')
    && (item.meshIndex == null || meshIds.has(item.meshIndex)) && Array.isArray(item.childNodeIndices)
    && item.childNodeIndices.every(index => nodeIds.has(index)) && Array.isArray(item.sceneIndices)
    && item.sceneIndices.every(index => sceneIds.has(index)))) return false;
  if (!value.meshes.every(item => (item.meshName == null || typeof item.meshName === 'string') && Array.isArray(item.primitives)
    && item.primitives.every(primitive => primitive && nonNegativeInteger(primitive.primitiveIndex)
      && (primitive.materialIndex == null || materialIds.has(primitive.materialIndex))
      && Array.isArray(primitive.attributeNames) && primitive.attributeNames.every(name => typeof name === 'string')
      && Array.isArray(primitive.textureIndices) && primitive.textureIndices.every(index => textureIds.has(index)))
    && new Set(item.primitives.map(primitive => primitive.primitiveIndex)).size === item.primitives.length)) return false;
  if (!value.materials.every(item => (item.name == null || typeof item.name === 'string') && Array.isArray(item.textureIndices)
    && item.textureIndices.every(index => textureIds.has(index)))) return false;
  if (!value.textures.every(item => Array.isArray(item.sourceImageIndices)
    && item.sourceImageIndices.every(index => imageIds.has(index))
    && (item.samplerIndex == null || nonNegativeInteger(item.samplerIndex)))) return false;
  if (!value.images.every(item => (item.name == null || typeof item.name === 'string')
    && (item.mimeType == null || typeof item.mimeType === 'string') && typeof item.uriSource === 'boolean'
    && (item.bufferViewIndex == null || nonNegativeInteger(item.bufferViewIndex)))) return false;
  const declaredOccurrences = new Set();
  for (const occurrence of value.primitiveOccurrences) {
    if (!isIdentity(occurrence) || (occurrence.originalName != null && typeof occurrence.originalName !== 'string')
      || (occurrence.materialIndex != null && !materialIds.has(occurrence.materialIndex))) return false;
    const key = identityKey(occurrence);
    if (declaredOccurrences.has(key)) return false;
    declaredOccurrences.add(key);
    const node = value.nodes.find(item => item.nodeIndex === occurrence.nodeIndex);
    const mesh = value.meshes.find(item => item.meshIndex === occurrence.meshIndex);
    if (!node || node.meshIndex !== occurrence.meshIndex || !mesh
      || !mesh.primitives.some(item => item.primitiveIndex === occurrence.primitiveIndex)) return false;
  }
  const expectedOccurrences = new Set();
  for (const node of value.nodes) {
    if (node.meshIndex == null) continue;
    const mesh = value.meshes.find(item => item.meshIndex === node.meshIndex);
    if (!mesh) return false;
    for (const primitive of mesh.primitives) expectedOccurrences.add(`${node.nodeIndex}:${node.meshIndex}:${primitive.primitiveIndex}`);
  }
  return expectedOccurrences.size === declaredOccurrences.size
    && [...expectedOccurrences].every(identity => declaredOccurrences.has(identity));
}

export function isMeshConfigurationResponse(value, structure) {
  if (!value || !structure || value.productId !== structure.productId || value.mediaAssetId !== structure.mediaAssetId
    || value.structureSnapshotId !== structure.structureSnapshotId || !Number.isSafeInteger(value.revision) || value.revision < 0
    || typeof value.configured !== 'boolean' || !Array.isArray(value.meshes)) return false;
  if (!value.configured) return value.materialId === null && value.meshes.length === 0;
  if (!positiveInteger(value.materialId) || value.meshes.length !== structure.primitiveOccurrences.length) return false;
  const expected = new Set(structure.primitiveOccurrences.map(identityKey));
  const seen = new Set();
  for (const mesh of value.meshes) {
    if (!isIdentity(mesh) || !expected.has(identityKey(mesh)) || seen.has(identityKey(mesh))
      || (mesh.originalName != null && typeof mesh.originalName !== 'string') || typeof mesh.customizable !== 'boolean'
      || !mesh.defaultSelection || mesh.defaultSelection.materialId !== value.materialId
      || !positiveInteger(mesh.defaultSelection.colorId) || !Array.isArray(mesh.allowedSelections)) return false;
    seen.add(identityKey(mesh));
    if (!mesh.allowedSelections.every(selection => selection && selection.materialId === value.materialId && positiveInteger(selection.colorId))) return false;
    const allowed = mesh.allowedSelections.map(selection => selection.colorId);
    if (new Set(allowed).size !== allowed.length) return false;
    if (mesh.customizable ? !allowed.length || !allowed.includes(mesh.defaultSelection.colorId) : allowed.length !== 0) return false;
  }
  return seen.size === expected.size;
}

export function meshConfigurationColorsExist(configuration, materials) {
  if (!configuration?.configured) return true;
  if (!Array.isArray(materials)) return false;
  const material = materials.find(item => item.id === configuration.materialId);
  if (!material || !Array.isArray(material.colors)) return false;
  const ids = new Set(material.colors.map(color => color.colorId));
  return configuration.meshes.every(mesh => ids.has(mesh.defaultSelection.colorId)
    && mesh.allowedSelections.every(selection => ids.has(selection.colorId)));
}

export function buildMeshConfigurationPayload(structure, expectedRevision, draft) {
  if (!structure || !isUuid(structure.structureSnapshotId) || !positiveInteger(structure.mediaAssetId)
    || !Number.isSafeInteger(expectedRevision) || expectedRevision < 0 || !positiveInteger(draft?.materialId)
    || !Array.isArray(draft.meshes) || draft.meshes.length !== structure.primitiveOccurrences?.length) {
    throw new Error('Cấu hình phải có material, revision và đầy đủ mọi primitive.');
  }
  const expected = new Set(structure.primitiveOccurrences.map(identityKey));
  const seen = new Set();
  const meshes = draft.meshes.map(mesh => {
    if (!isIdentity(mesh) || !expected.has(identityKey(mesh)) || seen.has(identityKey(mesh))) throw new Error('Mỗi primitive Backend phải xuất hiện đúng một lần.');
    seen.add(identityKey(mesh));
    if (!Number.isInteger(mesh.defaultColorId) || mesh.defaultColorId <= 0 || typeof mesh.customizable !== 'boolean'
      || !Array.isArray(mesh.allowedColorIds) || !mesh.allowedColorIds.every(positiveInteger)
      || new Set(mesh.allowedColorIds).size !== mesh.allowedColorIds.length) throw new Error('Màu và quyền tùy chỉnh chưa hợp lệ.');
    if (!mesh.customizable && mesh.allowedColorIds.length) throw new Error('Primitive non-customizable phải có allowedColorIds rỗng.');
    if (mesh.customizable && (!mesh.allowedColorIds.length || !mesh.allowedColorIds.includes(mesh.defaultColorId))) throw new Error('Danh sách màu phải bao gồm default color.');
    return { nodeIndex: mesh.nodeIndex, meshIndex: mesh.meshIndex, primitiveIndex: mesh.primitiveIndex,
      customizable: mesh.customizable, defaultColorId: mesh.defaultColorId, allowedColorIds: [...mesh.allowedColorIds] };
  });
  if (seen.size !== expected.size) throw new Error('Payload phải bao gồm every primitive đúng một lần.');
  return { mediaAssetId: structure.mediaAssetId, structureSnapshotId: structure.structureSnapshotId,
    expectedRevision, materialId: draft.materialId, meshes };
}

export function mapBackendOccurrencesToLocalMeshes(backendOccurrences, localOccurrences) {
  if (!Array.isArray(backendOccurrences) || !Array.isArray(localOccurrences)
    || backendOccurrences.length !== localOccurrences.length) return { mapped: false, byIdentity: new Map() };
  const localByIdentity = new Map();
  for (const local of localOccurrences) {
    if (!isIdentity(local) || !local.object || localByIdentity.has(identityKey(local))) return { mapped: false, byIdentity: new Map() };
    localByIdentity.set(identityKey(local), local.object);
  }
  const byIdentity = new Map();
  for (const occurrence of backendOccurrences) {
    if (!isIdentity(occurrence) || byIdentity.has(identityKey(occurrence)) || !localByIdentity.has(identityKey(occurrence))) return { mapped: false, byIdentity: new Map() };
    byIdentity.set(identityKey(occurrence), localByIdentity.get(identityKey(occurrence)));
  }
  return { mapped: byIdentity.size === backendOccurrences.length, byIdentity };
}

// filePath3d is compared only as opaque Backend provenance; it is never used to fetch media.
export function isLocalPreviewProvenanceCurrent(localPreview, backendProduct, structure) {
  if (!localPreview || localPreview.uploadConfirmed !== true || !localPreview.sourceFile
    || !Number.isInteger(localPreview.productId) || !backendProduct || !structure
    || backendProduct.id !== localPreview.productId || structure.productId !== localPreview.productId
    || typeof localPreview.filePath3d !== 'string' || !localPreview.filePath3d.trim()
    || backendProduct.filePath3d !== localPreview.filePath3d
    || localPreview.mediaAssetId !== structure.mediaAssetId
    || localPreview.structureSnapshotId !== structure.structureSnapshotId
    || !positiveInteger(structure.mediaAssetId) || !isUuid(structure.structureSnapshotId)) return false;
  return true;
}

export function isValidHexColor(value) {
  return typeof value === 'string' && HEX_COLOR.test(value);
}

export function validateGlbFile(file) {
  if (!file) return 'Chọn một tệp GLB.';
  if (typeof file.name !== 'string' || !/\.glb$/i.test(file.name)) return 'Tệp mô hình phải có phần mở rộng .glb.';
  if (file.type === '') return 'Trình duyệt chưa xác định được loại tệp. Chọn lại tệp GLB được nhận diện là model/gltf-binary hoặc application/octet-stream.';
  if (!GLB_MIME_TYPES.has(file.type)) return 'Loại MIME của tệp GLB phải là model/gltf-binary hoặc application/octet-stream.';
  if (!Number.isFinite(file.size) || file.size <= 0) return 'Tệp GLB không được rỗng.';
  if (file.size > MAX_GLB_BYTES) return 'Tệp GLB không được vượt quá 50 MiB.';
  return null;
}

const TEXTURE_MATERIAL_SLOTS = [
  'map', 'normalMap', 'roughnessMap', 'metalnessMap', 'emissiveMap', 'alphaMap',
  'aoMap', 'bumpMap', 'displacementMap', 'specularMap', 'specularColorMap', 'specularIntensityMap',
  'envMap', 'lightMap', 'matcap', 'gradientMap', 'clearcoatMap', 'clearcoatRoughnessMap',
  'clearcoatNormalMap', 'transmissionMap', 'thicknessMap', 'sheenColorMap', 'sheenRoughnessMap',
  'iridescenceMap', 'iridescenceThicknessMap', 'anisotropyMap',
];

function getMaterials(object) {
  const materials = Array.isArray(object.material) ? object.material : [object.material];
  return materials.filter(Boolean);
}

function getMaterialName(material) {
  return String(material.name || material.type || 'Không tên');
}

function hasTexture(material) {
  return TEXTURE_MATERIAL_SLOTS.some(slot => material[slot]?.isTexture || material[slot]);
}

function hasUnsafeColorMaterial(material) {
  const advancedPhysicalResponse = material.isMeshPhysicalMaterial && [
    material.clearcoat, material.transmission, material.sheen, material.iridescence,
    material.anisotropy, material.thickness, material.dispersion,
  ].some(value => Number(value) > 0);
  return !material.color || material.vertexColors === true || material.transparent === true
    || material.isShaderMaterial === true || material.isRawShaderMaterial === true
    || advancedPhysicalResponse;
}

export function inspectGlbScene(scene, associations = new Map()) {
  const groups = new Map();
  const visit = (object, path) => {
    if (object?.isMesh && object.geometry) {
      const association = associations?.get?.(object);
      let instanceObject = Number.isInteger(association?.nodes) && Number.isInteger(association?.meshes) ? object : null;
      if (!instanceObject && Number.isInteger(association?.meshes)) {
        for (let ancestor = object.parent; ancestor; ancestor = ancestor.parent) {
          const ancestorAssociation = associations?.get?.(ancestor);
          if (ancestorAssociation?.meshes === association.meshes && Number.isInteger(ancestorAssociation.nodes)) {
            instanceObject = ancestor;
            break;
          }
        }
      }
      // Object identity isolates cloned node instances even when GLTFLoader's copied
      // association mapping exposes the same numeric node index on both clones.
      const groupKey = instanceObject || object;
      if (!groups.has(groupKey)) {
        groups.set(groupKey, {
          id: `inspect-path:${path.join('.') || 'root'}`,
          name: String(object.name || '').trim() || '(không tên)',
          primitives: [],
        });
      }
      groups.get(groupKey).primitives.push(object);
    }
    const children = Array.isArray(object?.children) ? object.children : [];
    children.forEach((child, index) => visit(child, [...path, index]));
  };
  if (scene) visit(scene, []);

  const meshes = [...groups.values()].map(group => {
    const materials = group.primitives.flatMap(getMaterials);
    const uniqueMaterials = [...new Set(materials)];
    const materialNames = [...new Set(uniqueMaterials.map(getMaterialName))];
    const textured = uniqueMaterials.some(hasTexture);
    const unsafeMaterial = uniqueMaterials.some(hasUnsafeColorMaterial);
    return {
      id: group.id,
      name: group.name,
      primitiveCount: group.primitives.length,
      materialNames,
      materialCount: uniqueMaterials.length,
      hasTexture: textured,
      unsafeColorPreview: textured || unsafeMaterial,
      objects: group.primitives,
    };
  });

  const warnings = [];
  const addWarning = (code, message, meshId) => warnings.push({ code, message, ...(meshId ? { meshId } : {}) });
  if (meshes.length === 0) addWarning('no-geometry', 'GLB không chứa mesh có hình học thực tế.');
  if (meshes.length === 1) addWarning('single-mesh', 'GLB chỉ có một mesh hình học; không thể cấu hình nhiều phần riêng.');
  const names = new Map();
  meshes.forEach(mesh => names.set(mesh.name, [...(names.get(mesh.name) || []), mesh]));
  names.forEach(group => {
    if (group.length > 1) group.forEach(mesh => addWarning('duplicate-name', `Tên “${mesh.name}” bị trùng; định danh phiên inspect dùng đường dẫn scene.`, mesh.id));
  });
  meshes.forEach(mesh => {
    if (mesh.primitiveCount > 1) addWarning('multiple-primitives', `Mesh “${mesh.name}” gồm ${mesh.primitiveCount} primitive; chúng được giữ nguyên như trong GLB.`, mesh.id);
    if (mesh.materialCount > 1) addWarning('multiple-materials', `Mesh “${mesh.name}” dùng ${mesh.materialCount} material.`, mesh.id);
    if (mesh.hasTexture) addWarning('texture-preview-unsafe', `Mesh “${mesh.name}” có texture; không thể giả định màu phẳng an toàn.`, mesh.id);
    else if (mesh.unsafeColorPreview) addWarning('material-preview-unsafe', `Material của “${mesh.name}” không phù hợp để dự đoán màu phẳng.`, mesh.id);
  });
  addWarning('unmapped-production-config', 'Chưa có contract ánh xạ mesh với cấu hình sản xuất; ID này chỉ dùng trong phiên inspect.');

  return { meshes, warnings };
}

function readGlbJson(buffer) {
  const bytes = ArrayBuffer.isView(buffer)
    ? new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
    : buffer instanceof ArrayBuffer ? new Uint8Array(buffer) : null;
  if (!bytes || bytes.byteLength < 20) throw new Error('GLB không đúng định dạng hợp lệ phiên bản 2.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint32(0, true) !== 0x46546c67 || view.getUint32(4, true) !== 2 || view.getUint32(8, true) !== bytes.byteLength) {
    throw new Error('GLB không đúng định dạng hợp lệ phiên bản 2.');
  }

  let offset = 12;
  while (offset + 8 <= bytes.byteLength) {
    const chunkLength = view.getUint32(offset, true);
    const chunkType = view.getUint32(offset + 4, true);
    const chunkStart = offset + 8;
    const chunkEnd = chunkStart + chunkLength;
    if (chunkEnd > bytes.byteLength) throw new Error('GLB không đúng định dạng hợp lệ phiên bản 2.');
    if (chunkType === 0x4e4f534a) {
      try {
        const jsonText = new TextDecoder().decode(bytes.subarray(chunkStart, chunkEnd)).replace(/\u0000+$/g, '').trim();
        return JSON.parse(jsonText);
      } catch {
        throw new Error('Không đọc được cấu trúc JSON trong GLB.');
      }
    }
    offset = chunkEnd;
  }
  throw new Error('GLB không chứa JSON chunk hợp lệ.');
}

export function validateOfflineGlb(buffer) {
  let json;
  try {
    json = readGlbJson(buffer);
  } catch (error) {
    return error.message || 'GLB không đúng định dạng hợp lệ.';
  }
  const resources = [...(Array.isArray(json.buffers) ? json.buffers : []), ...(Array.isArray(json.images) ? json.images : [])];
  const hasExternalUri = resources.some(resource => typeof resource?.uri === 'string' && !/^data:/i.test(resource.uri));
  if (hasExternalUri) return 'GLB tham chiếu tài nguyên bên ngoài. Hãy chọn một tệp tự chứa đầy đủ dữ liệu để kiểm tra offline.';
  return null;
}

export function buildMetadataPayload(values = {}) {
  return {
    name: values.name,
    description: values.description || '',
    category: values.category || '',
  };
}

export function classifyAdminApiError(error) {
  const status = error?.response?.status;
  if (status === 400) return { kind: 'validation', message: 'Dữ liệu gửi lên chưa hợp lệ. Hãy kiểm tra các trường và tệp đã chọn.' };
  if (status === 401) return { kind: 'auth', message: 'Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.' };
  if (status === 403) return { kind: 'forbidden', message: 'Tài khoản hiện tại không có quyền thực hiện thao tác này.' };
  if (status === 404) return { kind: 'not-found', message: 'Không tìm thấy dữ liệu yêu cầu. Hãy tải lại danh sách.' };
  if (status === 409) return { kind: 'conflict', message: 'Dữ liệu hoặc trạng thái đã thay đổi. Hãy tải lại trước khi tiếp tục.' };
  if (status === 413) return { kind: 'too-large', message: 'Tệp vượt giới hạn dung lượng cho phép.' };
  if (status === 415) return { kind: 'unsupported-media', message: 'Định dạng tệp không được hỗ trợ.' };
  if ([502, 503].includes(status)) return { kind: 'unavailable', message: 'Dịch vụ lưu trữ hiện chưa khả dụng. Hãy thử lại sau.' };
  if (!error?.response) return { kind: 'unknown-outcome', message: 'Không nhận được xác nhận đáng tin cậy từ máy chủ.' };
  return { kind: 'unknown', message: 'Không thể hoàn tất yêu cầu. Vui lòng thử lại.' };
}

export function getAdminMutationErrorMessage(error, operation) {
  const classified = classifyAdminApiError(error);
  const responseCannotVerifyMutation = Number.isInteger(error?.response?.status) && error.response.status >= 500;
  if (classified.kind !== 'unknown-outcome' && !responseCannotVerifyMutation) return classified.message;
  if (operation === 'create') return 'Chưa xác định bản nháp đã được tạo hay chưa vì không nhận được xác nhận đáng tin cậy từ máy chủ. Hãy kiểm tra danh sách sản phẩm trước khi gửi lại để tránh tạo trùng.';
  if (operation === 'edit') return 'Chưa xác định thay đổi đã được lưu hay chưa. Hãy tải lại chi tiết sản phẩm để kiểm tra trước khi gửi lại.';
  return classified.message;
}
