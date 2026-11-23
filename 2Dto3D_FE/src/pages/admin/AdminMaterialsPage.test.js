import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('Admin materials page provides color/material create and update controls with recoverable errors and empty states', async () => {
  const source = await readFile(new URL('./AdminMaterialsPage.jsx', import.meta.url), 'utf8');
  assert.match(source, /listAdminColors/);
  assert.match(source, /createAdminColor/);
  assert.match(source, /createAdminMaterial/);
  assert.match(source, /updateAdminMaterial/);
  assert.match(source, /colorIds|selectedColorIds/);
  assert.match(source, /role="alert"/);
  assert.match(source, /Chưa có màu/);
  assert.match(source, /Chưa có vật liệu/);
  assert.doesNotMatch(source, /Chỉ xem\./);
});
