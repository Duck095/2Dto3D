import { test, expect } from '@playwright/test';
import { writeFile } from 'node:fs/promises';

async function signIn(page, email, password) {
  await page.goto('/login');
  await page.getByLabel('Địa chỉ email').fill(email);
  await page.locator('#auth-password').fill(password);
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  await expect(page.getByRole('button', { name: 'Tài khoản' })).toBeVisible();
  await expect(page).not.toHaveURL(/\/login$/);
}

test('live Backend checkout creates a TEST_ONLY VietQR attempt without rendering fake payment data', async ({ page }) => {
  const providerRequests = [];
  page.on('request', request => {
    if (/payos\.vn/i.test(request.url())) providerRequests.push(request.url());
  });
  await signIn(page, process.env.PAYMENT_E2E_EMAIL, process.env.PAYMENT_E2E_PASSWORD);
  await page.goto('/checkout');
  await expect(page.getByText('1 mục trong giỏ Backend.')).toBeVisible();
  await page.getByRole('button', { name: 'Tạo đơn hàng từ giỏ' }).click();
  await expect(page.getByText(/Backend đã ghi nhận đơn hàng/)).toBeVisible();
  await expect(page.getByText('Phí vận chuyển', { exact: true })).toBeVisible();
  await expect(page.getByText('Tổng phải thanh toán', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Thanh toán bằng VietQR' })).toBeEnabled();
  await page.getByRole('button', { name: 'Thanh toán bằng VietQR' }).click();
  await expect(page).toHaveURL(/\/payment-result\?attemptId=[0-9a-f-]+/i);
  await expect(page.getByText('Đang chờ xác nhận từ nhà cung cấp')).toBeVisible();
  await expect(page.getByText(/TEST_ONLY; không dùng mã này để chuyển tiền/)).toBeVisible();
  await expect(page.locator('.payos-payment__qr')).toHaveCount(0);
  await expect(page.locator('.payos-payment a')).toHaveCount(0);
  expect(providerRequests).toEqual([]);
  const attemptId = new URL(page.url()).searchParams.get('attemptId');
  await writeFile(process.env.PAYMENT_ATTEMPT_MARKER, JSON.stringify({ attemptId }));
});

test('wrong owner sees no attempt data and logout/login refreshes success from Backend', async ({ page }) => {
  const attemptId = process.env.PAYMENT_E2E_ATTEMPT_ID;
  await signIn(page, process.env.PAYMENT_E2E_OTHER_EMAIL, process.env.PAYMENT_E2E_OTHER_PASSWORD);
  await page.goto(`/payment-result?attemptId=${encodeURIComponent(attemptId)}`);
  await expect(page.getByText(/Backend chưa trả về trạng thái đã xác minh|Chưa thể xác minh trạng thái thanh toán từ Backend/)).toBeVisible();
  await expect(page.getByText('Backend xác nhận thanh toán thành công')).toHaveCount(0);
  await expect(page.locator('.payos-payment__qr')).toHaveCount(0);

  await page.getByRole('button', { name: 'Tài khoản' }).click();
  await page.getByRole('button', { name: 'Đăng xuất' }).click();
  await signIn(page, process.env.PAYMENT_E2E_EMAIL, process.env.PAYMENT_E2E_PASSWORD);
  await page.goto(`/payment-result?attemptId=${encodeURIComponent(attemptId)}`);
  await expect(page.getByText('Backend xác nhận thanh toán thành công')).toBeVisible();
  await expect(page.locator('.payos-payment__qr')).toHaveCount(0);
  await page.screenshot({ path: process.env.PAYMENT_E2E_SUCCESS_SCREENSHOT, fullPage: true });
});
