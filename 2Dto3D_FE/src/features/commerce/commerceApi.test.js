import assert from 'node:assert/strict';
import test from 'node:test';
import { createCommerceApi } from './commerceApi.js';

test('checkout reads the Backend cart and creates an order without a client total', async () => {
    const calls = [];
    const client = {
        get: async (...args) => { calls.push(['get', ...args]); return { items: [] }; },
        post: async (...args) => { calls.push(['post', ...args]); return { id: 41, status: 'PENDING' }; },
    };
    const api = createCommerceApi(client);

    const cart = await api.getCart();
    const order = await api.createOrder();

    assert.deepEqual(cart, { items: [] });
    assert.deepEqual(order, { id: 41, status: 'PENDING' });
    assert.deepEqual(calls, [
        ['get', '/cart'],
        ['post', '/orders'],
    ]);
});

test('estimate and cart requests send only server-owned selection identifiers', async () => {
    const calls = [];
    const api = createCommerceApi({
        get: async () => ({}),
        post: async (...args) => { calls.push(args); return { estimateId: 7 }; },
    });

    await api.createEstimate({ modelId: 21, materialId: 4, printerId: 3, configurationFingerprint: 'cfg-sha256', priceVnd: 1 });
    await api.addCartItem({ modelId: 21, estimateId: 7, quantity: 2, totalPrice: 1 });

    assert.deepEqual(calls, [
        ['/price-estimates', { modelId: 21, materialId: 4, printerId: 3, configurationFingerprint: 'cfg-sha256' }],
        ['/cart/items', { modelId: 21, estimateId: 7, quantity: 2 }],
    ]);
});

test('estimate and cart reject client-supplied invalid identifiers', () => {
    const api = createCommerceApi({ get: async () => ({}), post: async () => ({}) });
    assert.throws(() => api.createEstimate({ modelId: 0, materialId: 4, printerId: 3 }), TypeError);
    assert.throws(() => api.createEstimate({ modelId: 1, materialId: 2, printerId: 3 }), TypeError);
    assert.throws(() => api.addCartItem({ modelId: 21, estimateId: -1 }), TypeError);
});

test('estimate options are read from Backend and selection sends the exact configuration fingerprint', async () => {
    const calls = [];
    const api = createCommerceApi({
        get: async (...args) => { calls.push(['get', ...args]); return { options: [] }; },
        post: async (...args) => { calls.push(['post', ...args]); return { estimateId: 8 }; },
    });
    await api.getEstimateOptions('21');
    await api.createEstimate({ modelId: 21, materialId: 4, printerId: 3, configurationFingerprint: 'cfg-21' });
    assert.deepEqual(calls, [
        ['get', '/price-estimates/options', { params: { modelId: 21 } }],
        ['post', '/price-estimates', { modelId: 21, materialId: 4, printerId: 3, configurationFingerprint: 'cfg-21' }],
    ]);
});

test('payment attempt contract sends no amount and carries an idempotency key', async () => {
    const calls = [];
    const api = createCommerceApi({
        get: async (...args) => { calls.push(['get', ...args]); return { eligible: true }; },
        post: async (...args) => { calls.push(['post', ...args]); return { status: 'PENDING' }; },
    });

    await api.getPaymentReadiness(41);
    const config = { signal: new AbortController().signal };
    await api.createPaymentAttempt(41, 'BANK_TRANSFER', 'checkout:41:bank-transfer:key-1', config);
    await api.getPaymentAttempt('attempt-uuid', config);

    assert.deepEqual(calls, [
        ['get', '/payments/readiness', { params: { orderId: 41 } }],
        ['post', '/payments/attempts', { orderId: 41, method: 'BANK_TRANSFER' }, { signal: config.signal, headers: { 'Idempotency-Key': 'checkout:41:bank-transfer:key-1' } }],
        ['get', '/payments/attempts/attempt-uuid', config],
    ]);
});

test('payment attempt contract rejects missing idempotency and invalid attempt identity', () => {
    const api = createCommerceApi({ get: async () => ({}), post: async () => ({}) });
    assert.throws(() => api.createPaymentAttempt(41, 'MOMO', 'key-1'), TypeError);
    assert.throws(() => api.createPaymentAttempt(41, 'BANK_TRANSFER', ''), TypeError);
    assert.throws(() => api.getPaymentAttempt(''), TypeError);
});
