import PopArtForbiddenPage from './PopArtForbiddenPage.jsx';
import PopArtSystemErrorPage from './PopArtSystemErrorPage.jsx';
import PopArtServiceUnavailablePage from './PopArtServiceUnavailablePage.jsx';
import NotFoundPage from '../../pages/NotFoundPage.jsx';

export default function ErrorPagePreview() {
  const params = new URLSearchParams(window.location.search);
  const type = params.get('type');
  if (type === 'forbidden') return <PopArtForbiddenPage />;
  if (type === 'system-error') return <PopArtSystemErrorPage onRetry={() => window.location.reload()} />;
  if (type === 'system-error-no-retry') return <PopArtSystemErrorPage />;
  if (type === 'maintenance') return <PopArtServiceUnavailablePage mode="maintenance" />;
  if (type === 'service-unavailable') return <PopArtServiceUnavailablePage />;
  return <NotFoundPage />;
}
