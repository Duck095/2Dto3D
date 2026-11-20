import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const routes = await readFile(new URL('../routes/AppRoutes.jsx', import.meta.url), 'utf8');
const config = await readFile(new URL('../app/config.js', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../config/customerNavigation.js', import.meta.url), 'utf8');
const navbar = await readFile(new URL('../components/Navbar.jsx', import.meta.url), 'utf8');
const footer = await readFile(new URL('../components/Footer.jsx', import.meta.url), 'utf8');
const about = await readFile(new URL('../pages/AboutPage.jsx', import.meta.url), 'utf8');
const library = await readFile(new URL('../pages/ProductsPage.jsx', import.meta.url), 'utf8');

test('showcase keeps AI and commerce routes behind default-off feature flags', () => {
    for (const feature of ['AI_STUDIO', 'CART', 'CHECKOUT', 'PRINT_QUOTE']) {
        assert.match(config, new RegExp(`VITE_FEATURE_${feature} === 'true'`));
        assert.doesNotMatch(config, new RegExp(`VITE_FEATURE_${feature}\\s*\\|\\|\\s*'true'`));
    }
    assert.match(routes, /config\.features\.aiStudio\s*\?\s*\[/);
    assert.match(routes, /path:\s*'studio',[\s\S]*?<AIStudioPage\s*\/>/);
    assert.match(routes, /AIStudioPage = lazy\(\(\) => import\('\.\.\/pages\/AIStudioPage\.jsx'\)\)/);
    assert.match(routes, /config\.features\.cart\s*\?\s*\[/);
    assert.match(routes, /config\.features\.checkout\s*\?\s*\[/);
    assert.match(routes, /config\.features\.printQuote\s*\?\s*\[/);
    assert.match(routes, /path:\s*'studio'/);
    assert.match(routes, /path:\s*'cart'/);
    assert.match(routes, /path:\s*'checkout'/);
    assert.match(routes, /path:\s*'print-quote'/);
});

test('navigation hides feature routes by default and restores them only from config flags', () => {
    assert.match(navigation, /features\.aiStudio\s*\?/);
    assert.match(navigation, /features\.cart\s*\|\|\s*features\.checkout/);
    assert.match(navigation, /features\.printQuote\s*\?/);
    assert.match(navigation, /disabledReason:\s*'Thanh toán trực tuyến chưa được hỗ trợ\.'/);
    assert.doesNotMatch(footer, /to:\s*['"]\/(?:studio|cart|checkout|print-quote)/);
});

test('showcase copy avoids presenting disabled AI as an available capability', () => {
    assert.doesNotMatch(about, /AI Studio|công cụ sáng tạo AI|to="\/studio/);
    assert.doesNotMatch(library, /to="\/studio/);
});
