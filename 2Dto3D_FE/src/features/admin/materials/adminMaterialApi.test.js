import assert from 'node:assert/strict';
import test from 'node:test';
import { createAdminMaterialApi } from './adminMaterialApi.js';

test('Admin catalog API lists colors/materials and creates colors/materials with the shared endpoints', async () => {
  const calls = [];
  const color = { colorId: 3, name: 'Blue', hexCode: '#123456' };
  const material = { id: 7, name: 'PLA', type: 'PLA', isAvailable: 'AVAILABLE', colors: [] };
  const client = {
    get: async (...args) => { calls.push(['get', ...args]); return args[0] === '/colors/' ? [color] : [material]; },
    post: async (...args) => { calls.push(['post', ...args]); return args[0] === '/colors/create' ? undefined : material; },
    put: async (...args) => { calls.push(['put', ...args]); return undefined; },
  };
  const api = createAdminMaterialApi(client);
  const controller = new AbortController();

  assert.deepEqual(await api.listAdminColors({ signal: controller.signal }), [color]);
  assert.deepEqual(await api.listAdminMaterials({ signal: controller.signal }), [material]);
  await api.createAdminColor({ name: 'Blue', hexCode: '#123456' });
  await api.createAdminMaterial({ name: 'PLA', type: 'PLA', pricePerGram: 0.05, stockQuantity: 10, unit: 'g', colors: [] });
  await api.updateAdminMaterial(7, { name: 'PLA+', type: 'PLA', pricePerGram: 0.06, stockQuantity: 10, unit: 'g', colors: [] });

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ['get', '/colors/'], ['get', '/materials/'], ['post', '/colors/create'],
    ['post', '/materials/create'], ['put', '/materials/7'],
  ]);
  assert.equal(calls[0][2].signal, controller.signal);
  assert.deepEqual(calls[2][2], { name: 'Blue', hexCode: '#123456' });
  assert.deepEqual(calls[4][2].colors, []);
});

test('Admin catalog API rejects malformed response contracts and preserves server duplicate/validation errors', async () => {
  const malformed = createAdminMaterialApi({ get: async () => [{ name: 'bad' }] });
  await assert.rejects(malformed.listAdminColors(), /hợp đồng/i);

  const conflict = Object.assign(new Error('duplicate'), { response: { status: 409 } });
  const api = createAdminMaterialApi({ post: async () => { throw conflict; } });
  await assert.rejects(api.createAdminColor({ name: 'duplicate' }), error => error === conflict);
});
