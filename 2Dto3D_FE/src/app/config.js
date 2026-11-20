// ─────────────────────────────────────────────
// Morphia — Centralized application configuration
// All VITE_* env vars are resolved here once.
// Components / services import config — never import.meta.env directly.
// ─────────────────────────────────────────────

const env = import.meta.env || {};

export const config = Object.freeze({
  /** Base URL for all API calls (Vite proxy in dev, full URL in prod) */
  apiBaseUrl: env.VITE_API_BASE_URL || '/api',

  /** When true, services return mock data instead of calling the backend */
  demoMode: env.VITE_DEMO_MODE === 'true',

  /** Customer-facing capabilities stay hidden until explicitly released. */
  features: Object.freeze({
    aiStudio: env.VITE_FEATURE_AI_STUDIO === 'true',
    cart: env.VITE_FEATURE_CART === 'true',
    checkout: env.VITE_FEATURE_CHECKOUT === 'true',
    printQuote: env.VITE_FEATURE_PRINT_QUOTE === 'true',
  }),

  /** Vite built-in flags */
  isDev: env.DEV,
  isProd: env.PROD,
});
