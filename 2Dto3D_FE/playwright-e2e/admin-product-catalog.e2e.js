import { expect, test } from '@playwright/test';
import { resolve, sep } from 'node:path';
import { mkdirSync, readFileSync } from 'node:fs';

function credentials(name, passwordName) {
  const email = process.env[name];
  const password = process.env[passwordName];
  if (!email || !password) throw new Error('Required TEST_ONLY catalog browser data is missing.');
  return { email, password };
}

async function authenticate(request, apiUrl, user) {
  const response = await request.post(`${apiUrl}/api/auth/login`, { data: user });
  expect(response.status()).toBe(200);
  return (await response.json()).token;
}

function multipart(metadata, fixtureRoot, fixtureBuffers, imageNames = ['catalog.png', 'catalog.jpg', 'catalog.webp'], modelName = 'catalog.glb') {
  const form = new FormData();
  form.append('data', new File([JSON.stringify(metadata)], 'data.json', { type: 'application/json' }));
  for (const name of imageNames) {
    const type = name.endsWith('.jpg') ? 'image/jpeg' : name.endsWith('.webp') ? 'image/webp' : 'image/png';
    form.append('images', new File([fixtureBuffers.get(resolve(fixtureRoot, name))], name, { type }));
  }
  form.append('model', new File([fixtureBuffers.get(resolve(fixtureRoot, modelName))], modelName, { type: 'model/gltf-binary' }));
  return form;
}

