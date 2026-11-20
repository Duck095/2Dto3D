import { expect, test } from '@playwright/test';

test('desktop Full Feature navigation reaches restored public pages without browser errors', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/');

  for (const group of ['AI Studio', 'Hướng dẫn', 'Dịch vụ in 3D', 'Giỏ hàng & đặt hàng']) {
    await expect(page.getByRole('button', { name: group })).toBeVisible();
  }

  await page.getByRole('button', { name: 'AI Studio' }).click();
  await page.getByRole('link', { name: /Xem và tùy chỉnh mô hình 3D/ }).first().click();
  await expect(page.locator('.ai-studio-page-shell')).toBeVisible();

  await page.goto('/workflow');
  await expect(page.getByRole('heading', { name: 'Từ ý tưởng đến mô hình 3D' })).toBeVisible();
  await page.goto('/resources');
  await expect(page.getByRole('heading', { name: 'Tài nguyên hướng dẫn' })).toBeVisible();
  await page.goto('/cart');
  await expect(page.getByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeVisible();
  await page.goto('/checkout');
  await expect(page.getByRole('heading', { name: 'Xác nhận đơn hàng' })).toBeVisible();
  await page.goto('/print-quote');
  await expect(page.getByRole('heading', { name: 'Chưa có dữ liệu mô hình hợp lệ' })).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('mobile Full Feature navigation exposes guide links and closes after navigation', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.getByRole('button', { name: 'Mở menu' }).click();
  await expect(page.getByRole('navigation', { name: 'Điều hướng trên thiết bị di động' })).toBeVisible();
  await page.getByRole('button', { name: 'Hướng dẫn' }).click();
  await page.getByRole('link', { name: 'Tài nguyên hướng dẫn' }).click();
  await expect(page.getByRole('heading', { name: 'Tài nguyên hướng dẫn' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Mở menu' })).toHaveAttribute('aria-expanded', 'false');
  expect(pageErrors).toEqual([]);
});
