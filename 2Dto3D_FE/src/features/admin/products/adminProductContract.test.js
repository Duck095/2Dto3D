import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { ENDPOINTS } from '../../../services/api/endpoints.js';
import { isAdminProductPage, isAdminProduct, isMaterialList, buildMetadataPayload, classifyAdminApiError, getAdminMutationErrorMessage, validateGlbFile, inspectGlbScene, validateOfflineGlb, isMeshStructureResponse, isMeshConfigurationResponse, buildMeshConfigurationPayload, mapBackendOccurrencesToLocalMeshes, meshConfigurationColorsExist, isLocalPreviewProvenanceCurrent } from './adminProductContract.js';

function createGlbBuffer(json) {
  const jsonBytes = Buffer.from(JSON.stringify(json));
  const paddedLength = Math.ceil(jsonBytes.length / 4) * 4;
  const bytes = Buffer.alloc(20 + paddedLength, 0x20);
  bytes.writeUInt32LE(0x46546c67, 0);
  bytes.writeUInt32LE(2, 4);
  bytes.writeUInt32LE(bytes.length, 8);
  bytes.writeUInt32LE(paddedLength, 12);
  bytes.writeUInt32LE(0x4e4f534a, 16);
  jsonBytes.copy(bytes, 20);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

function createInstancedMultiPrimitiveGlb() {
  const binary = Buffer.alloc(44);
  const positions = [-0.5, -0.5, 0, 0.5, -0.5, 0, 0, 0.5, 0];
  positions.forEach((value, index) => binary.writeFloatLE(value, index * 4));
  binary.writeUInt16LE(0, 36);
  binary.writeUInt16LE(1, 38);
  binary.writeUInt16LE(2, 40);
  const json = {
    asset: { version: '2.0' },
    buffers: [{ byteLength: 42 }],
    bufferViews: [{ buffer: 0, byteOffset: 0, byteLength: 36 }, { buffer: 0, byteOffset: 36, byteLength: 6 }],
    accessors: [
      { bufferView: 0, componentType: 5126, count: 3, type: 'VEC3', min: [-0.5, -0.5, 0], max: [0.5, 0.5, 0] },
      { bufferView: 1, componentType: 5123, count: 3, type: 'SCALAR', min: [0], max: [2] },
    ],
    materials: [
      { name: 'Primitive red', pbrMetallicRoughness: { baseColorFactor: [1, 0, 0, 1] } },
      { name: 'Primitive blue', pbrMetallicRoughness: { baseColorFactor: [0, 0, 1, 1] } },
    ],
    meshes: [{ name: 'Duplicate source', primitives: [
      { attributes: { POSITION: 0 }, indices: 1, material: 0 },
      { attributes: { POSITION: 0 }, indices: 1, material: 1 },
    ] }],
    nodes: [
      { name: 'Duplicate instance', mesh: 0, translation: [-0.75, 0, 0] },
      { name: 'Duplicate instance', mesh: 0, translation: [0.75, 0, 0] },
      { name: 'Geometry-free locator', translation: [0, 0, 0] },
    ],
    scenes: [{ nodes: [0, 1, 2] }],
    scene: 0,
  };
  const jsonBytes = Buffer.from(JSON.stringify(json));
  const jsonLength = Math.ceil(jsonBytes.length / 4) * 4;
  const totalLength = 12 + 8 + jsonLength + 8 + binary.length;
  const glb = Buffer.alloc(totalLength, 0);
  glb.writeUInt32LE(0x46546c67, 0);
  glb.writeUInt32LE(2, 4);
  glb.writeUInt32LE(totalLength, 8);
  glb.writeUInt32LE(jsonLength, 12);
  glb.writeUInt32LE(0x4e4f534a, 16);
  glb.fill(0x20, 20, 20 + jsonLength);
  jsonBytes.copy(glb, 20);
  const binHeader = 20 + jsonLength;
  glb.writeUInt32LE(binary.length, binHeader);
  glb.writeUInt32LE(0x004e4942, binHeader + 4);
  binary.copy(glb, binHeader + 8);
  return glb.buffer.slice(glb.byteOffset, glb.byteOffset + glb.byteLength);
}

test('admin catalog paths match the verified Backend contract', () => {
  assert.equal(ENDPOINTS.ADMIN_PRODUCTS.LIST, '/admin/products');
  assert.equal(ENDPOINTS.ADMIN_PRODUCTS.DETAIL('a/b'), '/admin/products/a%2Fb');
  assert.equal(ENDPOINTS.ADMIN_PRODUCTS.PUBLISH(17), '/admin/products/17/publish');
  assert.equal(ENDPOINTS.ADMIN_PRODUCTS.ARCHIVE(17), '/admin/products/17/archive');
  assert.equal(ENDPOINTS.ADMIN_PRODUCTS.MESH_STRUCTURE(17), '/admin/products/17/mesh-structure');
  assert.equal(ENDPOINTS.ADMIN_PRODUCTS.MESH_CONFIGURATIONS(17), '/admin/products/17/mesh-configurations');
  assert.equal(ENDPOINTS.ADMIN_MATERIALS.LIST, '/materials/');
});

test('product page and model responses reject malformed or wrong source/status records', () => {
  const product = { id: 4, sourceType: 'DESIGNER_MODEL', name: 'Vase', status: 'PENDING' };
  assert.equal(isAdminProduct(product), true);
  assert.equal(isAdminProduct({ ...product, sourceType: 'AI_GENERATED' }), false);
  assert.equal(isAdminProduct({ ...product, status: 'UNKNOWN' }), false);
  assert.equal(isAdminProductPage({ items: [product], page: 0, size: 20, totalElements: 1, totalPages: 1 }), true);
  assert.equal(isAdminProductPage({ items: 'fake', page: 0, size: 20, totalElements: 1, totalPages: 1 }), false);
});

test('metadata payload is explicitly whitelisted', () => {
  assert.deepEqual(buildMetadataPayload({ name: 'A', description: 'B', category: 'C', designerId: 9, filePath3d: 'x' }), {
    name: 'A', description: 'B', category: 'C',
  });
});

test('API error classification distinguishes publish conflicts and ambiguous network outcomes', () => {
  assert.equal(classifyAdminApiError({ response: { status: 409 } }).kind, 'conflict');
  assert.equal(classifyAdminApiError({ response: { status: 413 } }).kind, 'too-large');
  assert.equal(classifyAdminApiError({ code: 'ECONNRESET' }).kind, 'unknown-outcome');
});

test('material response validation requires a materials array and valid color fields', () => {
  assert.equal(isMaterialList([{ id: 1, name: 'PLA', type: 'FILAMENT', isAvailable: 'AVAILABLE', colors: [{ colorId: 3, name: 'Blue', hexCode: '#123abc' }] }]), true);
  assert.equal(isMaterialList([{ id: 1, name: 'PLA', type: 'FILAMENT', isAvailable: 'AVAILABLE', colors: [{ colorId: 3, name: 'Blue', hexCode: 'blue' }] }]), false);
  assert.equal(isMaterialList({}), false);
});

test('nullable material type accepts only string or explicit null while retaining required presence', () => {
  const material = { id: 1, name: 'PLA', type: 'FILAMENT', isAvailable: 'AVAILABLE', colors: [] };
  assert.equal(isMaterialList([material]), true);
  assert.equal(isMaterialList([{ ...material, type: null }]), true);
  for (const type of [7, true, {}, [], undefined]) {
    const malformed = { ...material, type };
    if (type === undefined) delete malformed.type;
    assert.equal(isMaterialList([malformed]), false, `expected type ${String(type)} to be rejected`);
  }
  assert.equal(isMaterialList([{ ...material, isAvailable: null }]), false);
  assert.equal(isMaterialList([{ ...material, colors: null }]), false);
});

test('GLB file validation accepts both Backend MIME types and checks extension, size, and unknown MIME', () => {
  const makeFile = (name, type, size) => ({ name, type, size });
  assert.equal(validateGlbFile(makeFile('model.glb', 'model/gltf-binary', 4)), null);
  assert.equal(validateGlbFile(makeFile('model.glb', 'application/octet-stream', 4)), null);
  assert.match(validateGlbFile(makeFile('model.glb', '', 4)), /trình duyệt chưa xác định/i);
  for (const mime of ['text/plain', 'application/json', 'image/png']) {
    assert.match(validateGlbFile(makeFile('model.glb', mime, 4)), /MIME/i);
  }
  assert.match(validateGlbFile(makeFile('model.txt', 'model/gltf-binary', 4)), /\.glb/i);
  assert.match(validateGlbFile(makeFile('model.glb', 'model/gltf-binary', 0)), /rỗng/i);
  assert.match(validateGlbFile(makeFile('model.glb', 'model/gltf-binary', 50 * 1024 * 1024 + 1)), /50 MiB/i);
  assert.equal(validateGlbFile(makeFile('MODEL.GLB', 'application/octet-stream', 4)), null);
});

test('unknown create and edit outcomes use action-specific copy without implying reconciliation', () => {
  const timeout = { code: 'ECONNABORTED' };
  const network = { code: 'ERR_NETWORK' };
  const malformed = new Error('Backend returned an invalid ModelResponse');
  const createCopy = 'Chưa xác định bản nháp đã được tạo hay chưa vì không nhận được xác nhận đáng tin cậy từ máy chủ. Hãy kiểm tra danh sách sản phẩm trước khi gửi lại để tránh tạo trùng.';
  const editCopy = 'Chưa xác định thay đổi đã được lưu hay chưa. Hãy tải lại chi tiết sản phẩm để kiểm tra trước khi gửi lại.';
  for (const error of [timeout, network, malformed, { response: { status: 500 } }, { response: { status: 503 } }]) {
    assert.equal(getAdminMutationErrorMessage(error, 'create'), createCopy);
    assert.equal(getAdminMutationErrorMessage(error, 'edit'), editCopy);
  }
  assert.doesNotMatch(classifyAdminApiError(timeout).message, /đang kiểm tra lại/i);
  assert.match(getAdminMutationErrorMessage({ response: { status: 400 } }, 'create'), /chưa hợp lệ/i);
});

test('GLB inspection ignores geometry-free nodes and identifies geometry by scene path, not mesh name', () => {
  const scene = {
    children: [
      { name: 'location-only', children: [] },
      { name: 'duplicate', isMesh: true, geometry: {}, material: { name: 'paint', color: {} }, children: [] },
      { name: 'duplicate', isMesh: true, geometry: {}, material: { name: 'paint', color: {} }, children: [] },
    ],
  };
  const result = inspectGlbScene(scene);
  assert.equal(result.meshes.length, 2);
  assert.notEqual(result.meshes[0].id, result.meshes[1].id);
  assert.match(result.meshes[0].id, /path/);
  assert.deepEqual(result.warnings.map(warning => warning.code).sort(), ['duplicate-name', 'duplicate-name', 'unmapped-production-config'].sort());
});

test('GLB inspection warns for one mesh, multiple primitives/materials, textures, and unmapped production identity', () => {
  const texture = {};
  const meshA = { name: 'shell', isMesh: true, geometry: {}, material: [{ name: 'paint' }, { name: 'print', map: texture }], children: [] };
  const meshB = { name: 'shell', isMesh: true, geometry: {}, material: { name: 'paint', map: texture }, children: [] };
  const instance = { name: 'gltf-node-instance', children: [meshA, meshB] };
  meshA.parent = instance;
  meshB.parent = instance;
  const scene = { children: [instance] };
  const associations = new Map([[meshA, { meshes: 2, primitives: 0 }], [meshB, { meshes: 2, primitives: 1 }], [instance, { meshes: 2, nodes: 5 }]]);
  const result = inspectGlbScene(scene, associations);
  assert.equal(result.meshes.length, 1);
  assert.equal(result.meshes[0].primitiveCount, 2);
  assert.equal(result.meshes[0].materialNames.length, 2);
  assert.equal(result.meshes[0].hasTexture, true);
  assert.deepEqual(result.warnings.map(warning => warning.code).sort(), [
    'multiple-materials', 'multiple-primitives', 'single-mesh', 'texture-preview-unsafe', 'unmapped-production-config',
  ].sort());
});

test('GLB inspection reads material metadata without changing source material color or texture', () => {
  const color = { value: '#11aa33' };
  const texture = { isTexture: true };
  const material = { name: 'original', color, map: texture };
  const mesh = { name: 'part', isMesh: true, geometry: {}, material, children: [] };
  const result = inspectGlbScene({ children: [mesh] });
  assert.equal(result.meshes[0].hasTexture, true);
  assert.equal(material.color, color);
  assert.equal(material.map, texture);
});

test('GLB mesh instances stay separate even when they reuse the same source mesh', () => {
  const first = { name: 'instance', isMesh: true, geometry: {}, material: { name: 'paint', color: {} }, children: [] };
  const second = { name: 'instance', isMesh: true, geometry: {}, material: first.material, children: [] };
  const scene = { children: [first, second] };
  const associations = new Map([[first, { nodes: 0, meshes: 2 }], [second, { nodes: 1, meshes: 2 }]]);
  const result = inspectGlbScene(scene, associations);
  assert.equal(result.meshes.length, 2);
  assert.deepEqual(result.meshes.map(mesh => mesh.primitiveCount), [1, 1]);
  assert.notEqual(result.meshes[0].id, result.meshes[1].id);
});

test('GLB associations without node identity keep same-source primitives separate', () => {
  const first = { name: 'duplicate', isMesh: true, geometry: {}, material: { name: 'paint', color: {} }, children: [] };
  const second = { name: 'duplicate', isMesh: true, geometry: {}, material: first.material, children: [] };
  const associations = new Map([[first, { meshes: 2, primitives: 0 }], [second, { meshes: 2, primitives: 1 }]]);
  const result = inspectGlbScene({ children: [first, second] }, associations);
  assert.equal(result.meshes.length, 2);
  assert.deepEqual(result.meshes.map(mesh => mesh.primitiveCount), [1, 1]);
  assert.equal(result.warnings.filter(warning => warning.code === 'duplicate-name').length, 2);
});

test('real GLTFLoader groups mixed primitives by each source mesh instance through its ancestor association', async () => {
  const fixture = createInstancedMultiPrimitiveGlb();
  const gltf = await new Promise((resolve, reject) => new GLTFLoader().parse(fixture, '', resolve, reject));
  const primitiveObjects = [];
  gltf.scene.traverse(object => { if (object.isMesh && object.geometry) primitiveObjects.push(object); });
  assert.equal(primitiveObjects.length, 4, 'two primitives should load for each of the two node instances');
  const locator = gltf.scene.children.find(object => object.name.toLowerCase().includes('locator'));
  assert.ok(locator, JSON.stringify(gltf.scene.children.map(object => ({ name: object.name, isMesh: object.isMesh, children: object.children.length }))));
  assert.equal(locator.isMesh, undefined);
  const realAssociations = primitiveObjects.map(object => gltf.parser.associations.get(object));
  const instanceNodes = primitiveObjects.map(object => object.parent);
  const instanceNodeAssociations = instanceNodes.map(node => gltf.parser.associations.get(node));
  const originalMaterials = primitiveObjects.map(object => object.material);
  const originalMaterialColors = originalMaterials.map(material => material.color.getHex());
  const associationPaths = primitiveObjects.map(object => {
    const path = [];
    for (let current = object; current; current = current.parent) {
      path.push({ name: current.name, association: gltf.parser.associations.get(current) || null });
    }
    return path;
  });
  const inspected = inspectGlbScene(gltf.scene, gltf.parser.associations);
  assert.equal(inspected.meshes.length, 2, JSON.stringify({ realAssociations, associationPaths }));
  assert.deepEqual(realAssociations.map(association => association.primitives), [0, 1, 0, 1]);
  assert.ok(realAssociations.every(association => association.nodes == null));
  assert.notEqual(instanceNodes[0], instanceNodes[2]);
  assert.equal(instanceNodeAssociations[0].nodes, instanceNodeAssociations[1].nodes, 'GLTFLoader cloned instances share the copied numeric nodes mapping in this version');
  assert.ok(instanceNodeAssociations.every(association => association.meshes === 0));
  assert.deepEqual(inspected.meshes.map(mesh => mesh.primitiveCount), [2, 2]);
  assert.deepEqual(inspected.meshes.map(mesh => mesh.materialCount), [2, 2]);
  assert.equal(inspected.warnings.filter(warning => warning.code === 'multiple-primitives').length, 2);
  assert.equal(inspected.warnings.filter(warning => warning.code === 'duplicate-name').length, 2);
  assert.equal(inspected.meshes[0].id === inspected.meshes[1].id, false);

  inspected.meshes.forEach(mesh => assert.equal(mesh.objects.length, 2));
  assert.deepEqual(primitiveObjects.map(object => object.material), originalMaterials);
  assert.deepEqual(originalMaterials.map(material => material.color.getHex()), originalMaterialColors);
  assert.ok(primitiveObjects.every(object => gltf.parser.associations.get(object)?.nodes == null), 'GLTFLoader primitive children should lack nodes associations');
});

test('offline GLB validation accepts embedded data URIs but rejects relative and network dependencies', () => {
  const base = { asset: { version: '2.0' } };
  assert.equal(validateOfflineGlb(createGlbBuffer({ ...base, buffers: [{ byteLength: 4, uri: 'data:application/octet-stream;base64,AAAAAA==' }] })), null);
  for (const uri of ['./geometry.bin', '../image.png', 'https://example.invalid/geometry.bin', 'http://example.invalid/image.png']) {
    for (const resource of ['buffers', 'images']) {
      const error = validateOfflineGlb(createGlbBuffer({ ...base, [resource]: [{ byteLength: 4, uri }] }));
      assert.match(error, /tài nguyên bên ngoài/i, `${resource}: ${uri}`);
    }
  }
  assert.match(validateOfflineGlb(new ArrayBuffer(8)), /GLB không đúng định dạng/i);
});

test('material safety flags special physical response and advanced texture slots without mutation', () => {
  const special = { name: 'clearcoat', color: {}, isMeshPhysicalMaterial: true, clearcoat: 1, transmission: 0.4, sheen: 0.5 };
  const specialResult = inspectGlbScene({ children: [{ isMesh: true, geometry: {}, material: special }] });
  assert.equal(specialResult.meshes[0].unsafeColorPreview, true);
  assert.ok(specialResult.warnings.some(warning => warning.code === 'material-preview-unsafe'));

  const textureSlots = ['clearcoatMap', 'clearcoatRoughnessMap', 'clearcoatNormalMap', 'transmissionMap', 'thicknessMap', 'sheenColorMap', 'sheenRoughnessMap', 'specularColorMap', 'specularIntensityMap', 'iridescenceMap', 'iridescenceThicknessMap', 'anisotropyMap'];
  for (const slot of textureSlots) {
    const texture = { isTexture: true };
    const material = { name: slot, color: {}, [slot]: texture };
    const result = inspectGlbScene({ children: [{ isMesh: true, geometry: {}, material }] });
    assert.equal(result.meshes[0].hasTexture, true, `${slot} should be recognized`);
    assert.equal(material[slot], texture, `${slot} should stay intact`);
  }
});

test('admin product adapter uses the shared client and metadata-only PATCH', async () => {
  const source = await readFile(new URL('./adminProductApi.js', import.meta.url), 'utf8');
  assert.match(source, /import apiClient/);
  assert.match(source, /ENDPOINTS\.ADMIN_PRODUCTS/);
  assert.doesNotMatch(source, /axios\.create|designerId\s*:|filePath3d\s*:|imagePath\s*:/);
  assert.match(source, /apiClient\.patch\(ENDPOINTS\.ADMIN_PRODUCTS\.DETAIL\(id\),\s*buildMetadataPayload/);
  assert.match(source, /FormData/);
  assert.match(source, /headers:\s*\{\s*'Content-Type': undefined\s*\}/);
});

test('materials service is GET-only and screen makes no compatibility or mutation claim', async () => {
  const source = await readFile(new URL('./adminProductApi.js', import.meta.url), 'utf8');
  const page = await readFile(new URL('../../../pages/admin/AdminMaterialsPage.jsx', import.meta.url), 'utf8');
  assert.match(source, /apiClient\.get\(ENDPOINTS\.ADMIN_MATERIALS\.LIST/);
  assert.doesNotMatch(source, /apiClient\.(post|put|patch|delete)\(ENDPOINTS\.ADMIN_MATERIALS/);
  assert.match(page, /Danh sách vật liệu và màu toàn cục — chưa xác định tương thích cho từng sản phẩm\./);
});

const structureFixture = {
  productId: 11, mediaAssetId: 21, structureSnapshotId: '123e4567-e89b-42d3-a456-426614174000', parserVersion: 'gltf-2', defaultSceneIndex: 0,
  scenes: [{ sceneIndex: 0, name: 'Scene', rootNodeIndices: [0] }],
  nodes: [{ nodeIndex: 0, nodeName: 'Body', meshIndex: 2, childNodeIndices: [], sceneIndices: [0] }],
  meshes: [{ meshIndex: 2, meshName: 'BodyMesh', primitives: [{ primitiveIndex: 0, materialIndex: 0, attributeNames: ['POSITION'], textureIndices: [] }] }],
  primitiveOccurrences: [{ nodeIndex: 0, meshIndex: 2, primitiveIndex: 0, originalName: 'BodyMesh', materialIndex: 0 }],
  materials: [{ materialIndex: 0, name: 'Default', textureIndices: [] }], textures: [], images: [],
};

test('mesh structure validator rejects duplicate primitive occurrence identities and malformed snapshot contract', () => {
  assert.equal(isMeshStructureResponse(structureFixture, 11), true);
  assert.equal(isMeshStructureResponse({ ...structureFixture, primitiveOccurrences: [{ ...structureFixture.primitiveOccurrences[0], originalName: null }] }, 11), true);
  assert.equal(isMeshStructureResponse({ ...structureFixture, productId: 12 }, 11), false);
  assert.equal(isMeshStructureResponse({ ...structureFixture, primitiveOccurrences: [...structureFixture.primitiveOccurrences, structureFixture.primitiveOccurrences[0]] }, 11), false);
  assert.equal(isMeshStructureResponse({ ...structureFixture, structureSnapshotId: 'bad-snapshot' }, 11), false);
  assert.equal(isMeshStructureResponse({ ...structureFixture, primitiveOccurrences: [] }, 11), false);
  assert.equal(isMeshStructureResponse({ ...structureFixture, meshes: [{ ...structureFixture.meshes[0], primitives: [
    ...structureFixture.meshes[0].primitives, structureFixture.meshes[0].primitives[0],
  ] }] }, 11), false);
});

test('configuration response validates revision and exact Backend occurrence identity set', () => {
  const unconfigured = { productId: 11, mediaAssetId: 21, structureSnapshotId: structureFixture.structureSnapshotId, revision: 0, configured: false, materialId: null, meshes: [] };
  const configured = { ...unconfigured, revision: 2, configured: true, materialId: 5, meshes: [{
    nodeIndex: 0, meshIndex: 2, primitiveIndex: 0, originalName: 'BodyMesh', customizable: true,
    defaultSelection: { materialId: 5, colorId: 8 }, allowedSelections: [{ materialId: 5, colorId: 8 }, { materialId: 5, colorId: 9 }],
  }] };
  assert.equal(isMeshConfigurationResponse(unconfigured, structureFixture), true);
  assert.equal(isMeshConfigurationResponse(configured, structureFixture), true);
  assert.equal(isMeshConfigurationResponse({ ...configured, meshes: [{ ...configured.meshes[0], originalName: null }] }, structureFixture), true);
  assert.equal(isMeshConfigurationResponse({ ...unconfigured, revision: -1 }, structureFixture), false);
  assert.equal(isMeshConfigurationResponse({ ...configured, meshes: [...configured.meshes, configured.meshes[0]] }, structureFixture), false);
  assert.equal(isMeshConfigurationResponse({ ...configured, meshes: [{ ...configured.meshes[0], allowedSelections: [] }] }, structureFixture), false);
  assert.equal(isMeshConfigurationResponse({ ...configured, meshes: [{ ...configured.meshes[0], allowedSelections: [{ materialId: 7, colorId: 9 }] }] }, structureFixture), false);
});

test('PUT payload uses only Backend DTO fields and enforces default/allowed color invariant', () => {
  const draft = { materialId: 5, meshes: [{ nodeIndex: 0, meshIndex: 2, primitiveIndex: 0, customizable: true, defaultColorId: 8, allowedColorIds: [8, 9] }] };
  assert.deepEqual(buildMeshConfigurationPayload(structureFixture, 4, draft), {
    mediaAssetId: 21, structureSnapshotId: structureFixture.structureSnapshotId, expectedRevision: 4, materialId: 5,
    meshes: [{ nodeIndex: 0, meshIndex: 2, primitiveIndex: 0, customizable: true, defaultColorId: 8, allowedColorIds: [8, 9] }],
  });
  assert.throws(() => buildMeshConfigurationPayload(structureFixture, 4, { ...draft, meshes: [] }), /primitive/i);
  assert.throws(() => buildMeshConfigurationPayload(structureFixture, 4, { ...draft, meshes: [{ ...draft.meshes[0], customizable: false, allowedColorIds: [9] }] }), /non-customizable/i);
  assert.throws(() => buildMeshConfigurationPayload(structureFixture, 4, { ...draft, meshes: [{ ...draft.meshes[0], allowedColorIds: [9] }] }), /default color/i);
});

test('local GLTF viewer mapping is available only for unique exact occurrence associations', () => {
  const backend = structureFixture.primitiveOccurrences;
  const local = [{ object: {}, nodeIndex: 0, meshIndex: 2, primitiveIndex: 0 }];
  assert.equal(mapBackendOccurrencesToLocalMeshes(backend, local).mapped, true);
  assert.equal(mapBackendOccurrencesToLocalMeshes(backend, []).mapped, false);
  assert.equal(mapBackendOccurrencesToLocalMeshes(backend, [...local, local[0]]).mapped, false);
  assert.equal(mapBackendOccurrencesToLocalMeshes(backend, [{ ...local[0], nodeIndex: 1 }]).mapped, false);
});

test('saved configuration color ids must exist in the material catalog response', () => {
  const configured = { productId: 11, mediaAssetId: 21, structureSnapshotId: structureFixture.structureSnapshotId, revision: 2, configured: true, materialId: 5,
    meshes: [{ nodeIndex: 0, meshIndex: 2, primitiveIndex: 0, originalName: 'BodyMesh', customizable: true,
      defaultSelection: { materialId: 5, colorId: 8 }, allowedSelections: [{ materialId: 5, colorId: 8 }, { materialId: 5, colorId: 9 }] }] };
  const colors = [{ id: 5, name: 'PLA', type: 'FILAMENT', isAvailable: 'AVAILABLE', colors: [
    { colorId: 8, name: 'White', hexCode: '#fff' }, { colorId: 9, name: 'Blue', hexCode: '#123abc' },
  ] }];
  assert.equal(meshConfigurationColorsExist(configured, colors), true);
  assert.equal(meshConfigurationColorsExist(configured, [{ ...colors[0], colors: [colors[0].colors[0]] }]), false);
  assert.equal(isMaterialList([{ ...colors[0], colors: [...colors[0].colors, colors[0].colors[0]] }]), false);
});

test('local GLB highlight requires matching create response, current product path, media asset and structure snapshot', () => {
  const preview = { uploadConfirmed: true, productId: 11, filePath3d: 'opaque-model-id', sourceFile: {}, mediaAssetId: 21, structureSnapshotId: structureFixture.structureSnapshotId };
  const product = { id: 11, filePath3d: 'opaque-model-id' };
  const currentStructure = { productId: 11, mediaAssetId: 21, structureSnapshotId: structureFixture.structureSnapshotId };
  assert.equal(isLocalPreviewProvenanceCurrent(preview, product, currentStructure), true);
  assert.equal(isLocalPreviewProvenanceCurrent({ ...preview, uploadConfirmed: false }, product, currentStructure), false);
  assert.equal(isLocalPreviewProvenanceCurrent(preview, { ...product, filePath3d: 'different' }, currentStructure), false);
  assert.equal(isLocalPreviewProvenanceCurrent(preview, product, { ...currentStructure, mediaAssetId: 22 }), false);
  assert.equal(isLocalPreviewProvenanceCurrent(preview, product, { ...currentStructure, structureSnapshotId: '123e4567-e89b-42d3-a456-426614174001' }), false);
  assert.equal(isLocalPreviewProvenanceCurrent({ ...preview, mediaAssetId: null, structureSnapshotId: null }, product, currentStructure), false);
  assert.equal(isLocalPreviewProvenanceCurrent({ ...preview, sourceFile: null }, product, currentStructure), false);
});
