import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./ProductDetailPage.jsx', import.meta.url), 'utf8');
const api = await readFile(new URL('../features/commerce/commerceApi.js', import.meta.url), 'utf8');
const cart = await readFile(new URL('./CartPage.jsx', import.meta.url), 'utf8');

test('product commerce uses backend slicing options and estimate price only', () => {
    assert.match(page, /commerceApi\.getEstimateOptions/);
    assert.match(page, /commerceApi\.createEstimate/);
    assert.match(page, /commerceApi\.addCartItem/);
    assert.match(page, /estimate\?\.totalPriceVnd/);
    assert.match(page, /config\.features\.printQuote && config\.features\.cart/);
    assert.doesNotMatch(page, /priceNum|materialPremium/);
    assert.match(page, /setQuoteOptions\(\[\]\);\s*setSelection\(''\);\s*setQuoteMessage\(''\);\s*commerceApi\.getEstimateOptions/);
    assert.match(page, /user\?\.id, user\?\.email/);
    assert.match(page, /modelErrorMessage="Không thể tải bản xem trước 3D cho sản phẩm này/);
});

test('commerce API does not accept a client calculated amount', () => {
    assert.match(api, /configurationFingerprint/);
    assert.match(api, /createEstimate\(\{ modelId, materialId, printerId, configurationFingerprint \}\)/);
    assert.doesNotMatch(api, /totalPrice|priceVnd/);
});

test('Backend cart view is additive and leaves device cart state untouched', () => {
    assert.match(cart, /commerceApi\.getCart/);
    assert.match(cart, /Dữ liệu giỏ trên thiết bị vẫn được giữ nguyên/);
    assert.match(cart, /useCart\(\)/);
});
