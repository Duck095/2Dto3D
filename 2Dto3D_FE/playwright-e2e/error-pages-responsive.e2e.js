import { expect, test } from '@playwright/test';

const viewports = [
  { width: 320, height: 568, name: '320x568' },
  { width: 375, height: 667, name: '375x667' },
  { width: 390, height: 844, name: '390x844' },
  { width: 430, height: 932, name: '430x932' },
  { width: 667, height: 375, name: '667x375' },
  { width: 768, height: 1024, name: '768x1024' },
  { width: 1024, height: 768, name: '1024x768' },
  { width: 1366, height: 768, name: '1366x768' },
  { width: 1440, height: 900, name: '1440x900' },
  { width: 1920, height: 1080, name: '1920x1080' },
  { width: 2560, height: 1440, name: '2560x1440' },
];

const pages = [
  { type: 'forbidden', title: /Khu vực này chưa mở cho bạn/ },
  { type: 'not-found', title: /Không tìm thấy trang này!/ },
  { type: 'system-error', title: /Hệ thống đang gặp trục trặc nhỏ!/ },
  { type: 'service-unavailable', title: /Hệ thống hiện chưa thể phục vụ bạn!/ },
];

const screenshotCases = new Set([
  'forbidden-1024x768',
  'forbidden-390x844',
  'forbidden-1440x900',
  'not-found-320x568',
  'not-found-390x844',
  'not-found-1440x900',
  'system-error-667x375',
  'system-error-390x844',
  'system-error-1440x900',
  'service-unavailable-768x1024',
  'service-unavailable-390x844',
  'service-unavailable-1440x900',
  'not-found-2560x1440',
]);

test('all four Pop Art pages reflow without clipping across the requested viewport matrix', async ({ page }) => {
  const defects = [];
  for (const viewport of viewports) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    for (const errorPage of pages) {
      await page.goto(`/__dev/error-pages?type=${errorPage.type}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: errorPage.title })).toBeVisible();
      await expect(page.locator('.pop-error__art img')).toHaveJSProperty('complete', true);

      const metrics = await page.evaluate(() => {
        const root = document.documentElement;
        const main = document.querySelector('.pop-error');
        const art = document.querySelector('.pop-error__art').getBoundingClientRect();
        const copy = document.querySelector('.pop-error__content').getBoundingClientRect();
        const grid = getComputedStyle(document.querySelector('.pop-error__layout'));
        const buttons = [...document.querySelectorAll('.pop-error__button')].map((button) => {
          const rect = button.getBoundingClientRect();
          const style = getComputedStyle(button);
          return { x: rect.x, right: rect.right, width: rect.width, height: rect.height, display: style.display, visibility: style.visibility };
        });
        return {
          viewportWidth: innerWidth,
          documentWidth: root.scrollWidth,
          bodyWidth: document.body.scrollWidth,
          rootOverflowX: getComputedStyle(root).overflowX,
          bodyOverflowX: getComputedStyle(document.body).overflowX,
          mainRight: main.getBoundingClientRect().right,
          columns: grid.gridTemplateColumns.split(' ').length,
          art,
          copy,
          buttons,
          imageWidth: document.querySelector('.pop-error__art img').naturalWidth,
        };
      });

      if (metrics.documentWidth > viewport.width || metrics.bodyWidth > viewport.width || metrics.mainRight > viewport.width + 1) {
        defects.push(`${errorPage.type} ${viewport.name}: horizontal overflow ${JSON.stringify(metrics)}`);
      }
      if (metrics.rootOverflowX === 'hidden' || metrics.rootOverflowX === 'clip' || metrics.bodyOverflowX === 'hidden' || metrics.bodyOverflowX === 'clip') {
        defects.push(`${errorPage.type} ${viewport.name}: horizontal clipping is hiding overflow ${JSON.stringify(metrics)}`);
      }
      if (viewport.width <= 768 && metrics.columns !== 1) {
        defects.push(`${errorPage.type} ${viewport.name}: narrow/tablet layout is not stacked (${metrics.columns} columns)`);
      }
      if (viewport.width >= 1024 && metrics.columns !== 2) {
        defects.push(`${errorPage.type} ${viewport.name}: wide layout lost its two-column composition (${metrics.columns} columns)`);
      }
      if (viewport.width <= 900 && metrics.art.bottom > metrics.copy.top + 1) {
        defects.push(`${errorPage.type} ${viewport.name}: art overlaps the copy`);
      }
      if (!metrics.imageWidth || metrics.buttons.some((button) => button.width < 44 || button.height < 44 || button.display === 'none' || button.visibility === 'hidden' || button.x < -1 || button.right > viewport.width + 1)) {
        defects.push(`${errorPage.type} ${viewport.name}: artwork or CTA is unavailable/outside the viewport ${JSON.stringify(metrics)}`);
      }

      const screenshotKey = `${errorPage.type}-${viewport.name}`;
      if (screenshotCases.has(screenshotKey)) {
        await page.waitForTimeout(750);
        await page.screenshot({
          path: `.qa-evidence/POPART-FE-RESPONSIVE-CORRECTION-01/after-${screenshotKey}.png`,
          fullPage: true,
        });
      }
    }
  }
  expect(defects, defects.join('\n')).toEqual([]);
});

test('200 percent zoom, keyboard focus and reduced motion remain usable', async ({ page }) => {
  await page.setViewportSize({ width: 640, height: 900 });
  await page.goto('/__dev/error-pages?type=not-found', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  await expect(page.getByRole('heading', { name: /Không tìm thấy trang này!/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /Quay về trang chủ/ })).toBeVisible();
  const zoomMetrics = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth }));
  expect(zoomMetrics.scrollWidth).toBeLessThanOrEqual(zoomMetrics.viewportWidth);

  await page.evaluate(() => { document.documentElement.style.zoom = '1'; });
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  const focusStyle = await page.evaluate(() => ({
    label: document.activeElement?.innerText,
    outline: getComputedStyle(document.activeElement).outlineStyle,
    focusVisible: document.activeElement?.matches(':focus-visible'),
  }));
  expect(focusStyle.focusVisible).toBe(true);
  expect(focusStyle.outline).not.toBe('none');

  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(page.locator('.pop-error__art img')).toHaveCSS('animation-name', 'none');
});