test('Admin creates, edits, publishes, and archives products; public catalog loads persisted media', async ({ page, request }) => {
  const apiUrl = process.env.CATALOG_E2E_API_URL;
  const mediaRoot = resolve(process.env.CATALOG_E2E_MEDIA_ROOT || '');
  const fixtureRoot = resolve(process.env.CATALOG_E2E_FIXTURE_ROOT || '');
  const evidenceRoot = resolve(process.env.CATALOG_E2E_EVIDENCE_DIR || '');
  const admin = credentials('CATALOG_E2E_ADMIN_EMAIL', 'CATALOG_E2E_ADMIN_PASSWORD');
  const user = credentials('CATALOG_E2E_USER_EMAIL', 'CATALOG_E2E_USER_PASSWORD');
  if (!apiUrl || !process.env.CATALOG_E2E_MEDIA_ROOT || !process.env.CATALOG_E2E_FIXTURE_ROOT || !process.env.CATALOG_E2E_EVIDENCE_DIR) {
    throw new Error('Required TEST_ONLY catalog runtime paths are missing.');
  }

  const fixtureNames = ['catalog.png', 'catalog.jpg', 'catalog.webp', 'catalog.glb', 'invalid.png', 'invalid.glb', 'e2e-fail-upload.png'];
  const fixtureBuffers = new Map();
  for (const name of fixtureNames) {
    fixtureBuffers.set(resolve(fixtureRoot, name), readFileSync(resolve(fixtureRoot, name)));
  }

  const deliveredMedia = [];
  await page.route('https://res.cloudinary.com/test-cloud/**', async route => {
    const url = new URL(route.request().url());
    const segments = url.pathname.split('/').filter(Boolean);
    const uploadIndex = segments.indexOf('upload');
    const resource = uploadIndex > 0 ? segments[uploadIndex - 1] : '';
    const publicParts = segments.slice(uploadIndex + 2);
    if (uploadIndex < 0 || publicParts.length === 0) {
      await route.fulfill({ status: 404, body: 'Unknown TEST_ONLY media path.' });
      return;
    }
    if (resource === 'image') publicParts[publicParts.length - 1] = publicParts.at(-1).replace(/\.(?:png|jpe?g|webp)$/i, '');
    const filePath = resolve(mediaRoot, ...publicParts);
    if (!filePath.startsWith(mediaRoot + sep)) {
      await route.fulfill({ status: 404, body: 'Invalid TEST_ONLY media path.' });
      return;
    }
    deliveredMedia.push({ resource, filePath });
    await route.fulfill({
      path: filePath,
      headers: { 'access-control-allow-origin': '*', 'cache-control': 'no-store' },
    });
  });

  const adminToken = await authenticate(request, apiUrl, admin);
  const userToken = await authenticate(request, apiUrl, user);
  const productUrl = `${apiUrl}/api/admin/products`;
  expect((await request.post(productUrl)).status()).toBe(401);
  expect((await request.post(productUrl, { headers: { Authorization: `Bearer ${userToken}` } })).status()).toBe(403);

  await page.goto('/login');
  const loginResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/auth/login');
  await page.getByLabel('Địa chỉ email').fill(admin.email);
  await page.locator('input#auth-password').fill(admin.password);
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  const loginResponse = await loginResponsePromise;
  expect(loginResponse.status()).toBe(200);
  expect((await loginResponse.json()).role).toBe('ADMIN');
  await page.waitForURL(url => url.pathname.startsWith('/admin'));
  await page.goto('/admin/products');
  await expect(page.getByRole('heading', { name: 'Quản lý sản phẩm' })).toBeVisible();

  const productName = 'Catalog E2E Product 00';
  await page.getByRole('button', { name: 'Tạo bản nháp' }).click();
  await page.getByLabel('Tên sản phẩm').fill(productName);
  await page.getByLabel('Danh mục').fill('Test Catalog');
  await page.getByLabel('Mô tả').fill('TEST_ONLY browser product description');
  await page.getByLabel('Ảnh sản phẩm').setInputFiles(['catalog.png', 'catalog.jpg', 'catalog.webp'].map(name => ({
    name,
    mimeType: name.endsWith('.jpg') ? 'image/jpeg' : name.endsWith('.webp') ? 'image/webp' : 'image/png',
    buffer: fixtureBuffers.get(resolve(fixtureRoot, name)),
  })));
  await page.getByLabel('Mô hình 3D (GLB)').setInputFiles({
    name: 'catalog.glb', mimeType: 'model/gltf-binary', buffer: fixtureBuffers.get(resolve(fixtureRoot, 'catalog.glb')),
  });
  const createResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/admin/products');
  await page.getByRole('button', { name: 'Tạo bản nháp' }).last().click();
  const createResponse = await createResponsePromise;
  expect(createResponse.status()).toBe(201);
  const created = await createResponse.json();
  expect(created).toMatchObject({ name: productName, description: 'TEST_ONLY browser product description', category: 'Test Catalog', status: 'PENDING' });
  expect(created.filePath3d).toMatch(/^product-media\/models\//);
  expect(created.modelImages).toHaveLength(3);
  expect(created.modelImages.map(image => image.imagePath)).toEqual(expect.arrayContaining([
    expect.stringMatching(/^product-media\/images\//),
  ]));
  await expect(page.getByText(productName, { exact: true })).toBeVisible();

  const pendingSearch = await request.get(`${apiUrl}/api/products`, { params: { q: productName } });
  expect(pendingSearch.status()).toBe(200);
  expect((await pendingSearch.json()).items).toHaveLength(0);
  expect((await request.get(`${apiUrl}/api/products/${created.id}`)).status()).toBe(404);

  const adminRow = page.locator('tr').filter({ hasText: productName });
  await adminRow.getByRole('button', { name: 'Chỉnh sửa' }).click();
  await page.locator('input[name="edit-name"]').fill(productName);
  await page.locator('input[name="edit-category"]').fill('Test Catalog Edited');
  await page.locator('textarea[name="edit-description"]').fill('TEST_ONLY edited product description');
  const updateResponsePromise = page.waitForResponse(response => response.request().method() === 'PATCH'
    && new URL(response.url()).pathname === `/api/admin/products/${created.id}`);
  await page.getByRole('button', { name: 'Lưu thông tin' }).click();
  const updateResponse = await updateResponsePromise;
  expect(updateResponse.status()).toBe(200);
  expect(await updateResponse.json()).toMatchObject({ category: 'Test Catalog Edited', description: 'TEST_ONLY edited product description' });

  const badImage = await request.post(productUrl, {
    headers: { Authorization: `Bearer ${adminToken}` },
    multipart: multipart({ name: 'invalid image', description: '', category: '' }, fixtureRoot, fixtureBuffers, ['invalid.png']),
  });
  expect(badImage.status(), await badImage.text()).toBe(415);
  const badGlb = await request.post(productUrl, {
    headers: { Authorization: `Bearer ${adminToken}` },
    multipart: multipart({ name: 'invalid glb', description: '', category: '' }, fixtureRoot, fixtureBuffers, ['catalog.png'], 'invalid.glb'),
  });
  expect(badGlb.status()).toBe(415);
  const oversizedForm = new FormData();
  oversizedForm.append('data', new File([JSON.stringify({ name: 'oversize', description: '', category: '' })], 'data.json', { type: 'application/json' }));
  oversizedForm.append('images', new File([fixtureBuffers.get(resolve(fixtureRoot, 'catalog.png'))], 'catalog.png', { type: 'image/png' }));
  oversizedForm.append('model', new File([Buffer.alloc(50 * 1024 * 1024 + 1)], 'oversized.glb', { type: 'model/gltf-binary' }));
  const tooLarge = await request.post(productUrl, {
    headers: { Authorization: `Bearer ${adminToken}` },
    multipart: oversizedForm,
  });
  expect(tooLarge.status()).toBe(413);
  const failedUpload = await request.post(productUrl, {
    headers: { Authorization: `Bearer ${adminToken}` },
    multipart: multipart({ name: 'failed upload', description: '', category: '' }, fixtureRoot, fixtureBuffers, ['e2e-fail-upload.png']),
  });
  expect(failedUpload.status()).toBe(503);
  await expect((await request.get(`${apiUrl}/api/products`, { params: { q: 'failed upload' } })).json()).resolves.toMatchObject({ totalElements: 0 });

  const additional = [];
  for (let index = 1; index <= 12; index += 1) {
    const name = `Catalog E2E Product ${String(index).padStart(2, '0')}`;
    const response = await request.post(productUrl, {
      headers: { Authorization: `Bearer ${adminToken}` },
      multipart: multipart({ name, description: `TEST_ONLY ${name}`, category: 'Test Catalog' }, fixtureRoot, fixtureBuffers, ['catalog.png']),
    });
    expect(response.status()).toBe(201);
    const product = await response.json();
    expect(product.status).toBe('PENDING');
    expect(product.filePath3d).not.toBe(created.filePath3d);
    expect(product.modelImages[0].imagePath).not.toBe(created.modelImages[0].imagePath);
    additional.push(product);
    expect((await request.post(`${productUrl}/${product.id}/publish`, { headers: { Authorization: `Bearer ${adminToken}` } })).status()).toBe(204);
  }

  const published = await request.post(`${productUrl}/${created.id}/publish`, { headers: { Authorization: `Bearer ${adminToken}` } });
  expect(published.status()).toBe(204);
  expect((await request.get(`${apiUrl}/api/admin/products/${created.id}`, { headers: { Authorization: `Bearer ${adminToken}` } })).status()).toBe(200);
  const publicDetail = await request.get(`${apiUrl}/api/products/${created.id}`);
  expect(publicDetail.status()).toBe(200);
  expect(await publicDetail.json()).toMatchObject({ name: productName, category: 'Test Catalog Edited', description: 'TEST_ONLY edited product description' });

  await page.goto('/marketplace');
  await expect(page.locator('.joy-product-card__title')).toHaveCount(12);
  await expect(page.getByRole('navigation', { name: 'Phân trang sản phẩm' })).toContainText('Trang 1 / 2');
  await page.getByLabel('Sắp xếp').selectOption('name_asc');
  await expect(page.locator('.joy-product-card__title').first()).toHaveText('Catalog E2E Product 00');
  await page.getByLabel('Sắp xếp').selectOption('name_desc');
  await expect(page.locator('.joy-product-card__title').first()).toHaveText('Catalog E2E Product 12');
  await page.getByRole('button', { name: 'Tiếp' }).click();
  await expect(page).toHaveURL(/page=1/);
  await expect(page.locator('.joy-product-card__title')).toHaveCount(1);
  await expect(page.locator('.joy-product-card__title').first()).toHaveText(productName);
  await page.reload();
  await expect(page.locator('.joy-product-card__title').first()).toHaveText(productName);
  await page.getByLabel('Sắp xếp').selectOption('newest');

  await page.getByRole('searchbox', { name: 'Tìm sản phẩm' }).fill(productName);
  await page.getByRole('button', { name: 'Tìm kiếm' }).click();
  await expect(page.locator('.joy-product-card__title')).toHaveCount(1);
  await page.locator('.joy-product-card__title').click();
  await expect(page.getByRole('heading', { name: productName })).toBeVisible();
  await expect(page.locator('.joy-detail__description')).toHaveText('TEST_ONLY edited product description');
  await expect.poll(() => page.locator('.joy-detail__main-image').evaluate(image => image.naturalWidth)).toBeGreaterThan(0);
  await expect.poll(() => deliveredMedia.some(item => item.resource === 'image')).toBe(true);

  await page.getByRole('button', { name: 'Xem mô hình 3D' }).click();
  await expect(page.locator('.joy-detail__model-viewer canvas')).toBeVisible();
  await expect.poll(() => deliveredMedia.some(item => item.resource === 'raw')).toBe(true);
  await expect(page.getByText('Could not load the 3D model')).toHaveCount(0);
  mkdirSync(evidenceRoot, { recursive: true });
  await page.screenshot({ path: resolve(evidenceRoot, 'public-product-glb-viewer.png'), fullPage: false });

  await page.goto('/admin/products');
  const archiveRow = page.locator('tr').filter({ hasText: productName });
  page.once('dialog', dialog => dialog.accept());
  const archiveResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/admin/products/${created.id}/archive`);
  await archiveRow.getByRole('button', { name: 'Lưu trữ' }).click();
  expect((await archiveResponsePromise).status()).toBe(204);
  await page.getByLabel('Tìm theo tên').fill(productName);
  await page.getByLabel('Trạng thái').selectOption('INACTIVE');
  await page.getByRole('button', { name: 'Lọc danh sách' }).click();
  await expect(page.locator('tr').filter({ hasText: productName })).toContainText('Đã lưu trữ');
  await expect((await request.get(`${apiUrl}/api/products`, { params: { q: productName } })).json()).resolves.toMatchObject({ totalElements: 0 });
  expect((await request.get(`${apiUrl}/api/products/${created.id}`)).status()).toBe(404);

  const consoleErrors = [];
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  await expect(page.getByRole('heading', { name: 'Quản lý sản phẩm' })).toBeVisible();
  expect(consoleErrors).toEqual([]);
  expect(deliveredMedia.filter(item => item.resource === 'image').length).toBeGreaterThan(0);
  expect(deliveredMedia.filter(item => item.resource === 'raw').length).toBeGreaterThan(0);
});
