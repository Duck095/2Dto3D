export const CART_RUNTIME_QA_SENTINEL = '__FE_CART_RUNTIME_QA_ONLY_V1__';

export const CART_TEST_PRODUCTS = Object.freeze([
    Object.freeze({
        id: 'TEST_ONLY-CART-001',
        visibility: 'public-approved',
        title: 'TEST_ONLY — Synthetic Cart QA Item',
        description: 'Isolated browser QA fixture. Not a real product.',
        priceNum: 12345,
        priceLabel: null,
        priceConfirmed: true,
        readyForSale: true,
        priceOnRequest: false,
        purchaseOptions: Object.freeze([
            Object.freeze({ id: 'test-variant-v1', label: 'TEST_ONLY variant' }),
        ]),
        materials: Object.freeze([
            Object.freeze({ id: 'test-material-m1', name: 'TEST_ONLY material', premium: 655 }),
        ]),
        publicMedia: null,
    }),
]);
