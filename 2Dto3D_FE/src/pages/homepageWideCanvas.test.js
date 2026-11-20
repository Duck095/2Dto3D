import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./HomePage.jsx', import.meta.url), 'utf8');
const styles = await readFile(new URL('./homepage.css', import.meta.url), 'utf8');
const sharedStyles = await readFile(new URL('../styles/storefront.css', import.meta.url), 'utf8');

test('homepage adds a distinct model-library navigation card without changing the four vibe cards', () => {
  assert.match(page, /to="\/products"[^>]*pocket-vibe--discovery|className="pocket-vibe pocket-vibe--discovery"[^>]*to="\/products"/);
  assert.match(page, /Khám phá kho mẫu 3D/);
  assert.match(page, /Clicky & Fidget[\s\S]*Cute & Sweet[\s\S]*Characters & Collectibles[\s\S]*Personalized Gifts/);
});

test('wide canvas and wide navbar are scoped to homepage only', () => {
  assert.match(sharedStyles, /\.site-layout--home\s*\{[^}]*--joy-home-container:\s*calc\(100%\s*-\s*var\(--joy-page-gutter\)\s*\*\s*2\)/);
  assert.match(sharedStyles, /@media\s*\(min-width:\s*1366px\)[\s\S]*?\.site-layout--home\s+\.site-header__inner/);
  assert.match(sharedStyles, /\.site-layout--home\s+\.site-footer__inner/);
  assert.match(styles, /\.pocket-wrap\s*\{[^}]*var\(--joy-home-container/);
  assert.match(sharedStyles, /--joy-page-gutter:clamp\(10px,2vw,32px\)/);
});

test('16-inch laptop CSS viewports use fluid homepage gutters and a larger hero composition', () => {
  assert.match(sharedStyles, /@media\s*\(min-width:\s*1366px\)[\s\S]*?\.site-layout--home\s*\{[^}]*--joy-home-container/);
  assert.match(sharedStyles, /@media\s*\(min-width:\s*1366px\)[\s\S]*?\.site-layout--home\s+\.site-header__inner\s*\{[^}]*width:\s*var\(--joy-home-container\)/);
  assert.match(styles, /@media\s*\(min-width:\s*1366px\)[\s\S]*?\.pocket-hero__grid\s*\{[^}]*width:\s*var\(--joy-home-container\)[^}]*min-height:\s*clamp\(/);
  assert.match(styles, /@media\s*\(min-width:\s*1366px\)[\s\S]*?\.pocket-illustration\s*\{[^}]*min-height:\s*clamp\(/);
});

test('ultrawide desktop gets a controlled hero and five balanced navigation tiles', () => {
  assert.match(styles, /@media\s*\(min-width:\s*1920px\)\s*and\s*\(min-height:\s*1200px\)[\s\S]*?\.pocket-hero\s*\{[^}]*min-height:\s*clamp\(/);
  assert.match(styles, /@media\s*\(min-width:\s*1920px\)[\s\S]*?\.pocket-vibe-grid\s*\{[^}]*grid-template-columns:\s*repeat\(5/);
  assert.match(styles, /\.pocket-vibe--discovery/);
  assert.match(styles, /@media\s*\(max-width:\s*1919px\)[\s\S]*?\.pocket-vibe-grid/);
});

test('homepage product loading, real API contract, empty, error, and retry remain intact', () => {
  assert.match(page, /productApi\.getProducts\(\{ page: 0, size: 4, sort: 'newest', signal: controller\.signal \}\)/);
  assert.match(page, /featuredState\.status === 'loading'/);
  assert.match(page, /featuredState\.status === 'ready'/);
  assert.match(page, /featuredState\.status === 'empty'/);
  assert.match(page, /featuredState\.status === 'error'/);
  assert.match(page, /onClick=\{retryFeaturedProducts\}/);
});
