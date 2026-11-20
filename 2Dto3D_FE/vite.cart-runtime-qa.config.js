import baseViteConfig from './vite.config.js';
import { cartRuntimeFixtureVitePlugin } from './playwright-e2e/cartRuntimeFixtureVitePlugin.js';

export default ({ command, mode }) => {
    const base = typeof baseViteConfig === 'function'
        ? baseViteConfig({ command, mode })
        : baseViteConfig;

    if (command !== 'serve') return base;
    if (mode !== 'cart-qa') {
        throw new Error('The isolated Cart QA Vite config must be started with --mode cart-qa.');
    }

    return {
        ...base,
        plugins: [
            ...(Array.isArray(base.plugins) ? base.plugins : [base.plugins]).filter(Boolean),
            cartRuntimeFixtureVitePlugin(process.cwd(), mode),
        ],
        server: {
            ...base.server,
            host: '127.0.0.1',
            port: 3101,
            strictPort: true,
            open: false,
            proxy: undefined,
        },
    };
};
