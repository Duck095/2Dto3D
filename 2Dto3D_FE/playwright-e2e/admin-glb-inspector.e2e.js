import { expect, test } from '@playwright/test';
import { resolve } from 'node:path';

function testToken() {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ exp: Math.floor(Date.now() / 1000) + 3600 })}.test-only`;
}

function externalGlb(resource, uri) {
  const json = Buffer.from(JSON.stringify({ asset: { version: '2.0' }, [resource]: [{ uri, byteLength: 4 }] }));
  const jsonLength = Math.ceil(json.length / 4) * 4;
  const bytes = Buffer.alloc(20 + jsonLength, 0x20);
  bytes.writeUInt32LE(0x46546c67, 0);
  bytes.writeUInt32LE(2, 4);
  bytes.writeUInt32LE(bytes.length, 8);
  bytes.writeUInt32LE(jsonLength, 12);
  bytes.writeUInt32LE(0x4e4f534a, 16);
  json.copy(bytes, 20);
  return bytes;
}

function multiPrimitiveGlbFixture() {
  const binary = Buffer.alloc(44);
  [-0.5, -0.5, 0, 0.5, -0.5, 0, 0, 0.5, 0].forEach((value, index) => binary.writeFloatLE(value, index * 4));
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
  return glb;
}

test('admin GLB inspector previews an offline multi-mesh fixture and rejects corrupt GLB data', async ({ page }) => {
  const requests = [];
  const externalRequests = [];
  const fixture = multiPrimitiveGlbFixture();
  await page.addInitScript(({ token }) => {
    localStorage.setItem('morphia_access_token', token);
    localStorage.setItem('morphia_user', JSON.stringify({ token, id: 999, name: 'TEST_ONLY admin', contexts: ['CUSTOMER', 'ADMIN'] }));
  }, { token: testToken() });
  await page.route('**/api/v1/me', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ contexts: ['CUSTOMER', 'ADMIN'] }) }));
  await page.route('**/api/admin/products**', route => {
    requests.push({ method: route.request().method(), url: route.request().url() });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) });
  });
  page.on('request', request => {
    if (request.url().includes('example.invalid')) externalRequests.push(request.url());
  });

  await page.setViewportSize({ width: 1365, height: 900 });
  await page.goto('/admin/products');
  await expect(page.getByRole('heading', { name: 'Quản lý sản phẩm' })).toBeVisible();
  await page.getByRole('button', { name: 'Tạo bản nháp' }).click();
  const modelInput = page.getByLabel('Mô hình 3D (GLB)');
  await modelInput.setInputFiles({ name: 'offline-multi-primitive-instances.glb', mimeType: 'model/gltf-binary', buffer: fixture });
  await expect(page.getByRole('heading', { name: 'GLB Inspector' })).toBeVisible();
  const meshButtons = page.locator('.admin-glb-mesh__select');
  await expect(meshButtons.first()).toBeVisible({ timeout: 20_000 });
  await expect(meshButtons).toHaveCount(2);
  await expect(page.locator('.admin-glb-mesh').first().locator('dd').nth(1)).toHaveText('2');
  await expect(page.locator('.admin-glb-mesh').nth(1).locator('dd').nth(1)).toHaveText('2');
  await expect(page.getByText(/gồm 2 primitive/)).toHaveCount(2);
  await expect(page.getByText(/Chưa có contract ánh xạ mesh với cấu hình sản xuất/)).toBeVisible();

  await meshButtons.nth(1).click();
  await expect(meshButtons.nth(1)).toHaveAttribute('aria-pressed', 'true');
  const renamed = page.locator('.admin-glb-mesh').nth(1).getByLabel('Tên hiển thị trong phiên');
  await renamed.fill('Kiểm tra tại chỗ');
  await expect(renamed).toHaveValue('Kiểm tra tại chỗ');

  const canvas = page.locator('.admin-glb-viewer canvas');
  await expect(canvas).toBeVisible();
  await page.screenshot({ path: resolve('.qa-evidence/FE-ADMIN-3D-PRODUCT-01A-CORRECTION-02-2026-11-17/viewer-before-click.png'), fullPage: false });
  const selectedBeforeViewerClick = await meshButtons.evaluateAll(buttons => buttons.findIndex(button => button.getAttribute('aria-pressed') === 'true'));
  const canvasBox = await canvas.boundingBox();
  await canvas.click({ position: { x: canvasBox.width * 0.42, y: canvasBox.height / 2 } });
  await expect.poll(async () => meshButtons.evaluateAll(buttons => buttons.findIndex(button => button.getAttribute('aria-pressed') === 'true'))).not.toBe(selectedBeforeViewerClick);
  expect(requests.every(request => request.method === 'GET')).toBe(true);

  await page.screenshot({ path: resolve('.qa-evidence/FE-ADMIN-3D-PRODUCT-01A-CORRECTION-02-2026-11-17/glb-inspector-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Quản lý sản phẩm' })).toBeVisible();
  await page.getByRole('button', { name: 'Tạo bản nháp' }).click();
  await modelInput.setInputFiles({ name: 'offline-multi-primitive-instances.glb', mimeType: 'model/gltf-binary', buffer: fixture });
  await expect(canvas).toBeVisible();
  await expect(meshButtons).toHaveCount(2, { timeout: 20_000 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: resolve('.qa-evidence/FE-ADMIN-3D-PRODUCT-01A-CORRECTION-02-2026-11-17/glb-inspector-mobile.png'), fullPage: true });

  const externalFile = externalGlb('buffers', 'https://example.invalid/not-downloaded.bin');
  await modelInput.setInputFiles({ name: 'external.glb', mimeType: 'model/gltf-binary', buffer: externalFile });
  await expect(page.getByRole('alert')).toContainText('tham chiếu tài nguyên bên ngoài');
  await expect(page.getByRole('button', { name: 'Tạo bản nháp' })).toBeDisabled();
  expect(externalRequests).toEqual([]);

  await modelInput.setInputFiles({ name: 'broken.glb', mimeType: 'model/gltf-binary', buffer: Buffer.from('not a glb') });
  await expect(page.getByRole('alert')).toContainText('GLB không đúng định dạng');
  await expect(page.getByRole('button', { name: 'Tạo bản nháp' })).toBeDisabled();
  expect(requests.some(request => request.method !== 'GET')).toBe(false);
});
