import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const marketplace = await readFile(new URL('./marketplace.css', import.meta.url), 'utf8');
const productDetail = await readFile(new URL('./product-detail.css', import.meta.url), 'utf8');
const storefront = await readFile(new URL('../styles/storefront.css', import.meta.url), 'utf8');
const printQuote = await readFile(new URL('./print-quote.css', import.meta.url), 'utf8');

test('catalog, detail, and discovery pages share the fluid content container', () => {
  assert.match(marketplace, /\.joy-shop__wrap,.joy-shop__main\s*\{[^}]*width:\s*calc\(100%\s*-\s*var\(--joy-page-gutter\)\s*\*\s*2\)/);
  assert.match(productDetail, /\.joy-detail__breadcrumbs,.joy-detail__top,.joy-detail__information,.joy-detail__reviews,.joy-detail__related\s*\{[^}]*width:\s*calc\(100%\s*-\s*var\(--joy-page-gutter\)\s*\*\s*2\)/);
  assert.match(storefront, /\.joy-library__inner,.joy-guide__inner,.joy-about__inner\s*\{[^}]*width:\s*calc\(100%\s*-\s*var\(--joy-page-gutter\)\s*\*\s*2\)/);
});

test('marketplace hero art stays behind copy at very narrow widths', () => {
  assert.match(marketplace, /@media\s*\(max-width:\s*360px\)[\s\S]*?\.joy-shop__hero-art\s*\{[^}]*opacity:\s*\.14[^}]*scale\(\.52\)/);
});

test('cart and print quote use the wide-screen container without changing their inner layout', () => {
  assert.match(storefront, /\.cart-page__inner\s*\{width:\s*calc\(100%\s*-\s*var\(--joy-page-gutter\)\s*\*\s*2\)/);
  assert.match(printQuote, /\.print-quote__container\s*\{\s*width:\s*calc\(100%\s*-\s*var\(--joy-page-gutter\)\s*\*\s*2\)/);
});
