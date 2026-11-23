import assert from 'node:assert/strict';
import test from 'node:test';
import { createAdminProductMediaApi, createAdminProductMeshApi, createAdminProductPrintAssetApi } from './adminProductApi.js';
import { isPublishedAdminProductMedia } from './adminProductContract.js';

const structure = {
  productId: 17, mediaAssetId: 23, structureSnapshotId: '123e4567-e89b-42d3-a456-426614174000', parserVersion: 'v1', defaultSceneIndex: 0,
  scenes: [{ sceneIndex: 0, name: 'Scene', rootNodeIndices: [0] }],
  nodes: [{ nodeIndex: 0, nodeName: 'Body', meshIndex: 4, childNodeIndices: [], sceneIndices: [0] }],
  meshes: [{ meshIndex: 4, meshName: 'Body', primitives: [{ primitiveIndex: 0, materialIndex: null, attributeNames: ['POSITION'], textureIndices: [] }] }],
  primitiveOccurrences: [{ nodeIndex: 0, meshIndex: 4, primitiveIndex: 0, originalName: 'Body', materialIndex: null }],
  materials: [], textures: [], images: [],
};
const configuration = { productId: 17, mediaAssetId: 23, structureSnapshotId: structure.structureSnapshotId, revision: 0, configured: false, materialId: null, meshes: [] };

test('published product media adapter loads only active published detail with a canonical safe GLB URL', async () => {
  const calls = [];
  const product = { id: 17, name: 'Chair', model3dUrl: 'https://res.cloudinary.com/demo/raw/upload/v42/product-media/models/123e4567-e89b-42d3-a456-426614174000/23.glb' };
  const api = createAdminProductMediaApi({ get: async (...args) => { calls.push(args); return product; } });
  const controller = new AbortController();
  assert.deepEqual(await api.getPublishedAdminProductMedia(17, { signal: controller.signal }), { productId: 17, model3dUrl: product.model3dUrl });
  assert.deepEqual(calls[0], ['/products/17', { signal: controller.signal }]);
  for (const invalid of [
    { ...product, status: 'PENDING' }, { ...product, status: 'INACTIVE' }, { ...product, id: 18 },
    { ...product, model3dUrl: 'http://res.cloudinary.com/demo/raw/upload/v42/product-media/models/123e4567-e89b-42d3-a456-426614174000/23.glb' },
    { ...product, model3dUrl: 'https://res.cloudinary.com/demo/raw/upload/v42/product-media/models/123e4567-e89b-42d3-a456-426614174000/23.glb?token=secret' },
    { ...product, model3dUrl: 'blob:https://localhost/model.glb' },
    { ...product, model3dUrl: 'https://cdn.example.test/model.glb' },
    { ...product, model3dUrl: null },
  ]) assert.equal(isPublishedAdminProductMedia(invalid, 17), false);
});

test('mesh API uses injected shared client, exact paths, signals, and contract validation', async () => {
  const calls = [];
  const client = {
    get: async (...args) => { calls.push(['get', ...args]); return args[0].endsWith('mesh-structure') ? structure : configuration; },
    put: async (...args) => { calls.push(['put', ...args]); return { ...configuration, revision: 1 }; },
  };
  const api = createAdminProductMeshApi(client);
  const controller = new AbortController();
  const receivedStructure = await api.getStructure(17, { signal: controller.signal });
  assert.equal(receivedStructure, structure);
  assert.equal(await api.getConfiguration(17, structure, { signal: controller.signal }), configuration);
  const body = { mediaAssetId: 23, structureSnapshotId: structure.structureSnapshotId, expectedRevision: 0, materialId: 5, meshes: [] };
  assert.equal((await api.putConfiguration(17, body, structure, { signal: controller.signal })).revision, 1);
  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ['get', '/admin/products/17/mesh-structure'],
    ['get', '/admin/products/17/mesh-configurations'],
    ['put', '/admin/products/17/mesh-configurations'],
  ]);
  assert.equal(calls[0][2].signal, controller.signal);
  assert.equal(calls[2][2], body);
  assert.equal(calls[2][3].signal, controller.signal);
});

test('mesh API rejects malformed GET and PUT responses instead of presenting them as saved', async () => {
  const api = createAdminProductMeshApi({
    get: async () => ({ ...configuration, productId: 99 }),
    put: async () => ({ ...configuration, revision: -1 }),
  });
  await assert.rejects(api.getConfiguration(17, structure), /hợp đồng/i);
  await assert.rejects(api.putConfiguration(17, {}, structure), /hợp đồng/i);
});

test('print asset API posts multipart file and returns metadata without a storage URL', async () => {
  const calls = [];
  const response = { mediaAssetId: 8, fileName: 'part.3mf', byteCount: 128, status: 'ATTACHED', uploadedAt: '2026-11-23T08:00:00Z' };
  const api = createAdminProductPrintAssetApi({
    post: async (...args) => { calls.push(['post', ...args]); return response; },
    get: async (...args) => { calls.push(['get', ...args]); return [response]; },
  });
  const controller = new AbortController();
  const file = new File(['3mf'], 'part.3mf');
  assert.deepEqual(await api.upload(17, file), response);
  assert.equal(calls[0][1], '/admin/products/17/print-assets');
  assert.equal(calls[0][2].get('file').name, file.name);
  assert.equal(await calls[0][2].get('file').text(), '3mf');
  assert.equal(calls[0][3].headers['Content-Type'], undefined);
  assert.deepEqual(await api.list(17, { signal: controller.signal }), [response]);
  assert.deepEqual(calls[1], ['get', '/admin/products/17/print-assets', { signal: controller.signal }]);
});

test('print asset API rejects unsafe metadata and malformed upload responses', async () => {
  const api = createAdminProductPrintAssetApi({
    post: async () => ({ mediaAssetId: 8, fileName: 'part.3mf', byteCount: 128, status: 'ATTACHED', uploadedAt: '2026-11-23T08:00:00Z', downloadUrl: 'https://secret' }),
    get: async () => [{ mediaAssetId: 8, fileName: 'part.3mf', byteCount: 128, status: 'ATTACHED', uploadedAt: '2026-11-23T08:00:00Z', storagePath: 'secret' }],
  });
  await assert.rejects(api.upload(17, new File(['x'], 'part.3mf')), /hợp đồng/i);
  await assert.rejects(api.list(17), /hợp đồng/i);
});
