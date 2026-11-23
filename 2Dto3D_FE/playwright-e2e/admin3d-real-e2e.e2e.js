import { expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { deflateSync, deflateRawSync } from 'node:zlib';

test.use({ launchOptions: { args: [
  '--disable-background-networking',
  '--host-resolver-rules=MAP res.cloudinary.com 0.0.0.0',
] } });

const evidenceDir = resolve(process.env.ADMIN3D_E2E_EVIDENCE_DIR || '');
const apiUrl = process.env.ADMIN3D_E2E_API_URL;
const admin = { email: process.env.ADMIN3D_E2E_ADMIN_EMAIL, password: process.env.ADMIN3D_E2E_ADMIN_PASSWORD };
const secondAdmin = { email: process.env.ADMIN3D_E2E_SECOND_ADMIN_EMAIL, password: process.env.ADMIN3D_E2E_SECOND_ADMIN_PASSWORD };
const user = { email: process.env.ADMIN3D_E2E_USER_EMAIL, password: process.env.ADMIN3D_E2E_USER_PASSWORD };

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let index = 0; index < 8; index += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const typeBytes = Buffer.from(type);
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  typeBytes.copy(out, 4);
  data.copy(out, 8);
  out.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])), 8 + data.length);
  return out;
}

function makePng() {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(2, 0);
  header.writeUInt32BE(2, 4);
  header[8] = 8;
  header[9] = 6;
  const pixels = Buffer.from([
    0, 240, 180, 80, 255, 240, 180, 80, 255,
    0, 240, 180, 80, 255, 240, 180, 80, 255,
  ]);
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', header), pngChunk('IDAT', deflateSync(pixels)), pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

function make3mf() {
  const files = [
    ['[Content_Types].xml', Buffer.from('<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/3D/3dmodel.model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>')],
    ['3D/3dmodel.model', Buffer.from('<?xml version="1.0"?><model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources/><build/></model>')],
  ];
  const local = [];
  const central = [];
  let offset = 0;
  for (const [name, data] of files) {
    const filename = Buffer.from(name);
    const compressed = deflateRawSync(data);
    const crc = crc32(data);
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0x0800, 6);
    localHeader.writeUInt16LE(8, 8);
    localHeader.writeUInt32LE(crc, 14);
    localHeader.writeUInt32LE(compressed.length, 18);
    localHeader.writeUInt32LE(data.length, 22);
    localHeader.writeUInt16LE(filename.length, 26);
    local.push(localHeader, filename, compressed);

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(20, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0x0800, 8);
    centralHeader.writeUInt16LE(8, 10);
    centralHeader.writeUInt32LE(crc, 16);
    centralHeader.writeUInt32LE(compressed.length, 20);
    centralHeader.writeUInt32LE(data.length, 24);
    centralHeader.writeUInt16LE(filename.length, 28);
    centralHeader.writeUInt32LE(offset, 42);
    central.push(centralHeader, filename);
    offset += localHeader.length + filename.length + compressed.length;
  }
  const centralBytes = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, centralBytes, end]);
}

function makeGlb() {
  const gltf = {
    asset: { version: '2.0', generator: '2Dto3D isolated acceptance fixture' },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes: [{ name: 'E2E body node', mesh: 0 }],
    meshes: [{ name: 'E2E double primitive mesh', primitives: [
      { attributes: { POSITION: 0 }, mode: 4 },
      { attributes: { POSITION: 0 }, mode: 4 },
    ] }],
    buffers: [{ byteLength: 36 }],
    bufferViews: [{ buffer: 0, byteOffset: 0, byteLength: 36, target: 34962 }],
    accessors: [{ bufferView: 0, componentType: 5126, count: 3, type: 'VEC3', min: [-1, -1, 0], max: [1, 1, 0] }],
  };
  const json = Buffer.from(JSON.stringify(gltf));
  const jsonLength = (json.length + 3) & ~3;
  const bin = Buffer.alloc(36);
  [[-1, -1, 0], [1, -1, 0], [0, 1, 0]].forEach((vertex, row) => vertex.forEach((value, column) => bin.writeFloatLE(value, row * 12 + column * 4)));
  const total = 12 + 8 + jsonLength + 8 + bin.length;
  const out = Buffer.alloc(total, 0x20);
  out.write('glTF', 0, 'ascii');
  out.writeUInt32LE(2, 4);
  out.writeUInt32LE(total, 8);
  out.writeUInt32LE(jsonLength, 12);
  out.writeUInt32LE(0x4e4f534a, 16);
  json.copy(out, 20);
  const binHeader = 20 + jsonLength;
  out.writeUInt32LE(bin.length, binHeader);
  out.writeUInt32LE(0x004e4942, binHeader + 4);
  bin.copy(out, binHeader + 8);
  return out;
}

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

