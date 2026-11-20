import { expect, test } from '@playwright/test';

const illustrations = [
  'forbidden.webp',
  'not-found.webp',
  'system-error.webp',
  'service-unavailable.webp',
];

test('production preview serves all four approved Pop Art illustrations', async ({ page, request, baseURL }) => {
  for (const filename of illustrations) {
    const assetUrl = `${baseURL}/images/error-pages/${filename}`;
    const response = await request.get(assetUrl);
    expect(response.ok(), `${filename} should be served by the production preview`).toBe(true);
    expect(response.headers()['content-type']).toContain('image/webp');

    await page.setContent(`<img alt="${filename}" src="${assetUrl}">`);
    const illustration = page.getByRole('img', { name: filename });
    await expect(illustration).toHaveJSProperty('complete', true);
    await expect.poll(() => illustration.evaluate((image) => image.naturalWidth)).toBeGreaterThan(0);
  }
});
