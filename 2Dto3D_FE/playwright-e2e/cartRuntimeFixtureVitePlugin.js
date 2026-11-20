import { resolve } from 'node:path';

const PRODUCT_IMPORT = "import { STORE_PRODUCTS } from '../data/productModels';";
const TEST_IMPORT = "import { CART_TEST_PRODUCTS as STORE_PRODUCTS } from '../../playwright-e2e/fixtures/cartTestProducts.js';";

function normalize(path) {
    return path.replaceAll('\\', '/');
}

export function cartRuntimeFixtureVitePlugin(rootDirectory, mode) {
    const cartContextPath = normalize(resolve(rootDirectory, 'src/context/CartContext.jsx'));
    let transformedCartContextCount = 0;

    return {
        name: 'isolated-cart-runtime-test-fixture',
        apply: 'serve',
        enforce: 'pre',
        transform(source, id) {
            const modulePath = normalize(id.split('?')[0]);
            if (modulePath !== cartContextPath) return null;

            const importCount = source.split(PRODUCT_IMPORT).length - 1;
            if (importCount !== 1) {
                this.error(`Cart runtime QA expected exactly one production STORE_PRODUCTS import in ${modulePath}; found ${importCount}.`);
            }

            transformedCartContextCount += 1;
            return source.replace(PRODUCT_IMPORT, TEST_IMPORT);
        },
        configureServer(server) {
            server.middlewares.use('/__cart-qa__/health', (_request, response) => {
                response.statusCode = 200;
                response.setHeader('Content-Type', 'application/json');
                response.end(JSON.stringify({ status: 'ok', mode, testOnly: true }));
            });

            server.middlewares.use('/__cart-qa__/status', (_request, response) => {
                response.statusCode = 200;
                response.setHeader('Content-Type', 'application/json');
                response.end(JSON.stringify({
                    mode,
                    cartContextPath: 'src/context/CartContext.jsx',
                    fixtureImportPath: 'playwright-e2e/fixtures/cartTestProducts.js',
                    transformedCartContextCount,
                }));
            });
        },
    };
}
