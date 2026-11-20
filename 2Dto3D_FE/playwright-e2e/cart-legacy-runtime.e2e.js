import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from '@playwright/test';
import { CART_QUARANTINE_KEY, CART_STORAGE_KEY } from '../src/context/cartStorage.js';

const artifactRoot = process.env.CART_QA_ARTIFACT_DIR
    || resolve(process.cwd(), '..', '.qa-temp', 'fe-cart-runtime-qa-20261108');
const screenshotRoot = resolve(artifactRoot, 'screenshots');
mkdirSync(screenshotRoot, { recursive: true });

const fixtureProductId = 'TEST_ONLY-CART-001';
const fixtureTitle = 'TEST_ONLY — Synthetic Cart QA Item';
const referenceUnitPrice = 13_000;
const errorsByPage = new WeakMap();
const networkByPage = new WeakMap();

function storedItem(overrides = {}) {
    return {
        key: `${fixtureProductId}-test-variant-v1-test-material-m1`,
        productId: fixtureProductId,
        title: 'TAMPERED LOCAL TITLE — MUST NOT BE TRUSTED',
        img: 'https://invalid.test/tampered-image.png',
        filePath3d: '/private/tampered-model.glb',
        priceNum: 1,
        materialId: 'test-material-m1',
        materialName: 'TAMPERED MATERIAL',
        materialPremium: 0,
        variantId: 'test-variant-v1',
        variantLabel: 'TAMPERED VARIANT',
        quantity: 2,
        ...overrides,
    };
}

async function seedCart(page, items) {
    await page.addInitScript(({ key, value }) => {
        if (localStorage.getItem(key) === null) localStorage.setItem(key, value);
    }, {
        key: CART_STORAGE_KEY,
        value: JSON.stringify(items),
    });
}

async function seedRaw(page, raw) {
    await page.addInitScript(({ key, value }) => {
        if (localStorage.getItem(key) === null) localStorage.setItem(key, value);
    }, {
        key: CART_STORAGE_KEY,
        value: raw,
    });
}

async function enableApiBlock(page) {
    await page.route('**/api/**', async route => {
        const request = route.request();
        const url = new URL(request.url());
        if (!url.pathname.startsWith('/api/')) {
            await route.continue();
            return;
        }
        networkByPage.get(page).apiRequests.push({ method: request.method(), path: url.pathname, blockedByQa: true });
        await route.abort('blockedbyclient');
    });
}

async function cartPage(page) {
    await page.goto('/cart');
    await expect(page.getByRole('heading', { name: 'Những món bạn đã chọn' })).toBeVisible();
}

test.beforeEach(async ({ page }) => {
    errorsByPage.set(page, []);
    networkByPage.set(page, { apiRequests: [] });
    page.on('pageerror', error => errorsByPage.get(page).push({ type: 'pageerror', message: error.message }));
    page.on('console', message => {
        if (message.type() === 'error') errorsByPage.get(page).push({ type: 'console', message: message.text() });
    });
});

test.afterEach(async ({ page }, testInfo) => {
    const safeName = testInfo.title.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();
    const consoleSummaryPath = resolve(artifactRoot, `browser-${safeName}.json`);
    writeFileSync(consoleSummaryPath, JSON.stringify({
        test: testInfo.title,
        status: testInfo.status,
        errors: errorsByPage.get(page) || [],
        apiRequests: networkByPage.get(page)?.apiRequests || [],
    }, null, 2));
});

test('QA-01/02: restores only fixture data, shows reference-price disclosure and safe media fallback', async ({ page }) => {
    await seedCart(page, [storedItem()]);
    await cartPage(page);

    const item = page.locator('.cart-item');
    await expect(item).toHaveCount(1);
    await expect(item.getByRole('heading', { name: fixtureTitle })).toBeVisible();
    await expect(item).toContainText('TEST_ONLY variant');
    await expect(item).toContainText('TEST_ONLY material');
    await expect(item).toContainText('13.000 ₫');
    await expect(item.getByLabel('Chưa có hình ảnh sản phẩm được duyệt')).toBeVisible();
    await expect(item).not.toContainText('TAMPERED');
    await expect(item.locator('img')).toHaveCount(0);

    const active = JSON.parse(await page.evaluate(key => localStorage.getItem(key), CART_STORAGE_KEY));
    expect(active[0]).toMatchObject({
        productId: fixtureProductId,
        title: fixtureTitle,
        variantId: 'test-variant-v1',
        variantLabel: 'TEST_ONLY variant',
        materialId: 'test-material-m1',
        materialName: 'TEST_ONLY material',
        quantity: 2,
        priceNum: 12_345,
        materialPremium: 655,
        img: null,
        filePath3d: null,
    });
    await expect(page.getByText('Tổng giá tham khảo', { exact: true })).toBeVisible();
    await expect(page.locator('.cart-summary__row strong')).toHaveText('26.000 ₫');
    await expect(page.locator('.cart-summary__total strong')).toHaveText('26.000 ₫');
    await expect(page.getByText(/Tổng tham khảo — chưa phải số tiền phải thanh toán/)).toBeVisible();
    await expect(page.getByText('Phí vận chuyển chưa được xác định.')).toBeVisible();
    await expect(page.getByText(/báo giá chính thức chưa được hệ thống xác nhận/)).toBeVisible();
    await expect(page.getByText(/Đặt hàng và thanh toán trực tuyến hiện chưa được hỗ trợ/)).toBeVisible();
});

