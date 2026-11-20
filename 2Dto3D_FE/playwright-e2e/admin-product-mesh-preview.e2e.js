import { expect, test } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

function testToken() {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ exp: Math.floor(Date.now() / 1000) + 3600 })}.test-only`;
}

function multiPrimitiveGlb() {
  const binary = Buffer.alloc(44);
  [-0.5, -0.5, 0, 0.5, -0.5, 0, 0, 0.5, 0].forEach((value, index) => binary.writeFloatLE(value, index * 4));
  binary.writeUInt16LE(0, 36); binary.writeUInt16LE(1, 38); binary.writeUInt16LE(2, 40);
  const json = {
    asset: { version: '2.0' }, buffers: [{ byteLength: 42 }],
    bufferViews: [{ buffer: 0, byteOffset: 0, byteLength: 36 }, { buffer: 0, byteOffset: 36, byteLength: 6 }],
    accessors: [
      { bufferView: 0, componentType: 5126, count: 3, type: 'VEC3', min: [-0.5, -0.5, 0], max: [0.5, 0.5, 0] },
      { bufferView: 1, componentType: 5123, count: 3, type: 'SCALAR', min: [0], max: [2] },
    ],
    materials: [
      { name: 'Red source', pbrMetallicRoughness: { baseColorFactor: [1, 0, 0, 1] } },
      { name: 'Blue source', pbrMetallicRoughness: { baseColorFactor: [0, 0, 1, 1] } },
    ],
    meshes: [{ name: 'Two-part body', primitives: [
      { attributes: { POSITION: 0 }, indices: 1, material: 0 },
      { attributes: { POSITION: 0 }, indices: 1, material: 1 },
    ] }],
    nodes: [{ name: 'Body node', mesh: 0 }], scenes: [{ nodes: [0] }], scene: 0,
  };
  const jsonBytes = Buffer.from(JSON.stringify(json));
  const jsonLength = Math.ceil(jsonBytes.length / 4) * 4;
  const totalLength = 12 + 8 + jsonLength + 8 + binary.length;
  const glb = Buffer.alloc(totalLength, 0);
  glb.writeUInt32LE(0x46546c67, 0); glb.writeUInt32LE(2, 4); glb.writeUInt32LE(totalLength, 8);
  glb.writeUInt32LE(jsonLength, 12); glb.writeUInt32LE(0x4e4f534a, 16); glb.fill(0x20, 20, 20 + jsonLength); jsonBytes.copy(glb, 20);
  const binaryHeader = 20 + jsonLength;
  glb.writeUInt32LE(binary.length, binaryHeader); glb.writeUInt32LE(0x004e4942, binaryHeader + 4); binary.copy(glb, binaryHeader + 8);
  return glb;
}

function makeStructure(nodeIndex = 0) {
  return {
    productId: 17, mediaAssetId: 23, structureSnapshotId: '123e4567-e89b-42d3-a456-426614174000',
    parserVersion: 'test-parser', defaultSceneIndex: 0,
    scenes: [{ sceneIndex: 0, name: 'Scene', rootNodeIndices: [nodeIndex] }],
    nodes: [{ nodeIndex, nodeName: 'Body node', meshIndex: 0, childNodeIndices: [], sceneIndices: [0] }],
    meshes: [{ meshIndex: 0, meshName: 'Two-part body', primitives: [
      { primitiveIndex: 0, materialIndex: 0, attributeNames: ['POSITION'], textureIndices: [] },
      { primitiveIndex: 1, materialIndex: 1, attributeNames: ['POSITION'], textureIndices: [] },
    ] }],
    primitiveOccurrences: [0, 1].map(primitiveIndex => ({ nodeIndex, meshIndex: 0, primitiveIndex, originalName: 'Body node', materialIndex: primitiveIndex })),
    materials: [0, 1].map(materialIndex => ({ materialIndex, name: `Material ${materialIndex}`, textureIndices: [] })), textures: [], images: [],
  };
}

test('newly uploaded GLB previews inside Canvas, highlights only with current provenance/mapping, warns before unload, and clears on reload', async ({ page }) => {
  const consoleErrors = [];
  const pageErrors = [];
  const fixture = multiPrimitiveGlb();
  const filePath3d = 'product-media/models/test-only/two-part.glb';
  let created = false;
  let product = null;
  let structure = makeStructure();
  let configuration = null;
  let createCalls = 0;
  await page.addInitScript(({ token }) => {
    localStorage.setItem('morphia_access_token', token);
    localStorage.setItem('morphia_user', JSON.stringify({ token, id: 717, name: 'TEST_ONLY admin', contexts: ['CUSTOMER', 'ADMIN'] }));
  }, { token: testToken() });
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.route('**/api/v1/me', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ contexts: ['CUSTOMER', 'ADMIN'] }) }));
  await page.route('**/api/products**', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 4, totalElements: 0, totalPages: 0 }) }));
  await page.route('**/api/admin/products**', async route => {
    if (route.request().method() === 'POST') {
      createCalls++;
      product = { id: 17, sourceType: 'DESIGNER_MODEL', name: 'TEST_ONLY mesh preview', description: '', category: 'Test',
        status: 'PENDING', filePath3d, modelImages: [] };
      created = true;
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(product) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: created ? [product] : [], page: 0, size: 20, totalElements: created ? 1 : 0, totalPages: created ? 1 : 0,
    }) });
  });
  await page.route('**/api/admin/products/17', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(product) }));
  await page.route('**/api/admin/products/17/mesh-structure', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(structure) }));
  await page.route('**/api/admin/products/17/mesh-configurations', route => {
    configuration ||= { productId: 17, mediaAssetId: structure.mediaAssetId, structureSnapshotId: structure.structureSnapshotId,
      revision: 0, configured: false, materialId: null, meshes: [] };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(configuration) });
  });
  await page.route('**/api/materials/', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
    { id: 5, name: 'PLA', type: 'FILAMENT', isAvailable: 'AVAILABLE', colors: [{ colorId: 8, name: 'White', hexCode: '#ffffff' }] },
  ]) }));

  await page.setViewportSize({ width: 1365, height: 900 });
  await page.goto('/admin/products');
  await page.getByRole('button', { name: 'Tạo bản nháp' }).click();
  await page.getByLabel('Tên sản phẩm').fill('TEST_ONLY mesh preview');
  await page.getByLabel('Ảnh sản phẩm').setInputFiles({ name: 'part.png', mimeType: 'image/png', buffer: Buffer.from('TEST_ONLY image') });
  await page.getByLabel('Mô hình 3D (GLB)').setInputFiles({ name: 'two-part.glb', mimeType: 'model/gltf-binary', buffer: fixture });
  await expect(page.getByRole('heading', { name: 'GLB Inspector' })).toBeVisible();
  await expect(page.locator('.admin-glb-mesh')).toHaveCount(1, { timeout: 20_000 });
  await page.getByRole('button', { name: 'Tạo bản nháp' }).last().click();
  await expect.poll(() => createCalls).toBe(1);
  await expect(page.getByText('TEST_ONLY mesh preview', { exact: true })).toBeVisible();
  await page.locator('tr').filter({ hasText: 'TEST_ONLY mesh preview' }).getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.getByRole('heading', { name: 'Cấu hình bộ phận & màu' })).toBeVisible();
  const canvas = page.locator('.admin-mesh-config__preview canvas');
  await expect(canvas).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('button', { name: 'Chọn trong preview' })).toHaveCount(2);
  await page.getByRole('button', { name: 'Chọn trong preview' }).nth(1).click();
  await expect(page.getByRole('button', { name: 'Chọn trong preview' }).nth(1)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('Primitive 0:0:1 đang được highlight trong preview.')).toBeVisible();
  const webgl = await canvas.evaluate(element => ({
    width: element.width, height: element.height,
    context: Boolean(element.getContext('webgl2') || element.getContext('webgl')),
  }));
  expect(webgl.width).toBeGreaterThan(0); expect(webgl.height).toBeGreaterThan(0); expect(webgl.context).toBe(true);
  const evidence = resolve('.qa-evidence/ADMIN3D-FE-MESH-01-CORRECTION-01-2026-11-18');
  mkdirSync(evidence, { recursive: true });
  await page.screenshot({ path: resolve(evidence, 'mesh-preview-desktop.png'), fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await expect(canvas).toBeVisible();
  await page.screenshot({ path: resolve(evidence, 'mesh-preview-mobile.png'), fullPage: true });

  // A product path that no longer matches the successful create response keeps the local viewer,
  // but provenance gating must remove viewer-to-Backend selection/highlighting.
  await page.getByRole('button', { name: 'Đóng', exact: true }).click();
  product = { ...product, filePath3d: 'product-media/models/test-only/replaced.glb' };
  await page.locator('tr').filter({ hasText: 'TEST_ONLY mesh preview' }).getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.locator('.admin-mesh-config__preview canvas')).toBeVisible();
  await expect(page.getByText(/chưa được liên kết đủ.*highlight đã tắt/i)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Chọn trong preview' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Chọn occurrence Backend' })).toHaveCount(2);

  // Even with matching file provenance, an association mismatch cannot map/highlight.
  await page.getByRole('button', { name: 'Đóng', exact: true }).click();
  product = { ...product, filePath3d };
  structure = makeStructure(7);
  await page.locator('tr').filter({ hasText: 'TEST_ONLY mesh preview' }).getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.locator('.admin-mesh-config__preview canvas')).toBeVisible();
  await expect(page.getByText(/không ánh xạ duy nhất/i)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Chọn trong preview' })).toHaveCount(0);

  // A dirty draft triggers the browser's real beforeunload confirmation. Accept the
  // browser dialog to complete the navigation, then verify local GLB state is gone.
  await page.locator('.admin-mesh-config__row select').first().selectOption('8');
  page.once('dialog', dialog => dialog.dismiss());
  await page.getByRole('button', { name: 'Tải lại từ Backend' }).click();
  await expect(page.getByText('Có thay đổi chưa lưu.')).toBeVisible();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Tải lại từ Backend' }).click();
  await expect(page.getByText('Có thay đổi chưa lưu.')).toHaveCount(0);
  await page.locator('.admin-mesh-config__row select').first().selectOption('8');
  const dialogPromise = page.waitForEvent('dialog', { timeout: 5_000 });
  const navigation = page.reload({ waitUntil: 'domcontentloaded' });
  const dialog = await dialogPromise;
  expect(dialog.type()).toBe('beforeunload');
  await dialog.accept();
  await navigation;
  await page.locator('tr').filter({ hasText: 'TEST_ONLY mesh preview' }).getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.locator('.admin-mesh-config__preview canvas')).toHaveCount(0);
  await expect(page.getByText(/không có endpoint media công khai an toàn/i)).toBeVisible();
  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});
