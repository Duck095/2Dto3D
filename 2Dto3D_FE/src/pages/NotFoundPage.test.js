import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./NotFoundPage.jsx', import.meta.url), 'utf8');
const shared = await readFile(new URL('../components/error-pages/PopArtErrorPage.jsx', import.meta.url), 'utf8');
const styles = await readFile(new URL('../components/error-pages/pop-art-error-pages.css', import.meta.url), 'utf8').catch(() => '');
const routes = await readFile(new URL('../routes/AppRoutes.jsx', import.meta.url), 'utf8');

test('not found page presents the Pocket Joy recovery message without an HTTP code', () => {
    assert.match(shared, /<main\b[^>]*className=\{`pop-error/);
    assert.match(shared, /aria-labelledby="pop-error-title"/);
    assert.match(page, /Không tìm thấy <em>trang này!<\/em>/);
    assert.match(page, /Magnifier|magnifier|not-found/i);
    assert.doesNotMatch(page, /<h1[^>]*>\s*404\s*<\/h1>/);
    assert.doesNotMatch(page, /\b404\b|\b403\b|\b500\b|\b503\b/);
    assert.match(styles, /--error-cream:\s*var\(--joy-paper/);
    assert.match(styles, /--error-plum:\s*var\(--joy-ink/);
});

test('not found page keeps home, product discovery, and safe browser-back recovery', () => {
    assert.match(page, /label: 'Quay về trang chủ', to: '\/'/);
    assert.match(page, /label: 'Khám phá sản phẩm', to: '\/products'/);
    assert.match(page, /navigate\(-1\)/);
    assert.match(page, /history\.state\?\.idx/);
    assert.match(page, /Quay lại trang trước/);
});

test('not found page and shared controls support keyboard focus, responsive layout, and reduced motion', () => {
    assert.match(styles, /:focus-visible/);
    assert.match(styles, /@media\s*\(max-width:\s*900px\)/);
    assert.match(styles, /@media\s*\(max-width:\s*420px\)/);
    assert.match(styles, /prefers-reduced-motion:\s*reduce/);
    assert.match(styles, /html:has\(\.pop-error\)[\s\S]*overflow-x:\s*visible/);
    assert.doesNotMatch(styles, /overflow-x:\s*(?:hidden|clip)/);
});

test('home stays the only index route and unknown URLs use the existing catch-all', () => {
    assert.match(routes, /\{\s*index:\s*true,\s*element:\s*<HomePage\s*\/>\s*\}/);
    assert.match(routes, /\{\s*path:\s*'\*',\s*element:\s*<NotFoundPage\s*\/>\s*\}/);
    assert.doesNotMatch(routes, /home-preview|preview-home/i);
});
