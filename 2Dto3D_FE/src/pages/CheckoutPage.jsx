import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { commerceApi } from '../features/commerce/commerceApiClient';
import { getOrCreatePaymentIdempotencyKey } from '../features/commerce/paymentAttemptPresentation.js';

function getErrorCode(error) {
    return error?.response?.data?.error || error?.response?.data?.code || '';
}

function getCheckoutError(error) {
    switch (getErrorCode(error)) {
        case 'ORDER_CART_EMPTY': return 'Giỏ hàng trên máy chủ đang trống. Hãy tải lại giỏ hàng trước khi tiếp tục.';
        case 'ESTIMATE_EXPIRED': return 'Ước tính đã hết hạn. Hãy cập nhật lựa chọn và tạo ước tính mới trước khi đặt hàng.';
        case 'ORDER_CONFIGURATION_UNAVAILABLE': return 'Cấu hình model, vật liệu hoặc máy in không còn khả dụng.';
        case 'FEATURE_DISABLED': return 'Checkout hiện chưa được bật trên Backend này.';
        default:
            if (error?.response?.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
            if (error?.response?.status === 403) return 'Tài khoản hiện tại không được phép tạo đơn hàng.';
            return 'Không thể xác nhận đơn hàng với Backend. Giỏ hàng vẫn được giữ nguyên.';
    }
}

function formatVnd(value) {
    if (typeof value === 'string' && /^\d+$/.test(value)) return `${new Intl.NumberFormat('vi-VN').format(BigInt(value))} ₫`;
    if (Number.isSafeInteger(value) && value >= 0) return `${new Intl.NumberFormat('vi-VN').format(value)} ₫`;
    return 'Chưa xác định';
}

export default function CheckoutPage() {
    const { isAuthenticated, sessionVersion, isLoading: authLoading } = useAuth();
    const [cart, setCart] = useState(null);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');
    const [order, setOrder] = useState(null);
    const [readiness, setReadiness] = useState(null);
    const [paymentStarting, setPaymentStarting] = useState(false);
    const [paymentMessage, setPaymentMessage] = useState('');
    const navigate = useNavigate();
    const sessionVersionRef = useRef(sessionVersion);
    const paymentRequestRef = useRef(null);
    sessionVersionRef.current = sessionVersion;

    useEffect(() => {
        let current = true;
        paymentRequestRef.current?.abort();
        paymentRequestRef.current = null;
        setOrder(null);
        setReadiness(null);
        setPaymentMessage('');
        setPaymentStarting(false);
        setSubmitting(false);
        if (authLoading) return () => { current = false; };
        if (!isAuthenticated) {
            setCart(null);
            setLoading(false);
            return () => { current = false; };
        }

        setLoading(true);
        commerceApi.getCart()
            .then(result => { if (current) setCart(result); })
            .catch(error => { if (current) setErrorMessage(getCheckoutError(error)); })
            .finally(() => { if (current) setLoading(false); });
        return () => { current = false; };
    }, [authLoading, isAuthenticated, sessionVersion]);

    async function submitOrder() {
        if (submitting || !isAuthenticated || !cart?.items?.length) return;
        setSubmitting(true);
        setErrorMessage('');
        const requestSessionVersion = sessionVersion;
        try {
            const createdOrder = await commerceApi.createOrder();
            if (sessionVersionRef.current !== requestSessionVersion) return;
            setOrder(createdOrder);
            try {
                const nextReadiness = await commerceApi.getPaymentReadiness(createdOrder.id);
                if (sessionVersionRef.current === requestSessionVersion) setReadiness(nextReadiness);
            } catch {
                if (sessionVersionRef.current === requestSessionVersion) setReadiness(null);
            }
        } catch (error) {
            if (sessionVersionRef.current !== requestSessionVersion) return;
            setErrorMessage(getCheckoutError(error));
            if (getErrorCode(error) === 'ORDER_CART_EMPTY') {
                try { setCart(await commerceApi.getCart()); } catch { /* preserve the checkout error */ }
            }
        } finally {
            setSubmitting(false);
        }
    }

    async function startPayment(method) {
        if (!order?.id || !readiness?.eligible || !readiness?.providerAvailability?.PAYOS || paymentStarting) return;
        setPaymentStarting(true);
        setPaymentMessage('');
        const requestSessionVersion = sessionVersion;
        const controller = new AbortController();
        paymentRequestRef.current?.abort();
        paymentRequestRef.current = controller;
        try {
            const idempotencyKey = getOrCreatePaymentIdempotencyKey(
                window.sessionStorage,
                order.id,
                method,
                () => globalThis.crypto?.randomUUID?.(),
            );
            const attempt = await commerceApi.createPaymentAttempt(order.id, method, idempotencyKey, { signal: controller.signal });
            if (controller.signal.aborted || sessionVersionRef.current !== requestSessionVersion) return;
            if (attempt?.redirectUrl) window.location.assign(attempt.redirectUrl);
            else if (attempt?.attemptId) navigate(`/payment-result?attemptId=${encodeURIComponent(attempt.attemptId)}`);
            else setPaymentMessage('Yêu cầu đã được tiếp nhận nhưng chưa có kết quả xác định. Hãy kiểm tra trạng thái đơn trước khi thử lại.');
        } catch (error) {
            if (!controller.signal.aborted && sessionVersionRef.current === requestSessionVersion) setPaymentMessage(getCheckoutError(error));
        } finally {
            if (paymentRequestRef.current === controller) paymentRequestRef.current = null;
            if (sessionVersionRef.current === requestSessionVersion) setPaymentStarting(false);
        }
    }

    return (
        <div className="checkout-status-page">
            <section className="checkout-status" aria-labelledby="checkout-status-title">
                <div className="checkout-status__icon" aria-hidden="true">♡</div>
                <p className="cart-eyebrow">Pocket Joy · Đặt hàng</p>
                <h1 id="checkout-status-title">Xác nhận đơn hàng</h1>
                {authLoading || loading ? <p role="status">Đang tải giỏ hàng từ Backend…</p>
                    : !isAuthenticated ? <p>Bạn cần đăng nhập để đọc giỏ hàng và tạo đơn hàng được lưu trên Backend.</p>
                        : errorMessage ? <p role="alert">{errorMessage}</p>
                            : order ? <div role="status">
                                <p>Backend đã ghi nhận đơn hàng #{order.id}.</p>
                                <p>Trạng thái: {order.status || 'PENDING'}. Chưa có xác nhận thanh toán.</p>
                                <dl>
                                    <dt>Tiền sản phẩm</dt><dd>{formatVnd(order.totalAmountVnd)}</dd>
                                    <dt>Phí vận chuyển</dt><dd>{readiness?.shippingConfirmed ? formatVnd(readiness.shippingAmountVnd) : 'Chờ xác nhận'}</dd>
                                    <dt>Tổng phải thanh toán</dt><dd>{readiness?.totalFrozen ? formatVnd(readiness.payableAmountVnd) : 'Chưa được Backend chốt'}</dd>
                                </dl>
                                {readiness?.eligible && <div aria-label="Phương thức thanh toán">
                                    <button type="button" className="cart-button cart-button--primary"
                                        disabled={paymentStarting || !readiness.providerAvailability?.PAYOS}
                                        onClick={() => startPayment('BANK_TRANSFER')}>
                                        {paymentStarting ? 'Đang tạo mã VietQR…' : 'Thanh toán bằng VietQR'}
                                    </button>
                                    {!readiness.providerAvailability?.PAYOS && <p className="checkout-status__note">Thanh toán VietQR đang chờ cấu hình payOS; đơn hàng chưa được thanh toán.</p>}
                                </div>}
                                {!readiness?.eligible && <p className="checkout-status__note">{readiness?.blocker || 'Thanh toán đang chờ xác nhận phí vận chuyển, tổng tiền và trạng thái sẵn sàng của Backend.'}</p>}
                                {paymentMessage && <p role="status">{paymentMessage}</p>}
                            </div>
                                : <>
                                    <p>Checkout sẽ gửi giỏ hàng và estimate do Backend lưu. Giá từ localStorage không được gửi hoặc dùng để tính tiền.</p>
                                    <p className="checkout-status__note">Hiện chưa có payment sandbox được cấu hình. Tạo đơn không đồng nghĩa với đã thanh toán; phí vận chuyển chưa được tính.</p>
                                    <p role="status">{Array.isArray(cart?.items) ? `${cart.items.length} mục trong giỏ Backend.` : 'Chưa đọc được giỏ Backend.'}</p>
                                    {cart?.items?.length === 0 && <p>Giỏ hàng Backend đang trống.</p>}
                                    {cart?.items?.length > 0 && <button type="button" className="cart-button cart-button--primary" disabled={submitting} onClick={submitOrder}>
                                        {submitting ? 'Đang tạo đơn…' : 'Tạo đơn hàng từ giỏ'}
                                    </button>}
                                </>}
                <div className="checkout-status__actions">
                    <Link to="/cart" className="cart-button cart-button--secondary">Quay lại giỏ hàng</Link>
                    <Link to="/marketplace" className="cart-text-link">Tiếp tục khám phá</Link>
                </div>
            </section>
        </div>
    );
}
