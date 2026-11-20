import { ENDPOINTS } from './endpoints.js';
import { adaptProductDetail, adaptProductPage } from '../../features/storefront/productApi.js';

export function createProductApi(client) {
    return {
        async getProducts({ page = 0, size = 12, q, sort = 'newest', signal } = {}) {
            const params = { page, size, sort };
            if (typeof q === 'string' && q.trim()) params.q = q.trim();
            return adaptProductPage(await client.get(ENDPOINTS.PRODUCTS.LIST, { params, signal }));
        },
        async getProduct(id, { signal } = {}) {
            return adaptProductDetail(await client.get(ENDPOINTS.PRODUCTS.DETAIL(id), { signal }));
        },
    };
}
