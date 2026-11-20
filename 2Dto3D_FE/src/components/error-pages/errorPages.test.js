import assert from 'node:assert/strict';
import { access, readFile } from 'node:fs/promises';
import test from 'node:test';

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const [app, shared, forbidden, notFound, systemError, unavailable, boundary, guard, routes, styles] = await Promise.all([
  read('../../App.jsx'),
  read('./PopArtErrorPage.jsx'),
  read('./PopArtForbiddenPage.jsx'),
  read('../../pages/NotFoundPage.jsx'),
  read('./PopArtSystemErrorPage.jsx'),
  read('./PopArtServiceUnavailablePage.jsx'),
  read('../ErrorBoundary.jsx'),
  read('../../routes/InternalAccessGuard.jsx'),
  read('../../routes/AppRoutes.jsx'),
  read('./pop-art-error-pages.css'),
]);

test('each error scene has a dedicated illustration asset and semantic live content', async () => {
  for (const asset of ['forbidden.webp', 'not-found.webp', 'system-error.webp', 'service-unavailable.webp']) {
    await access(new URL(`../../../public/images/error-pages/${asset}`, import.meta.url));
  }
  assert.match(shared, /<main\b/);
  assert.match(shared, /aria-labelledby/);
  assert.match(shared, /role="img"/);
  assert.match(shared, /alt=/);
  assert.match(styles, /--joy-paper/);
  assert.match(styles, /:focus-visible/);
  assert.match(styles, /prefers-reduced-motion:\s*reduce/);
  assert.match(styles, /max-width:\s*900px/);
  assert.match(styles, /max-width:\s*420px/);
  assert.match(styles, /html:has\(\.pop-error\)[\s\S]*overflow-x:\s*visible/);
  assert.doesNotMatch(styles, /overflow-x:\s*(?:hidden|clip)/);
  assert.match(styles, /max-height:\s*800px/);
  assert.match(styles, /orientation:\s*landscape/);
});

test('production build explicitly packages all four approved error illustrations', async () => {
  const viteConfig = await read('../../../vite.config.js');
  for (const asset of ['forbidden.webp', 'not-found.webp', 'system-error.webp', 'service-unavailable.webp']) {
    assert.match(viteConfig, new RegExp(`images/error-pages/${asset.replaceAll('.', '\\.')}`));
  }
});

test('forbidden recovery retains auth states and links only to a backend-confirmed workspace', () => {
  assert.match(forbidden, /Khu vực này <em>chưa mở<\/em> cho bạn/);
  assert.match(guard, /accessState === 'loading'.*PageSpinner/s);
  assert.match(guard, /accessState === 'unauthenticated'.*[\s\S]*Navigate to="\/login"/);
  assert.match(guard, /accessState === 'error'.*[\s\S]*verifyCurrentUser/s);
  assert.match(guard, /currentUserStatus === 'verified'/);
  assert.match(guard, /contexts\.includes\('ADMIN'\)/);
  assert.match(guard, /contexts\.includes\('EMPLOYEE'\)/);
  assert.doesNotMatch(guard, /logout\(/);
  assert.doesNotMatch(forbidden, /\b403\b/);
});

test('system render fallback has safe retry and never renders exception details', () => {
  assert.match(systemError, /Hệ thống đang gặp <em>trục trặc nhỏ!<\/em>/);
  assert.match(boundary, /<PopArtSystemErrorPage[\s\S]*onRetry=/);
  assert.match(boundary, /nativeNavigation/);
  assert.match(boundary, /this\.setState\(\{ hasError: false \}\)/);
  assert.match(shared, /nativeNavigation \? 'a' : Link/);
  assert.match(app, /import\.meta\.env\.DEV/);
  assert.match(app, /window\.location\.pathname === '\/__dev\/error-boundary'/);
  assert.match(app, /<RuntimeErrorPreview\s*\/>/);
  assert.doesNotMatch(boundary, /<pre|details|error\.toString|this\.state\.error/);
  assert.doesNotMatch(boundary, /response\?\.status|status === 500/);
});

test('service unavailable supports explicit modes and only renders real optional notice links', async () => {
  assert.match(unavailable, /mode = 'unavailable'/);
  assert.match(unavailable, /mode === 'maintenance'/);
  assert.match(unavailable, /Hệ thống đang được <em>bảo trì!<\/em>/);
  assert.match(unavailable, /Hệ thống hiện chưa thể <em>phục vụ bạn!<\/em>/);
  assert.match(unavailable, /noticeUrl/);
  assert.match(unavailable, /onRetry/);
  assert.match(unavailable, /onRetry \?/);
  assert.doesNotMatch(unavailable, /window\.location\.reload/);
  assert.match(systemError, /onRetry \?/);
  assert.match(routes, /import ErrorPagePreview/);
  assert.match(routes, /import\.meta\.env\.DEV[\s\S]*__dev\/error-pages/);
  const preview = await read('./ErrorPagePreview.jsx');
  assert.match(preview, /type === 'maintenance'/);
  assert.match(preview, /system-error-no-retry/);
  assert.doesNotMatch(routes, /response\?\.status\s*===\s*503|status\s*===\s*503/);
});

test('headline uses a comic speech-bubble panel and recovery actions stack vertically', () => {
  assert.match(shared, /className="pop-error__speech"/);
  assert.match(styles, /\.pop-error__speech::before/);
  assert.match(styles, /clip-path:\s*polygon\(/);
  assert.match(styles, /\.pop-error--system-error \.pop-error__content h1 em \{ color: var\(--error-yellow\)/);
  assert.match(styles, /\.pop-error__actions[\s\S]*flex-direction:\s*column/);
});
