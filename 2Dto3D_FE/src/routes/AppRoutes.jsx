// ─────────────────────────────────────────────
// Morphia — Application Router
//
// • Product detail is lazy-loaded
// • AI, quote, cart, and checkout routes stay registered behind release flags
// ─────────────────────────────────────────────

import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout.jsx';
import PageSpinner from '../components/ui/PageSpinner.jsx';
import InternalAccessGuard from './InternalAccessGuard.jsx';
import AdminLayout from '../layouts/AdminLayout.jsx';
import EmployeeLayout from '../layouts/EmployeeLayout.jsx';
import AdminOverviewPage from '../pages/admin/AdminOverviewPage.jsx';
import { config } from '../app/config.js';

// ── Eager-loaded pages (small, frequently visited) ──
import HomePage from '../pages/HomePage.jsx';
import MarketplacePage from '../pages/MarketplacePage.jsx';
import ProductsPage from '../pages/ProductsPage.jsx';
import PricingPage from '../pages/PricingPage.jsx';
import AboutPage from '../pages/AboutPage.jsx';
import AuthPage from '../pages/AuthPage.jsx';
import NotFoundPage from '../pages/NotFoundPage.jsx';
import ErrorPagePreview from '../components/error-pages/ErrorPagePreview.jsx';

// ── Lazy-loaded pages ──
const ProductDetailPage = lazy(() => import('../pages/ProductDetailPage.jsx'));
const StaffManagementPage = lazy(() => import('../pages/admin/StaffManagementPage.jsx'));
const AdminTasksPage = lazy(() => import('../pages/admin/AdminTasksPage.jsx'));
const AdminAcceptancePage = lazy(() => import('../pages/admin/AdminAcceptancePage.jsx'));
const AdminProductsPage = lazy(() => import('../pages/admin/AdminProductsPage.jsx'));
const AdminMaterialsPage = lazy(() => import('../pages/admin/AdminMaterialsPage.jsx'));
const EmployeeWorkspacePage = lazy(() => import('../pages/employee/EmployeeWorkspacePage.jsx'));
const EmployeeTaskPage = lazy(() => import('../pages/employee/EmployeeTaskPage.jsx'));
const AIStudioPage = lazy(() => import('../pages/AIStudioPage.jsx'));
const CartPage = lazy(() => import('../pages/CartPage.jsx'));
const CheckoutPage = lazy(() => import('../pages/CheckoutPage.jsx'));
const PaymentResultPage = lazy(() => import('../pages/PaymentResultPage.jsx'));
const PrintQuotePage = lazy(() => import('../pages/PrintQuotePage.jsx'));
const WorkflowPage = lazy(() => import('../pages/WorkflowPage.jsx'));
const ResourcesPage = lazy(() => import('../pages/ResourcesPage.jsx'));

/** Suspense wrapper for lazy pages */
function LazyPage({ children }) {
  return <Suspense fallback={<PageSpinner />}>{children}</Suspense>;
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <MainLayout />,
    children: [
      // ── Public routes ────────────────────
      { index: true, element: <HomePage /> },
      { path: 'marketplace', element: <MarketplacePage /> },
      {
        path: 'product/:id',
        element: <LazyPage><ProductDetailPage /></LazyPage>,
      },
      { path: 'products', element: <ProductsPage /> },
      { path: 'pricing', element: <PricingPage /> },
      { path: 'about', element: <AboutPage /> },
      { path: 'workflow', element: <LazyPage><WorkflowPage /></LazyPage> },
      { path: 'resources', element: <LazyPage><ResourcesPage /></LazyPage> },
      ...(config.features.aiStudio ? [{ path: 'studio', element: <LazyPage><AIStudioPage /></LazyPage> }] : []),
      ...(config.features.cart ? [{ path: 'cart', element: <LazyPage><CartPage /></LazyPage> }] : []),
      ...(config.features.checkout ? [{ path: 'checkout', element: <LazyPage><CheckoutPage /></LazyPage> }] : []),
      ...(config.features.checkout ? [{ path: 'payment-result', element: <LazyPage><PaymentResultPage /></LazyPage> }] : []),
      ...(config.features.printQuote ? [{ path: 'print-quote', element: <LazyPage><PrintQuotePage /></LazyPage> }] : []),
      { path: 'login', element: <AuthPage key="login" mode="login" /> },
      { path: 'register', element: <AuthPage key="register" mode="register" /> },
    ],
  },
  {
    path: '/admin',
    element: <InternalAccessGuard requiredContext="ADMIN"><AdminLayout /></InternalAccessGuard>,
    children: [
      { index: true, element: <AdminOverviewPage /> },
      { path: 'staff', element: <LazyPage><StaffManagementPage /></LazyPage> },
      { path: 'tasks', element: <LazyPage><AdminTasksPage /></LazyPage> },
      { path: 'acceptance', element: <LazyPage><AdminAcceptancePage /></LazyPage> },
      { path: 'products', element: <LazyPage><AdminProductsPage /></LazyPage> },
      { path: 'materials', element: <LazyPage><AdminMaterialsPage /></LazyPage> },
    ],
  },
  {
    path: '/employee',
    element: <InternalAccessGuard requiredContext="EMPLOYEE"><EmployeeLayout /></InternalAccessGuard>,
    children: [
      { index: true, element: <LazyPage><EmployeeWorkspacePage /></LazyPage> },
      { path: 'tasks/:taskId', element: <LazyPage><EmployeeTaskPage /></LazyPage> },
    ],
  },
  ...(import.meta.env.DEV ? [{ path: '/__dev/error-pages', element: <ErrorPagePreview /> }] : []),
  // Keep unknown paths outside MainLayout so the full-screen recovery page has no nested site chrome.
  { path: '*', element: <NotFoundPage /> },
]);
