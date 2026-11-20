import assert from 'node:assert/strict';
import test from 'node:test';
import { clearResolvedPaymentRetry, getOrCreatePaymentIdempotencyKey, getPaymentAttemptForSession, getPayosPaymentForDisplay, pollPaymentAttempt, prepareAllowedPaymentRetry } from './paymentAttemptPresentation.js';

function createStorage() {
    const values = new Map();
    return {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value),
        removeItem: key => values.delete(key),
    };
}

test('payment idempotency key remains stable for the same order and method', () => {
    const storage = createStorage();
    let generated = 0;
    const createKey = () => `payment-key-${++generated}`;

    assert.equal(getOrCreatePaymentIdempotencyKey(storage, 17, 'BANK_TRANSFER', createKey), 'payment-key-1');
    assert.equal(getOrCreatePaymentIdempotencyKey(storage, 17, 'BANK_TRANSFER', createKey), 'payment-key-1');
    assert.equal(generated, 1);
});

test('a new payment key is issued only after Backend allows retry from a terminal attempt', () => {
    const storage = createStorage();
    let generated = 0;
    const createKey = () => `payment-key-${++generated}`;
    const oldKey = getOrCreatePaymentIdempotencyKey(storage, 17, 'BANK_TRANSFER', createKey);

    const attempt = { attemptId: 'attempt-1', orderId: 17, provider: 'PAYOS', status: 'FAILED', retryAllowed: true };
    assert.equal(prepareAllowedPaymentRetry(storage, { ...attempt, status: 'UNKNOWN' }, createKey), null);
    assert.equal(prepareAllowedPaymentRetry(storage, { ...attempt, retryAllowed: false }, createKey), null);
    assert.equal(prepareAllowedPaymentRetry(storage, attempt, createKey), 'payment-key-2');
    assert.equal(prepareAllowedPaymentRetry(storage, attempt, createKey), 'payment-key-2');
    assert.notEqual(oldKey, 'payment-key-2');
    clearResolvedPaymentRetry(storage, attempt.attemptId, attempt.orderId, attempt.provider);
    assert.equal(storage.getItem('2dto3d.payment.idempotency:17:BANK_TRANSFER:retry:attempt-1'), null);
});

test('attempt key storage validates owner order and provider inputs', () => {
    assert.throws(() => getOrCreatePaymentIdempotencyKey(createStorage(), 0, 'BANK_TRANSFER', () => 'key'), TypeError);
    assert.throws(() => getOrCreatePaymentIdempotencyKey(createStorage(), 17, 'COD', () => 'key'), TypeError);
    assert.throws(() => getOrCreatePaymentIdempotencyKey(createStorage(), 17, 'MOMO', () => 'key'), TypeError);
});

test('payment result state is hidden after logout or an account session change', () => {
    const priorOwnerSnapshot = { sessionVersion: 4, attempt: { attemptId: 'owner-a-attempt', status: 'SUCCEEDED' } };
    assert.equal(getPaymentAttemptForSession(priorOwnerSnapshot, 5, true, 'owner-a-attempt'), null);
    assert.equal(getPaymentAttemptForSession(priorOwnerSnapshot, 4, false, 'owner-a-attempt'), null);
    assert.equal(getPaymentAttemptForSession(priorOwnerSnapshot, 4, true, 'owner-b-attempt'), null);
    assert.deepEqual(getPaymentAttemptForSession(priorOwnerSnapshot, 4, true, 'owner-a-attempt'), priorOwnerSnapshot.attempt);
});

test('unknown result polling is bounded and stops immediately after trusted terminal status', async () => {
    const delays = [];
    const results = ['UNKNOWN', 'UNKNOWN', 'SUCCEEDED'];
    const observed = [];
    const result = await pollPaymentAttempt(
        async () => ({ status: results[observed.push(results[observed.length]) - 1] }),
        'attempt-1',
        { aborted: false },
        async delay => { delays.push(delay); },
        [100, 200],
    );

    assert.equal(result.status, 'SUCCEEDED');
    assert.deepEqual(delays, [100, 200]);
    assert.equal(observed.length, 3);
});

test('unknown result stops after the configured maximum poll count', async () => {
    let calls = 0;
    const delays = [];
    const result = await pollPaymentAttempt(
        async () => { calls += 1; return { status: 'UNKNOWN' }; },
        'attempt-1',
        { aborted: false },
        async delay => { delays.push(delay); },
        [50, 100],
    );

    assert.equal(result.status, 'UNKNOWN');
    assert.equal(calls, 3);
    assert.deepEqual(delays, [50, 100]);
});

test('VietQR display requires Backend pending state, live link, and non-test provider data', () => {
    const now = Date.parse('2026-11-13T10:00:00Z');
    const attempt = {
        provider: 'PAYOS', status: 'PENDING', amountVnd: 125000,
        payosPayment: {
            provenance: 'LIVE', testOnly: false, linkState: 'LINK_CREATED', qrPayload: '000201010212TESTPAYLOAD',
            checkoutUrl: 'https://pay.payos.vn/web/live', expiresAt: '2026-11-13T10:20:00Z',
        },
    };
    assert.equal(getPayosPaymentForDisplay(attempt, now)?.qrPayload, attempt.payosPayment.qrPayload);
    for (const provenance of ['TEST_ONLY', 'UNKNOWN', undefined]) {
        assert.equal(getPayosPaymentForDisplay({ ...attempt, payosPayment: { ...attempt.payosPayment, provenance } }, now), null,
            `provenance ${provenance ?? 'missing'} must fail closed`);
    }
    assert.equal(getPayosPaymentForDisplay({ ...attempt, payosPayment: { ...attempt.payosPayment, provenance: 'LIVE', testOnly: true } }, now), null);
    for (const status of ['UNKNOWN', 'FAILED', 'CANCELED', 'EXPIRED', 'SUCCEEDED', 'INITIATED']) {
        assert.equal(getPayosPaymentForDisplay({ ...attempt, status }, now), null, status);
    }
    assert.equal(getPayosPaymentForDisplay({ ...attempt, payosPayment: { ...attempt.payosPayment, testOnly: true } }, now), null);
    assert.equal(getPayosPaymentForDisplay({ ...attempt, payosPayment: { ...attempt.payosPayment, linkState: 'EXPIRED' } }, now), null);
    assert.equal(getPayosPaymentForDisplay({ ...attempt, payosPayment: { ...attempt.payosPayment, expiresAt: '2026-11-13T09:59:59Z' } }, now), null);
    assert.equal(getPayosPaymentForDisplay({ ...attempt, payosPayment: { ...attempt.payosPayment, checkoutUrl: 'http://pay.payos.vn/fake' } }, now), null);
});
