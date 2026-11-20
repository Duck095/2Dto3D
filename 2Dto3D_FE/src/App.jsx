import { RouterProvider } from "react-router-dom";
import { router } from "./routes/AppRoutes.jsx";
import { AuthProvider } from "./context/AuthContext.jsx";
import { CartProvider } from "./context/CartContext.jsx";
import ErrorBoundary from "./components/ErrorBoundary.jsx";

function RuntimeErrorPreview() {
  throw new Error('intentional render failure for ErrorBoundary browser regression');
}

export default function App() {
  const isBoundaryPreview = import.meta.env.DEV
    && window.location.pathname === '/__dev/error-boundary';

  return (
    <ErrorBoundary>
      {isBoundaryPreview
        ? <RuntimeErrorPreview />
        : (
          <AuthProvider>
            <CartProvider>
              <RouterProvider router={router} />
            </CartProvider>
          </AuthProvider>
        )}
    </ErrorBoundary>
  );
}
