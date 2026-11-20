import test from 'node:test';
import assert from 'node:assert/strict';
import { adaptProductPage, adaptProductDetail, getCatalogState, getProductDetailState, getProductLoadErrorMessage, isProductNotFound } from './productApi.js';
import { createProductApi } from '../../services/api/productApiClient.js';

const summary = {
    id: 42,
    name: 'Desk charm',
    slug: 'desk-charm',
    description: 'A small public catalog item.',
    category: 'Accessories',
    thumbnailUrl: 'https://cdn.example.test/charm.webp',
};

test('adapts the backend page envelope and keeps catalog products non-purchasable', () => {
    const result = adaptProductPage({
        items: [summary], page: 1, size: 12, totalElements: 25, totalPages: 3,
    });

    assert.deepEqual(result, {
        items: [{
            id: 42,
            title: 'Desk charm',
            slug: 'desk-charm',
            description: 'A small public catalog item.',
            category: 'Accessories',
            visibility: 'public-approved',
            publicMedia: { images: [summary.thumbnailUrl], videoUrl: null, modelUrl: null },
        }],
        page: 1,
        size: 12,
        totalElements: 25,
        totalPages: 3,
    });
    assert.equal('priceNum' in result.items[0], false);
    assert.equal('readyForSale' in result.items[0], false);
    assert.equal('collections' in result.items[0], false);
});

test('preserves an empty backend page and rejects malformed pagination metadata', () => {
    assert.deepEqual(adaptProductPage({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 0 }), {
        items: [], page: 0, size: 12, totalElements: 0, totalPages: 0,
    });
    assert.throws(() => adaptProductPage({ items: [], page: 0, size: 12 }), /Invalid product page response/);
});

test('adapts only detail media and model fields actually supplied by the API', () => {
    const result = adaptProductDetail({
        ...summary,
        images: [{ id: 1, url: 'https://cdn.example.test/front.webp', primary: true }],
        model3dUrl: 'https://cdn.example.test/model.glb',
        sizeXMm: 10,
        sizeYMm: 20,
        sizeZMm: 30,
        modelParts: [{ name: 'Body', key: 'body' }],
    });
    assert.equal(result.title, 'Desk charm');
    assert.deepEqual(result.publicMedia.images, ['https://cdn.example.test/front.webp']);
    assert.equal(result.publicMedia.modelUrl, 'https://cdn.example.test/model.glb');
    assert.deepEqual(result.publicModelDimensions, { x: 10, y: 20, z: 30 });
    assert.deepEqual(result.publicModelParts, [{ id: 'body', name: 'Body' }]);
    assert.equal('priceNum' in result, false);
    assert.equal('verifiedReviews' in result, false);
});

test('product API requests use the central client and encode list parameters', async () => {
    const calls = [];
    const api = createProductApi({ get: async (...args) => { calls.push(args); return args[0] === '/products' ? { items: [], page: 0, size: 12, totalElements: 0, totalPages: 0 } : summary; } });

    await api.getProducts({ page: 2, size: 12, q: 'blue charm', sort: 'name_asc' });
    await api.getProduct(42);

    assert.deepEqual(calls, [
        ['/products', { params: { page: 2, size: 12, q: 'blue charm', sort: 'name_asc' }, signal: undefined }],
        ['/products/42', { signal: undefined }],
    ]);
});

test('product detail 404 is propagated to the customer detail state', async () => {
    const notFound = Object.assign(new Error('missing'), { response: { status: 404 } });
    const api = createProductApi({ get: async () => { throw notFound; } });
    await assert.rejects(api.getProduct(999), error => error.response.status === 404);
    assert.equal(isProductNotFound(notFound), true);
    assert.equal(getProductDetailState({ notFound: isProductNotFound(notFound) }), 'not-found');
});

test('catalog and detail distinguish loading, empty, and general error states', () => {
    assert.equal(getCatalogState({ loading: true, error: true }), 'loading');
    assert.equal(getCatalogState({ loading: false, error: true }), 'error');
    assert.equal(getCatalogState({ loading: false, error: false, items: [] }), 'empty');
    assert.equal(getProductDetailState({ loading: false, error: true }), 'error');
    assert.equal(getProductDetailState({ loading: false, product: null }), 'empty');
});

test('catalog and detail API failures name the affected resource', () => {
    assert.equal(getProductLoadErrorMessage(), 'Không thể tải danh mục sản phẩm. Vui lòng thử lại.');
    assert.equal(getProductLoadErrorMessage('detail'), 'Không thể tải thông tin sản phẩm. Vui lòng thử lại.');
});
