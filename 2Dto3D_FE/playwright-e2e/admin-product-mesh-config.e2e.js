import { expect, test } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

function testToken() {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ exp: Math.floor(Date.now() / 1000) + 3600 })}.test-only`;
}

const product = { id: 17, sourceType: 'DESIGNER_MODEL', name: 'Offline mesh product', status: 'PENDING', category: 'Test', description: '' };
const structure = {
  productId: 17, mediaAssetId: 23, structureSnapshotId: '123e4567-e89b-42d3-a456-426614174000', parserVersion: 'test-parser', defaultSceneIndex: 0,
  scenes: [{ sceneIndex: 0, name: 'Scene', rootNodeIndices: [0] }],
  nodes: [{ nodeIndex: 0, nodeName: 'Body node', meshIndex: 4, childNodeIndices: [], sceneIndices: [0] }],
  meshes: [{ meshIndex: 4, meshName: 'Body mesh', primitives: [{ primitiveIndex: 0, materialIndex: 0, attributeNames: ['POSITION'], textureIndices: [] }] }],
  primitiveOccurrences: [{ nodeIndex: 0, meshIndex: 4, primitiveIndex: 0, originalName: 'Body primitive', materialIndex: 0 }],
  materials: [{ materialIndex: 0, name: 'Original', textureIndices: [] }], textures: [], images: [],
};
let configuration = { productId: 17, mediaAssetId: 23, structureSnapshotId: structure.structureSnapshotId, revision: 0, configured: false, materialId: null, meshes: [] };
const materials = [{ id: 5, name: 'PLA White', type: 'FILAMENT', isAvailable: 'AVAILABLE', colors: [
  { colorId: 8, name: 'White', hexCode: '#ffffff' }, { colorId: 9, name: 'Blue', hexCode: '#2255cc' },
] }];

test('offline Admin mesh editor loads Backend contract, edits, saves exact payload, and confirms GET revision on desktop/mobile', async ({ page }) => {
  const putRequests = [];
  const authorizationHeaders = [];
  let putMode = 'success';
  configuration = { productId: 17, mediaAssetId: 23, structureSnapshotId: structure.structureSnapshotId, revision: 0, configured: false, materialId: null, meshes: [] };
  const token = testToken();
  await page.addInitScript(({ token: sessionToken }) => {
    localStorage.setItem('morphia_access_token', sessionToken);
    localStorage.setItem('morphia_user', JSON.stringify({ token: sessionToken, id: 777, name: 'TEST_ONLY admin', contexts: ['CUSTOMER', 'ADMIN'] }));
  }, { token });
  await page.route('**/api/v1/me', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ contexts: ['CUSTOMER', 'ADMIN'] }) }));
  await page.route('**/api/products**', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 4, totalElements: 0, totalPages: 0 }) }));
  await page.route('**/api/admin/products**', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [product], page: 0, size: 20, totalElements: 1, totalPages: 1 }) }));
  await page.route('**/api/admin/products/17/mesh-structure', route => {
    authorizationHeaders.push(route.request().headers().authorization || '');
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(structure) });
  });
  await page.route('**/api/admin/products/17/mesh-configurations', async route => {
    authorizationHeaders.push(route.request().headers().authorization || '');
    if (route.request().method() === 'PUT') {
      const payload = route.request().postDataJSON();
      putRequests.push(payload);
      if (putMode === 'conflict') return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 'CONFIGURATION_REVISION_CONFLICT', message: 'Revision changed.' }) });
      if (putMode === 'network') return route.abort('failed');
      configuration = { productId: 17, mediaAssetId: 23, structureSnapshotId: structure.structureSnapshotId, revision: 1,
        configured: true, materialId: payload.materialId, meshes: payload.meshes.map(mesh => ({
          ...mesh, originalName: 'Body primitive', defaultSelection: { materialId: payload.materialId, colorId: mesh.defaultColorId },
          allowedSelections: mesh.allowedColorIds.map(colorId => ({ materialId: payload.materialId, colorId })),
        })) };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(configuration) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(configuration) });
  });
  await page.route('**/api/materials/', route => {
    authorizationHeaders.push(route.request().headers().authorization || '');
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(materials) });
  });
  await page.route('**/api/admin/products/17', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(product) }));

  await page.setViewportSize({ width: 1365, height: 900 });
  await page.goto('/admin/products');
  await page.getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.getByRole('heading', { name: 'Cấu hình bộ phận & màu' })).toBeVisible();
  await expect(page.getByText('Body node · Mesh: Body mesh · Primitive: 0')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Chọn occurrence Backend' })).toHaveAttribute('aria-pressed', 'true');
  const materialSelect = page.getByLabel('Material áp dụng cho revision này');
  await expect(materialSelect).toHaveValue('5');
  const customize = page.getByLabel('Khách được đổi màu');
  await customize.check();
  await page.locator('.admin-mesh-config__row select').selectOption('9');
  await page.getByLabel('White', { exact: true }).check();
  const save = page.getByRole('button', { name: 'Lưu cấu hình' });
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.getByText('Đã lưu và xác minh revision 1 từ Backend.')).toBeVisible();
  expect(putRequests).toHaveLength(1);
  expect({ ...putRequests[0], meshes: putRequests[0].meshes.map(mesh => ({ ...mesh, allowedColorIds: [...mesh.allowedColorIds].sort((a, b) => a - b) })) }).toEqual({
    mediaAssetId: 23, structureSnapshotId: structure.structureSnapshotId, expectedRevision: 0, materialId: 5,
    meshes: [{ nodeIndex: 0, meshIndex: 4, primitiveIndex: 0, customizable: true, defaultColorId: 9, allowedColorIds: [8, 9] }],
  });

  const evidence = resolve('.qa-evidence/ADMIN3D-FE-MESH-01-2026-11-18');
  mkdirSync(evidence, { recursive: true });
  await page.screenshot({ path: resolve(evidence, 'admin-mesh-editor-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await page.getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.getByText('Revision')).toBeVisible();
  await expect(page.locator('.admin-mesh-config__meta > div').filter({ hasText: 'Revision' }).locator('dd')).toHaveText('1');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: resolve(evidence, 'admin-mesh-editor-mobile.png'), fullPage: true });

  putMode = 'conflict';
  await page.locator('.admin-mesh-config__row select').selectOption('8');
  await page.getByRole('button', { name: 'Lưu cấu hình' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'đã thay đổi (409)' })).toBeVisible();
  await expect(page.locator('.admin-mesh-config__row select')).toHaveValue('8');
  expect(putRequests).toHaveLength(2);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Tải revision mới (xác nhận bỏ draft)' }).click();

  putMode = 'network';
  await page.locator('.admin-mesh-config__row select').selectOption('8');
  await page.getByRole('button', { name: 'Lưu cấu hình' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Chưa xác định kết quả lưu' })).toBeVisible();
  await page.getByRole('button', { name: 'Đọc lại để xác minh kết quả lưu' }).click();
  await expect(page.getByText('Backend xác nhận revision chưa đổi. Có thể thử lưu lại thủ công.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Lưu cấu hình' })).toBeEnabled();
  expect(putRequests).toHaveLength(3);
  expect(authorizationHeaders.length).toBeGreaterThan(3);
  expect(authorizationHeaders.every(value => value === `Bearer ${token}`)).toBe(true);
});