test('QA-03/04: quantity changes by keyboard, persists across reload and zero removes item', async ({ page }) => {
    await seedCart(page, [storedItem()]);
    await cartPage(page);

    const increase = page.getByRole('button', { name: 'Tăng số lượng' });
    await increase.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.cart-quantity')).toContainText('3');
    await expect(page.locator('.cart-summary__row strong')).toHaveText('39.000 ₫');
    await expect(page.locator('.cart-summary__total strong')).toHaveText('39.000 ₫');
    await expect.poll(async () => JSON.parse(await page.evaluate(key => localStorage.getItem(key), CART_STORAGE_KEY))[0].quantity).toBe(3);

    await page.reload();
    await expect(page.locator('.cart-quantity')).toContainText('3');
    await page.getByRole('button', { name: 'Giảm số lượng' }).click();
    await expect(page.locator('.cart-quantity')).toContainText('2');
    await page.getByRole('button', { name: 'Giảm số lượng' }).click();
    await expect(page.locator('.cart-quantity')).toContainText('1');
    await page.getByRole('button', { name: 'Giảm số lượng' }).click();
    await expect(page.getByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeVisible();
    await expect.poll(async () => page.evaluate(key => localStorage.getItem(key), CART_STORAGE_KEY)).toBe('[]');
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeVisible();
});

test('QA-05: remove persists the empty state across reload', async ({ page }) => {
    await seedCart(page, [storedItem()]);
    await cartPage(page);
    await page.getByRole('button', { name: `Xóa ${fixtureTitle} khỏi giỏ` }).click();
    await expect(page.getByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeVisible();
    await expect.poll(async () => page.evaluate(key => localStorage.getItem(key), CART_STORAGE_KEY)).toBe('[]');
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeVisible();
});

test('QA-06: recalculated local reference price is disclosed as non-payable', async ({ page }) => {
    await seedCart(page, [storedItem({ quantity: 1 })]);
    await cartPage(page);
    await expect(page.getByRole('status')).toContainText('tính lại theo danh mục cục bộ');
    await expect(page.getByRole('status')).toContainText('không phải báo giá do Backend xác nhận hoặc số tiền phải thanh toán');
    await expect(page.locator('.cart-item')).toContainText('13.000 ₫');
    await expect(page.locator('.cart-summary__row strong')).toHaveText(`${referenceUnitPrice.toLocaleString('vi-VN')} ₫`);
});

test('QA-07: invalid item is removed from active Cart and retained in bounded quarantine', async ({ page }) => {
    await seedCart(page, [storedItem({ productId: 'TEST_ONLY-OBSOLETE-999' })]);
    await cartPage(page);
    await expect(page.getByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeVisible();
    await expect(page.getByRole('status')).toContainText('đã được loại khỏi giỏ hoạt động');
    const active = await page.evaluate(key => localStorage.getItem(key), CART_STORAGE_KEY);
    const quarantine = JSON.parse(await page.evaluate(key => localStorage.getItem(key), CART_QUARANTINE_KEY));
    expect(active).toBe('[]');
    expect(quarantine.entries).toHaveLength(1);
    expect(quarantine.entries[0].raw).toContain('TEST_ONLY-OBSOLETE-999');
    expect(quarantine.entries.length).toBeLessThanOrEqual(3);
    expect(new TextEncoder().encode(JSON.stringify(quarantine.entries[0])).length).toBeLessThanOrEqual(64 * 1024);
});

test('QA-08: malformed JSON is quarantined and source bytes are retained in recovery record', async ({ page }) => {
    const malformed = '{"testOnly":"broken-json"';
    await seedRaw(page, malformed);
    await cartPage(page);
    await expect(page.getByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeVisible();
    await expect(page.getByRole('status')).toContainText('không hợp lệ');
    const quarantine = JSON.parse(await page.evaluate(key => localStorage.getItem(key), CART_QUARANTINE_KEY));
    expect(quarantine.entries).toHaveLength(1);
    expect(quarantine.entries[0].raw).toBe(malformed);
});

test('QA-08: quarantine write denial blocks migration and preserves original malformed bytes', async ({ page }) => {
    const malformed = 'TEST_ONLY original bytes — malformed';
    await page.addInitScript(({ cartKey, raw, quarantineKey }) => {
        localStorage.setItem(cartKey, raw);
        const originalSetItem = Storage.prototype.setItem;
        Object.defineProperty(Storage.prototype, 'setItem', {
            configurable: true,
            value(key, value) {
                if (key === quarantineKey) throw new DOMException('TEST_ONLY storage denied', 'QuotaExceededError');
                return originalSetItem.call(this, key, value);
            },
        });
    }, { cartKey: CART_STORAGE_KEY, raw: malformed, quarantineKey: CART_QUARANTINE_KEY });
    await cartPage(page);
    await expect(page.getByRole('status')).toContainText('chưa sao lưu được');
    await expect(page.getByRole('status')).toContainText('Dữ liệu gốc vẫn được giữ nguyên');
    await expect(page.getByRole('button', { name: 'Xóa dữ liệu giỏ cũ và lưu giỏ đã xác thực' })).toBeVisible();
    expect(await page.evaluate(key => localStorage.getItem(key), CART_STORAGE_KEY)).toBe(malformed);
    expect(await page.evaluate(key => localStorage.getItem(key), CART_QUARANTINE_KEY)).toBeNull();
});

test('QA-08: active Cart write denial is reported without changing the seeded legacy bytes', async ({ page }) => {
    const legacy = JSON.stringify([storedItem()]);
    await page.addInitScript(({ cartKey, raw }) => {
        localStorage.setItem(cartKey, raw);
        const originalSetItem = Storage.prototype.setItem;
        Object.defineProperty(Storage.prototype, 'setItem', {
            configurable: true,
            value(key, value) {
                if (key === cartKey) throw new DOMException('TEST_ONLY storage denied', 'QuotaExceededError');
                return originalSetItem.call(this, key, value);
            },
        });
    }, { cartKey: CART_STORAGE_KEY, raw: legacy });
    await cartPage(page);
    await expect(page.getByRole('status')).toContainText('Không thể lưu thay đổi giỏ hàng trên thiết bị này.');
    expect(await page.evaluate(key => localStorage.getItem(key), CART_STORAGE_KEY)).toBe(legacy);
});

test('QA-09: desktop, tablet and mobile layouts have no horizontal overflow and controls remain usable', async ({ page }) => {
    await seedCart(page, [storedItem()]);
    await cartPage(page);
    const viewports = [
        { name: 'desktop', width: 1440, height: 900 },
        { name: 'tablet', width: 768, height: 1024 },
        { name: 'mobile', width: 390, height: 844 },
    ];

    for (const viewport of viewports) {
        await page.setViewportSize({ width: viewport.width, height: viewport.height });
        await expect.poll(async () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        await expect(page.getByRole('region', { name: 'Sản phẩm trong giỏ' })).toBeVisible();
        await expect(page.getByRole('complementary', { name: 'Tóm tắt giỏ hàng' })).toBeVisible();
        await expect(page.getByRole('button', { name: 'Giảm số lượng' })).toBeVisible();
        await expect(page.getByRole('button', { name: 'Tăng số lượng' })).toBeVisible();
        await expect(page.getByText(/Tổng tham khảo — chưa phải số tiền phải thanh toán/)).toBeVisible();
        await page.screenshot({ path: resolve(screenshotRoot, `test-only-cart-${viewport.name}.png`), fullPage: true });
    }

    const increase = page.getByRole('button', { name: 'Tăng số lượng' });
    await increase.focus();
    await expect(increase).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('.cart-quantity')).toContainText('3');
});

test('QA-10: checkout remains unavailable and no API request is made', async ({ page }) => {
    await seedCart(page, [storedItem()]);
    await enableApiBlock(page);
    await cartPage(page);
    await page.getByRole('link', { name: /Xem thông tin đặt hàng/ }).click();
    await expect(page).toHaveURL(/\/checkout$/);
    await expect(page.getByRole('heading', { name: 'Trang không tồn tại' })).toBeVisible();
    expect(networkByPage.get(page).apiRequests).toEqual([]);
});

test('fixture is loaded only through the isolated Cart transform and does not populate Marketplace or Product Detail', async ({ page }) => {
    await enableApiBlock(page);
    await page.goto('/cart');
    const statusResponse = await page.request.get('/__cart-qa__/status');
    expect(statusResponse.ok()).toBeTruthy();
    const status = await statusResponse.json();
    expect(status).toMatchObject({
        mode: 'cart-qa',
        cartContextPath: 'src/context/CartContext.jsx',
        fixtureImportPath: 'playwright-e2e/fixtures/cartTestProducts.js',
    });
    expect(status.transformedCartContextCount).toBeGreaterThan(0);
    const fixtureResponse = await page.request.get('/playwright-e2e/fixtures/cartTestProducts.js');
    expect(fixtureResponse.ok()).toBeTruthy();
    const fixtureSource = await fixtureResponse.text();
    expect(fixtureSource).toContain('__FE_CART_RUNTIME_QA_ONLY_V1__');
    expect(fixtureSource).toContain(fixtureTitle);

    await page.goto('/marketplace');
    await expect(page.getByRole('heading', { name: fixtureTitle })).toHaveCount(0);
    await expect(page.getByText(fixtureTitle, { exact: true })).toHaveCount(0);
    await page.goto(`/product/${fixtureProductId}`);
    await expect(page.getByText(fixtureTitle, { exact: true })).toHaveCount(0);
    expect(networkByPage.get(page).apiRequests.every(request => request.method === 'GET' && request.blockedByQa)).toBe(true);
    writeFileSync(resolve(artifactRoot, 'test-fixture-runtime-status.json'), JSON.stringify(status, null, 2));
});
