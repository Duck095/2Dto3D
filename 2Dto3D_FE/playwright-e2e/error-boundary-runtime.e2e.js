import { expect, test } from '@playwright/test';

test('outer ErrorBoundary renders and navigates without RouterProvider after a render exception', async ({ page }) => {
  const routerContextErrors = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && /Router context|useNavigate|useHref|useInRouterContext/i.test(message.text())) {
      routerContextErrors.push(message.text());
    }
  });

  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.goto('/__dev/error-boundary');
  await expect(page.getByRole('heading', { name: 'Oops! Hệ thống đang gặp trục trặc nhỏ!' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Thử lại ngay' })).toBeVisible();
  await expect(page.getByText('intentional render failure', { exact: false })).toHaveCount(0);
  await page.waitForTimeout(800);
  expect(routerContextErrors).toEqual([]);
  await page.screenshot({ path: '.qa-evidence/POPART-FE-ERROR-PAGES-4IN1-01/error-boundary-runtime-1440x1080.png', fullPage: true });
  await page.getByRole('button', { name: 'Thử lại ngay' }).click();
  await expect(page.getByRole('heading', { name: 'Oops! Hệ thống đang gặp trục trặc nhỏ!' })).toBeVisible();
  expect(routerContextErrors).toEqual([]);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Oops! Hệ thống đang gặp trục trặc nhỏ!' })).toBeVisible();
  await page.waitForTimeout(800);
  await page.screenshot({ path: '.qa-evidence/POPART-FE-ERROR-PAGES-4IN1-01/error-boundary-runtime-390x844.png', fullPage: true });

  await page.getByRole('link', { name: /Quay về trang chủ/ }).click();
  await expect(page).toHaveURL('/');
});

test('unauthenticated internal routes still redirect to login', async ({ page }) => {
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: 'Tiếp tục hành trình sáng tạo' })).toBeVisible();
});

test('unavailable screens omit retry controls when no safe retry action is configured', async ({ page }) => {
  await page.goto('/__dev/error-pages?type=service-unavailable');
  await expect(page.getByRole('heading', { name: 'Hệ thống hiện chưa thể phục vụ bạn!' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Kiểm tra lại' })).toHaveCount(0);

  await page.goto('/__dev/error-pages?type=maintenance');
  await expect(page.getByRole('heading', { name: 'Hệ thống đang được bảo trì!' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Kiểm tra lại' })).toHaveCount(0);

  await page.goto('/__dev/error-pages?type=system-error-no-retry');
  await expect(page.getByRole('heading', { name: 'Oops! Hệ thống đang gặp trục trặc nhỏ!' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Thử lại ngay' })).toHaveCount(0);
});
