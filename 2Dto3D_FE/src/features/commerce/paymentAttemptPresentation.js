const RETRYABLE_TERMINAL_STATUSES = new Set(['FAILED', 'CANCELED', 'EXPIRED']);
const POLLED_IN_PROGRESS = new Set(['INITIATED', 'SUBMITTING', 'PENDING', 'UNKNOWN']);
export const PAYMENT_STATUS_POLL_DELAYS_MS = Object.freeze([1000, 2000, 4000, 8000]);

function paymentKeyStorageKey(orderId, provider) {
    if (!Number.isInteger(orderId) || orderId < 1) throw new TypeError('A valid order id is required');
    if (provider !== 'BANK_TRANSFER') throw new TypeError('Only BANK_TRANSFER is supported for new payment attempts');
    return `2dto3d.payment.idempotency:${orderId}:${provider}`;
}

function createSecureKey(createKey) {
    const key = createKey?.();
    if (typeof key !== 'string' || !key.trim()) throw new TypeError('A secure idempotency key generator is required');
    return key;
}

export function getOrCreatePaymentIdempotencyKey(storage, orderId, provider, createKey) {
    const storageKey = paymentKeyStorageKey(orderId, provider);
    const existing = storage?.getItem(storageKey);
    if (typeof existing === 'string' && existing.trim()) return existing;
    const next = createSecureKey(createKey);
    storage?.setItem(storageKey, next);
    return next;
}

export function prepareAllowedPaymentRetry(storage, attempt, createKey) {
    if (!attempt || attempt.retryAllowed !== true || !RETRYABLE_TERMINAL_STATUSES.has(attempt.status)
            || typeof attempt.attemptId !== 'string' || !attempt.attemptId.trim()
            || attempt.provider !== 'PAYOS') return null;
    const baseKey = paymentKeyStorageKey(attempt.orderId, 'BANK_TRANSFER');
    const retryKey = `${baseKey}:retry:${attempt.attemptId}`;
    const existing = storage?.getItem(retryKey);
    if (typeof existing === 'string' && existing.trim()) return existing;
    const next = createSecureKey(createKey);
    storage?.setItem(retryKey, next);
    storage?.setItem(baseKey, next);
    return next;
}

export function clearResolvedPaymentRetry(storage, attemptId, orderId, provider) {
    if (typeof attemptId !== 'string' || !attemptId.trim()) return;
    if (provider !== 'PAYOS') return;
    storage?.removeItem(`${paymentKeyStorageKey(orderId, 'BANK_TRANSFER')}:retry:${attemptId}`);
}

export function getPaymentAttemptForSession(snapshot, sessionVersion, isAuthenticated, expectedAttemptId) {
    if (!isAuthenticated || snapshot?.sessionVersion !== sessionVersion
            || snapshot?.attempt?.attemptId !== expectedAttemptId) return null;
    return snapshot.attempt || null;
}

export function getPayosPaymentForDisplay(attempt, now = Date.now()) {
    if (attempt?.provider !== 'PAYOS' || attempt?.status !== 'PENDING') return null;
    const payment = attempt.payosPayment;
    if (payment?.provenance !== 'LIVE' || payment?.testOnly !== false || payment?.linkState !== 'LINK_CREATED'
            || typeof payment.qrPayload !== 'string' || !payment.qrPayload.startsWith('000201')
            || payment.qrPayload.length > 2048) return null;
    const expiry = Date.parse(payment.expiresAt);
    if (!Number.isFinite(expiry) || expiry <= now) return null;
    try {
        const url = new URL(payment.checkoutUrl);
        if (url.protocol !== 'https:' || url.hostname !== 'pay.payos.vn' || url.username || url.password) return null;
    } catch {
        return null;
    }
    return payment;
}

export async function pollPaymentAttempt(readAttempt, attemptId, signal, wait, delays = PAYMENT_STATUS_POLL_DELAYS_MS, onAttempt) {
    let attempt;
    for (let pollIndex = 0; ; pollIndex += 1) {
        attempt = await readAttempt(attemptId, signal);
        onAttempt?.(attempt);
        if (signal?.aborted || !POLLED_IN_PROGRESS.has(attempt?.status) || pollIndex >= delays.length) return attempt;
        await wait(delays[pollIndex], signal);
    }
}