async function realLogin(request, credentials) {
  const response = await request.post(`${apiUrl}/api/auth/login`, { data: credentials });
  expect(response.status(), 'real login endpoint status').toBe(200);
  const payload = await response.json();
  expect(payload.token).toBeTruthy();
  return payload.token;
}

function recordNetwork(page) {
  const events = [];
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) events.push({ method: request.method(), path: url.pathname, origin: url.origin, status: null, failed: false });
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if (!url.pathname.startsWith('/api/')) return;
    const event = [...events].reverse().find(item => item.path === url.pathname && item.method === response.request().method() && item.status === null);
    if (event) event.status = response.status();
  });
  page.on('requestfailed', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) return;
    const event = [...events].reverse().find(item => item.path === url.pathname && item.method === request.method() && item.status === null);
    if (event) event.failed = true;
  });
  return events;
}

test('real Admin completes product onboarding with materials, private 3MF and public viewer color selection', async ({ page, request, context }) => {
  expect(apiUrl).toBeTruthy();
  expect(admin.email && admin.password && secondAdmin.email && secondAdmin.password && user.email && user.password).toBeTruthy();
  mkdirSync(evidenceDir, { recursive: true });
  const glb = makeGlb();
  const image = makePng();
  writeFileSync(resolve(evidenceDir, 'fixture.glb'), glb);
  writeFileSync(resolve(evidenceDir, 'fixture.png'), image);
  const network = recordNetwork(page);
  const consoleErrors = [];
  const pageErrors = [];
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text().replace(/Bearer\s+\S+/gi, 'Bearer <redacted>')); });
  page.on('pageerror', error => pageErrors.push(error.message.replace(/Bearer\s+\S+/gi, 'Bearer <redacted>')));

  const viteOrigin = new URL(test.info().project.use.baseURL).origin;
  await page.goto('/login');
  const browserLogin = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/auth/login');
  await page.getByLabel('Địa chỉ email').fill(admin.email);
  await page.locator('input#auth-password').fill(admin.password);
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  const loginResponse = await browserLogin;
  const loginPayload = await loginResponse.json();
  loginPayload.email = '<test-admin>';
  loginPayload.token = loginPayload.token ? '<redacted>' : loginPayload.token;
  const loginBody = JSON.stringify(loginPayload);
  writeFileSync(resolve(evidenceDir, 'browser-login-response.json'), JSON.stringify({
    status: loginResponse.status(), contentType: loginResponse.headers()['content-type'] || '', body: loginBody,
  }, null, 2));
  expect(loginResponse.status(), loginBody).toBe(200);
  expect(loginPayload.role).toBe('ADMIN');
  await page.waitForURL(url => url.pathname.startsWith('/admin'));

  const catalogRun = `${Date.now()}`;
  const whiteName = `E2E White ${catalogRun}`;
  const blueName = `E2E Blue ${catalogRun}`;
  const materialName = `E2E PLA ${catalogRun}`;
  await page.goto('/admin/materials');
  await page.getByLabel('Tên màu').fill(whiteName);
  await page.getByLabel('Mã HEX').fill('#eeeeee');
  const whiteResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/colors/create');
  await page.getByRole('button', { name: 'Thêm màu' }).click();
  const whiteResponse = await whiteResponsePromise;
  expect(whiteResponse.status()).toBe(200);
  await page.getByLabel('Tên màu').fill(blueName);
  await page.getByLabel('Mã HEX').fill('#2255cc');
  const blueResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/colors/create');
  await page.getByRole('button', { name: 'Thêm màu' }).click();
  const blueResponse = await blueResponsePromise;
  expect(blueResponse.status()).toBe(200);
  await page.getByLabel('Tên vật liệu').fill(materialName);
  await page.getByLabel('Loại').fill('PLA');
  await page.getByLabel('Giá mỗi gram').fill('1');
  await page.getByLabel('Số lượng tồn').fill('0');
  await page.getByLabel('Đơn vị').fill('g');
  await page.getByRole('checkbox', { name: whiteName }).check();
  await page.getByRole('checkbox', { name: blueName }).check();
  const materialResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/materials/create');
  await page.getByRole('button', { name: 'Thêm vật liệu' }).click();
  const materialResponse = await materialResponsePromise;
  expect(materialResponse.status()).toBe(200);
  const catalogHeaders = { Authorization: `Bearer ${loginPayload.token}` };
  const colorsResponse = await request.get(`${apiUrl}/api/colors/`, { headers: catalogHeaders });
  expect(colorsResponse.status()).toBe(200);
  const catalogColors = await colorsResponse.json();
  const whiteColor = catalogColors.find(color => color.name === whiteName);
  const blueColor = catalogColors.find(color => color.name === blueName);
  expect(whiteColor?.colorId).toBeGreaterThan(0);
  expect(blueColor?.colorId).toBeGreaterThan(0);
  const materialsResponse = await request.get(`${apiUrl}/api/materials/`, { headers: catalogHeaders });
  expect(materialsResponse.status()).toBe(200);
  const createdMaterial = (await materialsResponse.json()).find(material => material.name === materialName);
  expect(createdMaterial?.id).toBeGreaterThan(0);
  expect(createdMaterial.colors.map(color => color.colorId)).toEqual(expect.arrayContaining([whiteColor.colorId, blueColor.colorId]));

  await page.goto('/admin/products');
  await expect(page.getByRole('heading', { name: 'Quản lý sản phẩm' })).toBeVisible();

  const productName = `Admin3D Real E2E ${Date.now()}`;
  await page.getByRole('button', { name: 'Tạo bản nháp' }).first().click();
  await page.getByLabel('Tên sản phẩm').fill(productName);
  await page.getByLabel('Danh mục').fill('Admin3D E2E');
  await page.getByLabel('Mô tả').fill('Disposable PostgreSQL browser acceptance product');
  await page.getByLabel('Ảnh sản phẩm').setInputFiles({ name: 'admin3d-e2e.png', mimeType: 'image/png', buffer: image });
  await page.getByLabel('Mô hình 3D (GLB)').setInputFiles({ name: 'admin3d-e2e.glb', mimeType: 'model/gltf-binary', buffer: glb });
  await expect(page.getByRole('heading', { name: 'GLB Inspector' })).toBeVisible();
  await expect(page.locator('.admin-glb-mesh')).toHaveCount(1);
  await expect(page.locator('.admin-glb-mesh dd').nth(1)).toHaveText('2');
  await page.screenshot({ path: resolve(evidenceDir, 'browser-glb-inspector.png'), fullPage: true });

  const createResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/admin/products');
  await page.getByRole('button', { name: 'Tạo bản nháp' }).last().click();
  const createResponse = await createResponsePromise;
  expect(createResponse.status()).toBe(201);
  const created = await createResponse.json();
  expect(created.name).toBe(productName);
  expect(created.filePath3d).toMatch(/^product-media\/models\//);
  expect(created.modelImages).toHaveLength(1);
  const productId = created.id;
  expect(productId).toBeGreaterThan(0);
  await expect(page.getByText(productName, { exact: true })).toBeVisible();

  const row = page.locator('tr').filter({ hasText: productName });
  await row.getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.getByRole('heading', { name: 'Cấu hình bộ phận & màu' })).toBeVisible();
  await expect(page.locator('.admin-mesh-config__row')).toHaveCount(2);
  const structure = await (await request.get(`${apiUrl}/api/admin/products/${productId}/mesh-structure`, {
    headers: { Authorization: `Bearer ${await realLogin(request, admin)}` },
  })).json();
  expect(structure.productId).toBe(productId);
  expect(structure.primitiveOccurrences).toHaveLength(2);
  const structureSnapshotId = structure.structureSnapshotId;
  const mediaAssetId = structure.mediaAssetId;
  const materialSelect = page.getByLabel('Material áp dụng cho revision này');
  await expect(materialSelect.locator('option').filter({ hasText: materialName })).toHaveCount(1);
  await materialSelect.selectOption({ label: materialName });
  const rows = page.locator('.admin-mesh-config__row');
  await rows.nth(0).getByLabel('Khách được đổi màu').check();
  await rows.nth(0).locator('label').filter({ hasText: 'Màu mặc định' }).locator('select').selectOption({ label: blueName });
  await rows.nth(0).getByLabel(whiteName, { exact: true }).check();
  await rows.nth(1).locator('label').filter({ hasText: 'Màu mặc định' }).locator('select').selectOption({ label: whiteName });
  const saveResponsePromise = page.waitForResponse(response => response.request().method() === 'PUT'
    && new URL(response.url()).pathname === `/api/admin/products/${productId}/mesh-configurations`);
  await page.getByRole('button', { name: 'Lưu cấu hình' }).click();
  const saveResponse = await saveResponsePromise;
  expect(saveResponse.status()).toBe(200);
  const putPayload = saveResponse.request().postDataJSON();
  expect(putPayload).toMatchObject({ mediaAssetId, structureSnapshotId, expectedRevision: 0, meshes: [
    { nodeIndex: 0, meshIndex: 0, primitiveIndex: 0, customizable: true },
    { nodeIndex: 0, meshIndex: 0, primitiveIndex: 1, customizable: false },
  ] });
  await expect(page.getByText('Đã lưu và xác minh revision 1 từ Backend.')).toBeVisible();
  expect(new Set(network.filter(event => event.path.startsWith('/api/')).map(event => event.origin))).toEqual(new Set([viteOrigin]));
  const firstSnapshot = await request.get(`${apiUrl}/api/admin/products/${productId}/mesh-structure`, {
    headers: { Authorization: `Bearer ${await realLogin(request, admin)}` },
  });
  expect(firstSnapshot.status()).toBe(200);

  const printAssetsUrl = `/api/admin/products/${productId}/print-assets`;
  await page.getByLabel('Tệp in 3D (.3mf)').setInputFiles({ name: 'invalid.txt', mimeType: 'text/plain', buffer: Buffer.from('not-3mf') });
  const postsBeforeInvalidExtension = network.filter(event => event.method === 'POST' && event.path === printAssetsUrl).length;
  await page.getByRole('button', { name: 'Tải 3MF lên' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'phần mở rộng .3mf' })).toBeVisible();
  expect(network.filter(event => event.method === 'POST' && event.path === printAssetsUrl)).toHaveLength(postsBeforeInvalidExtension);

  const printFile = make3mf();
  await page.getByLabel('Tệp in 3D (.3mf)').setInputFiles({ name: 'admin3d-e2e.3mf', mimeType: 'model/3mf', buffer: printFile });
  const printUploadPromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === printAssetsUrl);
  await page.getByRole('button', { name: 'Tải 3MF lên' }).click();
  const printUpload = await printUploadPromise;
  expect(printUpload.status()).toBe(201);
  const printMetadata = await printUpload.json();
  expect(printMetadata).toMatchObject({ fileName: 'admin3d-e2e.3mf', status: 'ATTACHED' });
  expect(Object.keys(printMetadata).sort()).toEqual(['byteCount', 'fileName', 'mediaAssetId', 'status', 'uploadedAt'].sort());
  await expect(page.getByText('admin3d-e2e.3mf', { exact: true })).toBeVisible();
  await expect(page.locator('.admin-print-assets a')).toHaveCount(0);
  const invalidArchive = await request.post(`${apiUrl}${printAssetsUrl}`, {
    headers: { Authorization: `Bearer ${await realLogin(request, admin)}` },
    multipart: { file: { name: 'invalid.3mf', mimeType: 'model/3mf', buffer: Buffer.from('invalid zip') } },
  });
  expect(invalidArchive.status()).toBe(415);

  await page.reload();
  await expect(page.getByRole('heading', { name: 'Quản lý sản phẩm' })).toBeVisible();
  const reloadedRow = page.locator('tr').filter({ hasText: productName });
  await reloadedRow.getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(page.locator('.admin-mesh-config__meta > div').filter({ hasText: 'Revision' }).locator('dd')).toHaveText('1');
  await expect(page.getByText('GLB lưu trữ chỉ được tải lại sau khi sản phẩm được xuất bản. Bản nháp chỉ dùng preview cục bộ trong phiên đã tải lên.')).toBeVisible();
  await expect(page.locator('.admin-mesh-config__row')).toHaveCount(2);
  await expect(page.locator('.admin-mesh-config__row').nth(0).getByLabel('Khách được đổi màu')).toBeChecked();
  await expect(page.locator('.admin-mesh-config__row').nth(1).getByLabel('Khách được đổi màu')).not.toBeChecked();
  await page.screenshot({ path: resolve(evidenceDir, 'browser-mesh-reload-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: resolve(evidenceDir, 'browser-mesh-reload-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1365, height: 900 });

  const editorRows = page.locator('.admin-mesh-config__row');
  await editorRows.nth(0).locator('label').filter({ hasText: 'Màu mặc định' }).locator('select').selectOption({ label: whiteName });
  const putsBeforeOffline = network.filter(event => event.method === 'PUT' && event.path.endsWith('/mesh-configurations')).length;
  await context.setOffline(true);
  await page.getByRole('button', { name: 'Lưu cấu hình' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Chưa xác định kết quả lưu' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Đọc lại để xác minh kết quả lưu' })).toBeVisible();
  await context.setOffline(false);
  await page.getByRole('button', { name: 'Đọc lại để xác minh kết quả lưu' }).click();
  await expect(page.getByText('Backend xác nhận revision chưa đổi. Có thể thử lưu lại thủ công.')).toBeVisible();
  expect(network.filter(event => event.method === 'PUT' && event.path.endsWith('/mesh-configurations'))).toHaveLength(putsBeforeOffline + 1);

  const adminToken = await realLogin(request, admin);
  const secondAdminToken = await realLogin(request, secondAdmin);
  const userToken = await realLogin(request, user);
  const meshUrl = `${apiUrl}/api/admin/products/${productId}/mesh-configurations`;
  const anonymous = await request.get(meshUrl);
  expect(anonymous.status()).toBe(401);
  const nonAdmin = await request.get(meshUrl, { headers: { Authorization: `Bearer ${userToken}` } });
  expect(nonAdmin.status()).toBe(403);
  expect(await nonAdmin.text()).not.toContain(structureSnapshotId);
  expect((await request.get(`${apiUrl}/api/admin/products`, { headers: { Authorization: `Bearer ${userToken}` } })).status()).toBe(403);
  expect((await request.get(`${apiUrl}/api/admin/products`, { headers: { Authorization: `Bearer ${adminToken}` } })).status()).toBe(200);
  const deniedPrintUpload = await request.post(`${apiUrl}${printAssetsUrl}`, {
    headers: { Authorization: `Bearer ${userToken}` },
    multipart: { file: { name: 'forbidden.3mf', mimeType: 'model/3mf', buffer: printFile } },
  });
  expect(deniedPrintUpload.status()).toBe(403);
  expect((await request.get(`${apiUrl}${printAssetsUrl}`, { headers: { Authorization: `Bearer ${userToken}` } })).status()).toBe(403);

  const oversizedTotalBytes = 33_554_433;
  let oversizedSent = 0;
  const oversizedBody = new ReadableStream({
    pull(controller) {
      if (oversizedSent >= oversizedTotalBytes) { controller.close(); return; }
      const count = Math.min(64 * 1024, oversizedTotalBytes - oversizedSent);
      controller.enqueue(new Uint8Array(count).fill(0x20));
      oversizedSent += count;
    },
  });
  const oversizedResponse = await fetch(`${apiUrl}/api/admin/products/${productId}/mesh-configurations`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
    body: oversizedBody,
    duplex: 'half',
  });
  const oversized = {
    status: oversizedResponse.status,
    contentType: oversizedResponse.headers.get('content-type') || '',
    body: await oversizedResponse.text(),
    streamedBytes: oversizedSent,
  };
  writeFileSync(resolve(evidenceDir, 'oversized-chunked-http.json'), JSON.stringify(oversized, null, 2));
  expect(oversized.streamedBytes).toBe(33_554_433);
  expect(oversized.status).toBe(413);
  expect(JSON.parse(oversized.body).code).toBe('REQUEST_TOO_LARGE');

  const invalidHeaderGlb = Buffer.from([0x67, 0x6c, 0x54, 0x46, 2, 0, 0, 0, 12, 0, 0, 0]);
  const invalidUpload = await request.post(`${apiUrl}/api/admin/products`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    multipart: {
      data: { name: 'invalid header GLB', description: '', category: 'test', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ name: 'invalid header GLB', description: '', category: 'test' })) },
      images: { name: 'invalid-header.png', mimeType: 'image/png', buffer: image },
      model: { name: 'header-only.glb', mimeType: 'model/gltf-binary', buffer: invalidHeaderGlb },
    },
  });
  expect(invalidUpload.status()).toBe(415);

  const getConfig = async () => request.get(meshUrl, { headers: { Authorization: `Bearer ${adminToken}` } });
  const loadedConfiguration = await getConfig();
  expect(loadedConfiguration.status()).toBe(200);
  const current = await loadedConfiguration.json();
  expect(current.revision).toBe(1);
  const colorIds = current.meshes[0].allowedSelections.map(selection => selection.colorId);
  const currentPayload = {
    mediaAssetId, structureSnapshotId, expectedRevision: 1, materialId: current.materialId,
    meshes: current.meshes.map(mesh => ({ nodeIndex: mesh.nodeIndex, meshIndex: mesh.meshIndex,
      primitiveIndex: mesh.primitiveIndex, customizable: mesh.customizable,
      defaultColorId: mesh.defaultSelection.colorId,
      allowedColorIds: mesh.allowedSelections.map(selection => selection.colorId) })),
  };
  const invalidMaterial = await request.put(meshUrl, { headers: { Authorization: `Bearer ${adminToken}` },
    data: { ...currentPayload, materialId: 2147483000 } });
  expect(invalidMaterial.status()).toBe(422);
  const missingOccurrence = await request.put(meshUrl, { headers: { Authorization: `Bearer ${adminToken}` },
    data: { ...currentPayload, meshes: currentPayload.meshes.slice(0, 1) } });
  expect(missingOccurrence.status()).toBe(422);
  const staleSnapshot = await request.put(meshUrl, { headers: { Authorization: `Bearer ${adminToken}` },
    data: { ...currentPayload, structureSnapshotId: '123e4567-e89b-42d3-a456-426614174000' } });
  expect(staleSnapshot.status()).toBe(409);
  const staleMedia = await request.put(meshUrl, { headers: { Authorization: `Bearer ${adminToken}` },
    data: { ...currentPayload, mediaAssetId: mediaAssetId + 1000 } });
  expect(staleMedia.status()).toBe(409);
  const staleRevision = await request.put(meshUrl, { headers: { Authorization: `Bearer ${adminToken}` },
    data: { ...currentPayload, expectedRevision: 0 } });
  expect(staleRevision.status()).toBe(409);
  expect((await getConfig()).status()).toBe(200);
  expect((await (await getConfig()).json()).revision).toBe(1);

  const concurrentPayloadA = { ...currentPayload, meshes: currentPayload.meshes.map(mesh => ({ ...mesh, defaultColorId: colorIds[0] })) };
  const concurrentPayloadB = { ...currentPayload, meshes: currentPayload.meshes.map(mesh => ({
    ...mesh,
    defaultColorId: mesh.customizable ? mesh.allowedColorIds.at(-1) : mesh.defaultColorId,
  })) };
  const concurrent = await Promise.all([
    request.put(meshUrl, { headers: { Authorization: `Bearer ${adminToken}` }, data: concurrentPayloadA }),
    request.put(meshUrl, { headers: { Authorization: `Bearer ${secondAdminToken}` }, data: concurrentPayloadB }),
  ]);
  const concurrentEvidence = await Promise.all(concurrent.map(async response => ({
    status: response.status(), body: await response.text(),
  })));
  writeFileSync(resolve(evidenceDir, 'concurrency-result.json'), JSON.stringify(concurrentEvidence, null, 2));
  expect(concurrent.map(response => response.status()).sort(), JSON.stringify(concurrentEvidence)).toEqual([200, 409]);
  const afterRace = await getConfig();
  expect(afterRace.status()).toBe(200);
  expect((await afterRace.json()).revision).toBe(2);

  await page.once('dialog', dialog => dialog.accept());
  const publishPromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/admin/products/${productId}/publish`);
  await page.locator('tr').filter({ hasText: productName }).getByRole('button', { name: 'Xuất bản' }).click();
  const publishResponse = await publishPromise;
  const publishBody = await publishResponse.text();
  expect(publishResponse.status(), publishBody).toBe(204);

  const publicDetailResponse = await request.get(`${apiUrl}/api/products/${productId}`);
  expect(publicDetailResponse.status()).toBe(200);
  const publicDetail = await publicDetailResponse.json();
  expect(publicDetail.modelMeshAppearances).toHaveLength(2);
  expect(publicDetail.modelMeshAppearances[0]).toMatchObject({ customizable: true });
  expect(publicDetail.modelMeshAppearances[0].allowedColors.map(color => color.name)).toEqual(expect.arrayContaining([whiteName, blueName]));
  expect(publicDetail.modelMeshAppearances[1]).toMatchObject({ customizable: false, allowedColors: [] });
  expect(publicDetail.model3dUrl).toMatch(/^https:\/\/res\.cloudinary\.com\/test-cloud\/raw\/upload\//);

  let fakeGlbRequests = 0;
  await context.route(publicDetail.model3dUrl, async route => {
    fakeGlbRequests += 1;
    await route.fulfill({ status: 200, contentType: 'model/gltf-binary', body: glb,
      headers: { 'Access-Control-Allow-Origin': viteOrigin, 'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS' } });
  });
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await context.clearCookies();
  const reopened = await context.newPage();
  await reopened.goto('/login');
  const reopenedLogin = reopened.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/auth/login');
  await reopened.getByLabel('Địa chỉ email').fill(admin.email);
  await reopened.locator('input#auth-password').fill(admin.password);
  await reopened.getByRole('button', { name: 'Đăng nhập' }).click();
  expect((await reopenedLogin).status()).toBe(200);
  await reopened.waitForURL(url => url.pathname.startsWith('/admin'));
  await reopened.goto('/admin/products');
  const publishedRow = reopened.locator('tr').filter({ hasText: productName });
  await expect(publishedRow).toContainText('Đang hoạt động');
  await publishedRow.getByRole('button', { name: 'Chỉnh sửa' }).click();
  await expect(reopened.getByText('Đang tải lại GLB đã xuất bản…')).toHaveCount(0);
  await expect(reopened.locator('.admin-mesh-config__preview canvas')).toBeVisible();
  await expect(reopened.getByText('admin3d-e2e.3mf', { exact: true })).toBeVisible();

  await reopened.goto(`/product/${productId}`);
  await expect(reopened.getByRole('heading', { name: productName })).toBeVisible();
  await reopened.getByRole('button', { name: 'Xem mô hình 3D' }).click();
  const publicColorSelect = reopened.getByLabel('Màu bộ phận 1');
  await expect(publicColorSelect).toBeVisible();
  await expect(reopened.getByLabel('Màu bộ phận 2')).toHaveCount(0);
  await publicColorSelect.selectOption({ label: whiteName });
  await expect(publicColorSelect).toHaveValue(String(whiteColor.colorId));
  await expect(reopened.locator('.joy-detail__model-viewer canvas')).toBeVisible();
  expect(fakeGlbRequests).toBeGreaterThanOrEqual(2);

  const requestOrigins = [...new Set(network.filter(event => event.path.startsWith('/api/')).map(event => event.origin))];
  expect(requestOrigins).toEqual([viteOrigin]);
  writeFileSync(resolve(evidenceDir, 'browser-result.json'), JSON.stringify({
    productId, productName, mediaAssetId, structureSnapshotId, materialId: current.materialId,
    whiteColorId: String(whiteColor.colorId), blueColorId: String(blueColor.colorId),
    snapshotRevision: 1, finalConfigurationRevision: 2, meshOccurrences: structure.primitiveOccurrences.length,
    viteOrigin, backendApiOrigin: apiUrl, fixtureGlbBytes: glb.length, fixtureGlbSha256: sha256(glb),
    fixtureImageBytes: image.length, fixtureImageSha256: sha256(image),
    fixture3mfBytes: printFile.length, fixture3mfSha256: sha256(printFile),
    statuses: { anonymous: anonymous.status(), nonAdmin: nonAdmin.status(), invalidGlb: invalidUpload.status(),
      invalidMaterial: invalidMaterial.status(), missingOccurrence: missingOccurrence.status(),
      staleSnapshot: staleSnapshot.status(), staleMedia: staleMedia.status(), staleRevision: staleRevision.status(),
      chunkedOversize: oversized.status, concurrent: concurrent.map(response => response.status()).sort() },
    apiEvents: network, consoleErrors, pageErrors,
  }, null, 2));
});
