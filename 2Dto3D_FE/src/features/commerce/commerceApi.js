import { ENDPOINTS } from '../../services/api/endpoints.js';

export function createCommerceApi(client) {
    return {
        getEstimateOptions(modelId) {
            if (!Number.isInteger(Number(modelId)) || Number(modelId) < 1) throw new TypeError('modelId must be a positive integer');
            return client.get(ENDPOINTS.ESTIMATES.OPTIONS, { params: { modelId: Number(modelId) } });
        },
        createEstimate({ modelId, materialId, printerId, configurationFingerprint }) {
            for (const [name, value] of Object.entries({ modelId, materialId, printerId })) {
                if (!Number.isInteger(value) || value < 1) throw new TypeError(`${name} must be a positive integer`);
            }
            if (typeof configurationFingerprint !== 'string' || !configurationFingerprint.trim()) throw new TypeError('configurationFingerprint is required');
            return client.post(ENDPOINTS.ESTIMATES.CREATE, { modelId, materialId, printerId, configurationFingerprint });
        },
        getCart() {
            return client.get(ENDPOINTS.CART.GET);
        },
        addCartItem({ modelId, estimateId, quantity = 1 }) {
            if (!Number.isInteger(modelId) || modelId < 1
                || !Number.isInteger(estimateId) || estimateId < 1
                || !Number.isInteger(quantity) || quantity < 1) {
                throw new TypeError('Valid model, estimate, and quantity are required');
            }
            return client.post(ENDPOINTS.CART.ITEMS, { modelId, estimateId, quantity });
        },
        createOrder() {
            return client.post(ENDPOINTS.ORDERS.CREATE);
        },
        getPaymentReadiness(orderId, config) {
            if (!Number.isInteger(orderId) || orderId < 1) throw new TypeError('A valid order id is required');
            return client.get(ENDPOINTS.PAYMENTS.READINESS, { ...config, params: { orderId } });
        },
        createPaymentAttempt(orderId, method, idempotencyKey, requestConfig = {}) {
            if (!Number.isInteger(orderId) || orderId < 1) throw new TypeError('A valid order id is required');
            if (method !== 'BANK_TRANSFER') throw new TypeError('Only BANK_TRANSFER is supported for new payment attempts');
            if (typeof idempotencyKey !== 'string' || !idempotencyKey.trim()) throw new TypeError('An idempotency key is required');
            return client.post(ENDPOINTS.PAYMENTS.ATTEMPTS, { orderId, method }, {
                ...requestConfig,
                headers: { ...requestConfig.headers, 'Idempotency-Key': idempotencyKey },
            });
        },
        getPaymentAttempt(attemptId, config) {
            if (typeof attemptId !== 'string' || !attemptId.trim()) throw new TypeError('A payment attempt id is required');
            return client.get(ENDPOINTS.PAYMENTS.ATTEMPT(attemptId), config);
        },
    };
}
