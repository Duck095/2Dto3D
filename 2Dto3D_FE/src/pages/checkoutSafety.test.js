import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const checkoutSource = await readFile(new URL('./CheckoutPage.jsx', import.meta.url), 'utf8');
const cartSource = await readFile(new URL('./CartPage.jsx', import.meta.url), 'utf8');
const configSource = await readFile(new URL('../app/config.js', import.meta.url), 'utf8');
const routesSource = await readFile(new URL('../routes/AppRoutes.jsx', import.meta.url), 'utf8');
const localDevelopmentEnv = await readFile(new URL('../../.env.development.local', import.meta.url), 'utf8');
const paymentDetailsSource = await readFile(new URL('../features/commerce/PayosPaymentDetails.jsx', import.meta.url), 'utf8');
const paymentPresentationSource = await readFile(new URL('../features/commerce/paymentAttemptPresentation.js', import.meta.url), 'utf8');

test('checkout reads Backend cart and creates orders without using browser prices', () => {
    assert.match(checkoutSource, /commerceApi\.getCart/);
    assert.match(checkoutSource, /commerceApi\.createOrder/);
    assert.match(checkoutSource, /ORDER_CART_EMPTY/);
    assert.match(checkoutSource, /ESTIMATE_EXPIRED/);
    assert.match(checkoutSource, /ORDER_CONFIGURATION_UNAVAILABLE/);
    assert.match(checkoutSource, /order\.totalAmountVnd/);
    assert.match(checkoutSource, /Chờ xác nhận/);
    assert.doesNotMatch(checkoutSource, /localStorage\.(?:getItem|setItem|removeItem)|clearCart|\b(?:COD|VNPay|MoMo|ZaloPay)\b/i);
    assert.match(checkoutSource, /Thanh toán bằng VietQR/);
    assert.match(checkoutSource, /providerAvailability\?\.PAYOS/);
    assert.doesNotMatch(configSource, /VITE_MOCK_CHECKOUT|mockCheckout/);
});

test('payment result treats redirect as a lookup hint and displays only Backend status', async () => {
    const resultSource = await readFile(new URL('./PaymentResultPage.jsx', import.meta.url), 'utf8');
    assert.match(resultSource, /pollPaymentAttempt/);
    assert.match(resultSource, /commerceApi\.getPaymentAttempt\(id, \{ signal \}\)/);
    assert.match(resultSource, /Thông tin redirect từ trình duyệt không xác nhận giao dịch/);
    assert.doesNotMatch(resultSource, /params\.get\(['"](?:status|success|result)['"]\)/);
    assert.match(resultSource, /Backend xác nhận thanh toán thành công/);
    assert.match(resultSource, /'BANK_TRANSFER'/);
    assert.match(paymentDetailsSource, /QRCode\.toDataURL/);
    assert.match(paymentDetailsSource, /testOnly === true/);
    assert.match(paymentDetailsSource, /provenance/);
    assert.match(paymentPresentationSource, /url\.hostname !== 'pay\.payos\.vn'/);
    assert.doesNotMatch(paymentDetailsSource, /api\.qrserver|chart\.googleapis|quickchart\.io/i);
});

test('checkout, cart, AI Studio, and print quote routes stay available behind default-off release flags', () => {
    assert.match(configSource, /aiStudio: env\.VITE_FEATURE_AI_STUDIO === 'true'/);
    assert.match(configSource, /cart: env\.VITE_FEATURE_CART === 'true'/);
    assert.match(configSource, /checkout: env\.VITE_FEATURE_CHECKOUT === 'true'/);
    assert.match(configSource, /printQuote: env\.VITE_FEATURE_PRINT_QUOTE === 'true'/);
    assert.doesNotMatch(configSource, /VITE_FEATURE_(?:AI_STUDIO|CART|CHECKOUT|PRINT_QUOTE)\s*\|\|\s*['"]true/);
    for (const flag of ['AI_STUDIO', 'CART', 'CHECKOUT', 'PRINT_QUOTE']) {
        assert.match(localDevelopmentEnv, new RegExp(`^VITE_FEATURE_${flag}=true$`, 'm'));
    }
    assert.match(localDevelopmentEnv, /^VITE_DEMO_MODE=false$/m);
    assert.match(routesSource, /config\.features\.aiStudio\s*\?\s*\[\{\s*path:\s*'studio'/);
    assert.match(routesSource, /config\.features\.cart\s*\?\s*\[\{\s*path:\s*'cart'/);
    assert.match(routesSource, /config\.features\.checkout\s*\?\s*\[\{\s*path:\s*'checkout'/);
    assert.match(routesSource, /config\.features\.printQuote\s*\?\s*\[\{\s*path:\s*'print-quote'/);
    assert.match(routesSource, /AIStudioPage/);
    assert.doesNotMatch(routesSource, /<AIStudioWorkspace\s*\/>/);
    assert.match(routesSource, /CartPage/);
    assert.match(routesSource, /CheckoutPage/);
    assert.match(routesSource, /PrintQuotePage/);
    assert.match(routesSource, /path: 'workflow', element: <LazyPage><WorkflowPage \/><\/LazyPage>/);
    assert.match(routesSource, /path: 'resources', element: <LazyPage><ResourcesPage \/><\/LazyPage>/);
    assert.doesNotMatch(routesSource, /ProtectedRoute/);
    assert.match(routesSource, /path: 'marketplace'/);
    assert.match(routesSource, /path: 'product\/:id'/);
});

test('cart states the unconfirmed shipping policy and does not calculate shipping charges', () => {
    assert.match(cartSource, /Phí vận chuyển chưa được xác định/);
    assert.doesNotMatch(cartSource, /30000|300000|Miễn phí vận chuyển/);
});

test('cart labels saved local prices and totals as references, not payable amounts', () => {
    assert.match(cartSource, /Giá tham khảo từ dữ liệu giỏ hàng trên thiết bị/);
    assert.match(cartSource, /Giá tham khảo/);
    assert.match(cartSource, /Tổng tham khảo — chưa phải số tiền phải thanh toán/);
    assert.match(cartSource, /Giá, phí vận chuyển và báo giá chính thức chưa được hệ thống xác nhận/);
    assert.match(cartSource, /Đặt hàng và thanh toán trực tuyến hiện chưa được hỗ trợ/);
    assert.doesNotMatch(cartSource, /Giá được xác nhận theo catalog hiện tại|Giá và phí vận chuyển đã được xác nhận/);
    assert.doesNotMatch(cartSource, /fetch\s*\(|axios|quoteApi|checkoutApi|paymentApi/i);
});
