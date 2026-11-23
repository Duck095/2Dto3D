// ─────────────────────────────────────────────
// Morphia — All backend endpoints in one place
//
// No service or component should hardcode a URL path.
// Import ENDPOINTS from here instead.
// ─────────────────────────────────────────────

export const ENDPOINTS = Object.freeze({
  AUTH: Object.freeze({
    LOGIN: '/auth/login',
    REGISTER: '/auth/register',
    PING: '/auth/ping',
    ME: '/v1/me',
  }),

  MODELS: Object.freeze({
    LIST: '/models',
    DETAIL: (id) => `/models/${id}`,
  }),

  PRODUCTS: Object.freeze({
    LIST: '/products',
    DETAIL: (id) => `/products/${encodeURIComponent(id)}`,
  }),

  ESTIMATES: Object.freeze({ CREATE: '/price-estimates', OPTIONS: '/price-estimates/options' }),

  AI: Object.freeze({
    GENERATIONS: '/ai-studio/generations',
    GENERATION: (id) => `/ai-studio/generations/${id}`,
    REFRESH: (id) => `/ai-studio/generations/${id}/refresh`,
    INPUT_IMAGE: (id) => `/ai-studio/generations/${id}/input-image`,
    MODEL_ASSET: (id, format) => `/ai-studio/generations/${id}/models/model.${format}`,
    MODEL_DOWNLOAD: (id, format) => `/ai-studio/generations/${id}/models/model.${format}/download`,
    CONSUME_DOWNLOAD: (id) => `/ai-studio/generations/${id}/downloads`,
  }),

  CART: Object.freeze({
    GET: '/cart',
    ITEMS: '/cart/items',
    ITEM: (id) => `/cart/items/${id}`,
  }),

  ORDERS: Object.freeze({
    CREATE: '/orders',
    LIST: '/orders',
    DETAIL: (id) => `/orders/${id}`,
  }),

  MEMBERSHIPS: Object.freeze({
    PLANS: '/memberships/plans',
    ME: '/memberships/me',
    COMMUNITY_DOWNLOAD: (modelId) => `/memberships/community-downloads/${encodeURIComponent(modelId)}`,
  }),

  PAYMENTS: Object.freeze({
    READINESS: '/payments/readiness',
    ATTEMPTS: '/payments/attempts',
    ATTEMPT: (id) => `/payments/attempts/${encodeURIComponent(id)}`,
  }),

  STAFF: Object.freeze({
    LIST: '/admin/staff',
    CREATE: '/admin/staff',
    DETAIL: (id) => `/admin/staff/${id}`,
    UPDATE: (id) => `/admin/staff/${id}`,
    STATUS: (id) => `/admin/staff/${id}/status`,
  }),

  ADMIN_PRODUCTS: Object.freeze({
    LIST: '/admin/products',
    DETAIL: (id) => `/admin/products/${encodeURIComponent(id)}`,
    PUBLISH: (id) => `/admin/products/${encodeURIComponent(id)}/publish`,
    ARCHIVE: (id) => `/admin/products/${encodeURIComponent(id)}/archive`,
    MESH_STRUCTURE: (id) => `/admin/products/${encodeURIComponent(id)}/mesh-structure`,
    MESH_CONFIGURATIONS: (id) => `/admin/products/${encodeURIComponent(id)}/mesh-configurations`,
  }),

  ADMIN_MATERIALS: Object.freeze({
    LIST: '/materials/', CREATE: '/materials/create', UPDATE: id => `/materials/${encodeURIComponent(id)}`,
    COLORS: '/colors/', CREATE_COLOR: '/colors/create',
  }),

  TASKS: Object.freeze({
    LIST: '/admin/tasks',
    CREATE: '/admin/tasks',
    DETAIL: (id) => `/admin/tasks/${id}`,
    UPDATE: (id) => `/admin/tasks/${id}`,
    ACCEPTANCE: '/admin/tasks/acceptance',
    REVIEW: (id) => `/admin/tasks/${id}/review`,
    SUMMARY: '/admin/tasks/summary',
  }),

  EMPLOYEE: Object.freeze({
    STAFF_PROFILE: '/v1/me/staff-profile',
    TASKS: '/v1/me/tasks',
    TASK: (id) => `/v1/me/tasks/${id}`,
    START_TASK: (id) => `/v1/me/tasks/${id}/start`,
    SUBMISSIONS: (id) => `/v1/me/tasks/${id}/submissions`,
  }),
});
