import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import ErrorState from '../components/ui/ErrorState';
import PageSpinner from '../components/ui/PageSpinner';
import { getInternalAccessState } from '../features/auth/currentUserAccess';
import PopArtForbiddenPage from '../components/error-pages/PopArtForbiddenPage.jsx';

export default function InternalAccessGuard({ requiredContext, children }) {
  const { isAuthenticated, isLoading, currentUser, currentUserStatus, verifyCurrentUser } = useAuth();
  const location = useLocation();
  const accessState = getInternalAccessState({ isAuthenticated, isLoading, currentUser, status: currentUserStatus }, requiredContext);

  if (accessState === 'loading') return <PageSpinner />;
  if (accessState === 'unauthenticated') {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  if (accessState === 'error') {
    return (
      <main className="min-h-screen bg-pocket-paper p-4 sm:p-8" aria-live="polite">
        <ErrorState
          title="Không thể xác minh phiên đăng nhập"
          message="Không kết nối được với Backend để xác nhận quyền. Hãy thử lại khi kết nối hoạt động."
          onRetry={verifyCurrentUser}
        />
      </main>
    );
  }
  if (accessState === 'forbidden') {
    const contexts = currentUserStatus === 'verified' && Array.isArray(currentUser?.contexts)
      ? currentUser.contexts
      : [];
    const workspacePath = requiredContext === 'ADMIN' && contexts.includes('EMPLOYEE')
      ? '/employee'
      : requiredContext === 'EMPLOYEE' && contexts.includes('ADMIN')
        ? '/admin'
        : undefined;
    return <PopArtForbiddenPage workspacePath={workspacePath} />;
  }
  if (accessState !== 'allowed') return <PopArtForbiddenPage />;
  return children;
}
