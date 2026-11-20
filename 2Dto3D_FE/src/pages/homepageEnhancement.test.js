import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./HomePage.jsx', import.meta.url), 'utf8');
const styles = await readFile(new URL('./homepage.css', import.meta.url), 'utf8');
const sharedStyles = await readFile(new URL('../styles/storefront.css', import.meta.url), 'utf8');

test('home hero keeps its collection action and accessible concept illustration', () => {
  assert.match(page, /to="\/marketplace" className="pocket-button pocket-button--plum"/);
  assert.match(page, /Small keychain\.<br\s*\/><span>Big happiness\.<\/span>/);
  assert.match(page, /role="img" aria-label="Minh họa concept nguyên bản cho Pocket Joy, không phải ảnh sản phẩm"/);
});

test('home hero motion is lightweight and fully disabled for reduced motion', () => {
  assert.match(styles, /@keyframes pocket-keychain-float/);
  assert.match(styles, /@keyframes pocket-orbit-drift/);
  assert.match(styles, /@keyframes pocket-hero-enter/);
  assert.match(styles, /@media\s*\(prefers-reduced-motion:\s*reduce\)[\s\S]*?animation:\s*none/);
});

test('home hero remains a two-column desktop composition and stacks on mobile', () => {
  assert.match(styles, /\.pocket-hero__grid\s*\{[^}]*grid-template-columns:\s*1fr\s+1fr/);
  assert.match(styles, /@media\s*\(max-width:\s*980px\)[\s\S]*?\.pocket-hero__grid\s*\{[^}]*grid-template-columns:\s*1fr\s+1fr/);
  assert.match(styles, /@media\s*\(max-width:\s*760px\)[\s\S]*?\.pocket-hero__grid\s*\{[^}]*grid-template-columns:\s*1fr/);
});

test('home hero flows directly into the next section without the purple marquee', () => {
  assert.doesNotMatch(page, /pocket-marquee|click · collect · carry a little joy/i);
  assert.doesNotMatch(styles, /\.pocket-marquee/);
});

test('shared storefront containers grow fluidly on wide displays', () => {
  assert.match(sharedStyles, /--joy-page-gutter:\s*clamp\(10px,\s*2vw,\s*32px\)/);
  assert.match(sharedStyles, /\.site-header__inner\s*\{[^}]*width:\s*calc\(100% - var\(--joy-page-gutter\)\s*\*\s*2\)/);
  assert.match(sharedStyles, /\.site-footer__inner\s*\{[^}]*width:\s*calc\(100% - var\(--joy-page-gutter\)\s*\*\s*2\)/);
  assert.match(styles, /@media\s*\(min-width:\s*2560px\)[\s\S]*?\.pocket-hero__copy\s*\{[^}]*max-width:\s*min\(100%,\s*860px\)/);
});

test('narrow home viewport preserves the hero copy and contains orbit decoration', () => {
  assert.match(styles, /@media\s*\(max-width:\s*360px\)[\s\S]*?\.pocket-hero h1\s*\{[^}]*font-size:\s*clamp\(38px,\s*12vw,\s*44px\)/);
  assert.match(styles, /@media\s*\(max-width:\s*1100px\)[\s\S]*?\.pocket-illustration__orbit--one\s*\{[^}]*scale\(\.82\)/);
  assert.match(styles, /\.pocket-illustration\s*\{[^}]*overflow:\s*clip/);
});

test('desktop hero heading keeps its first headline line intact before ultrawide sizing', () => {
  assert.match(styles, /@media\s*\(min-width:\s*981px\)\s*and\s*\(max-width:\s*2559px\)[\s\S]*?\.pocket-hero h1\s*\{[^}]*font-size:\s*clamp\(72px,\s*5\.6vw,\s*82px\)/);
});
