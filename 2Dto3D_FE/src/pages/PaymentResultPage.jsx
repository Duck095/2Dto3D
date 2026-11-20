import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { commerceApi } from '../features/commerce/commerceApiClient';
import PayosPaymentDetails from '../features/commerce/PayosPaymentDetails.jsx';
import {
    clearResolvedPaymentRetry,
    getPaymentAttemptForSession,
    pollPaymentAttempt,
    prepareAllowedPaymentRetry,
} from '../features/commerce/paymentAttemptPresentation.js';

const STATUS_LABELS = Object.freeze({
    INITIATED: 'Đang khởi tạo thanh toán',
    SUBMITTING: 'Đang gửi yêu cầu thanh toán',
    PENDING: 'Đang chờ xác nhận từ nhà cung cấp',
    UNKNOWN: 'Cần xác minh giao dịch trước khi thử lại',
    SUCCEEDED: 'Backend xác nhận thanh toán thành công',
    FAILED: 'Backend xác nhận giao dịch thất bại',
    CANCELED: 'Giao dịch đã bị hủy',
    EXPIRED: 'Giao dịch đã hết hạn',
    LEGACY_REVIEW: 'Giao dịch cần được đối soát',
    PARTIALLY_REFUNDED: 'Giao dịch đã được hoàn một phần',
    REFUNDED: 'Giao dịch đã được hoàn tiền',
});
const RETRYABLE_TERMINAL = new Set(['FAILED', 'CANCELED', 'EXPIRED']);

function waitForPoll(delayMs, signal) {
    return new Promise((resolve, reject) => {
        if (signal.aborted) {
            reject(new DOMException('Request aborted', 'AbortError'));
            return;
        }
        const timer = window.setTimeout(() => {
            signal.removeEventListener('abort', abort);
            resolve();
        }, delayMs);
        function abort() {
            window.clearTimeout(timer);
            reject(new DOMException('Request aborted', 'AbortError'));
        }
        signal.addEventListener('abort', abort, { once: true });
    });
}

function createPaymentKey() {
    return globalThis.crypto?.randomUUID?.();
}

function formatVnd(value) {
    if (typeof value === 'string' && /^\d+$/.test(value)) return `${new Intl.NumberFormat('vi-VN').format(BigInt(value))} ₫`;
    if (Number.isSafeInteger(value) && value >= 0) return `${new Intl.NumberFormat('vi-VN').format(value)} ₫`;
    return 'Chưa xác định';
}

export default function PaymentResultPage() {
    const [params] = useSearchParams();
    const attemptId = params.get('attemptId');
    const navigate = useNavigate();
    const { sessionVersion, isAuthenticated, isLoading: authLoading } = useAuth();
    const [attemptSnapshot, setAttemptSnapshot] = useState(null);
    const [loading, setLoading] = useState(Boolean(attemptId));
    const [retrying, setRetrying] = useState(false);
    const [error, setError] = useState('');
    const activeRequest = useRef(null);
    const requestSequence = useRef(0);
    const sessionVersionRef = useRef(sessionVersion);
    sessionVersionRef.current = sessionVersion;
    const attempt = getPaymentAttemptForSession(attemptSnapshot, sessionVersion, isAuthenticated, attemptId);

    const refresh = useCallback(async () => {
        activeRequest.current?.abort();
        const controller = new AbortController();
        activeRequest.current = controller;
        const sequence = ++requestSequence.current;
        if (!isAuthenticated || !attemptId) {
            setAttemptSnapshot(null);
            setLoading(false);
            return;
        }

        setLoading(true);
        setError('');
        try {
            await pollPaymentAttempt(
                (id, signal) => commerceApi.getPaymentAttempt(id, { signal }),
                attemptId,
                controller.signal,
                waitForPoll,
                undefined,
                current => {
                    if (controller.signal.aborted || requestSequence.current !== sequence) return;
                    setAttemptSnapshot({ sessionVersion, attempt: current });
                    setLoading(false);
                },
            );
        } catch {
            if (!controller.signal.aborted && requestSequence.current === sequence) {
                setAttemptSnapshot(null);
                setError('Chưa thể xác minh trạng thái thanh toán từ Backend.');
            }
        } finally {
            if (requestSequence.current === sequence) setLoading(false);
        }
    }, [attemptId, isAuthenticated, sessionVersion]);

    useEffect(() => {
        setAttemptSnapshot(null);
        void refresh();
        return () => {
            activeRequest.current?.abort();
            requestSequence.current += 1;
        };
    }, [refresh]);

    async function retryPayment() {
        if (!attempt || !RETRYABLE_TERMINAL.has(attempt.status) || attempt.retryAllowed !== true || retrying) return;
        setRetrying(true);
        setError('');
        const requestSessionVersion = sessionVersion;
        const controller = new AbortController();
        activeRequest.current?.abort();
        activeRequest.current = controller;
        try {
            const readiness = await commerceApi.getPaymentReadiness(attempt.orderId, { signal: controller.signal });
            if (controller.signal.aborted || requestSessionVersion !== sessionVersionRef.current) return;
            if (readiness?.eligible !== true || readiness?.providerAvailability?.[attempt.provider] !== true) {
                setError(readiness?.blocker || 'Backend chưa cho phép tạo lần thanh toán mới.');
                return;
            }
            const idempotencyKey = prepareAllowedPaymentRetry(
                window.sessionStorage,
                attempt,
                createPaymentKey,
            );
            if (!idempotencyKey) return;
            const nextAttempt = await commerceApi.createPaymentAttempt(
                attempt.orderId,
                'BANK_TRANSFER',
                idempotencyKey,
                { signal: controller.signal },
            );
            if (controller.signal.aborted || requestSessionVersion !== sessionVersionRef.current) return;
            if (nextAttempt?.attemptId || nextAttempt?.redirectUrl) {
                clearResolvedPaymentRetry(window.sessionStorage, attempt.attemptId, attempt.orderId, attempt.provider);
            }
            if (nextAttempt?.redirectUrl) window.location.assign(nextAttempt.redirectUrl);
            else if (nextAttempt?.attemptId) navigate(`/payment-result?attemptId=${encodeURIComponent(nextAttempt.attemptId)}`);
            else {
                setError('Kết quả lần thử mới chưa xác định. Không tạo lần thanh toán khác; hãy kiểm tra trạng thái lại.');
                await refresh();
            }
        } catch {
            if (!controller.signal.aborted && requestSessionVersion === sessionVersionRef.current) {
                setError('Backend chưa xác nhận việc tạo lần thanh toán mới. Hãy kiểm tra trạng thái trước khi thử lại.');
            }
        } finally {
            if (activeRequest.current === controller) activeRequest.current = null;
            if (requestSessionVersion === sessionVersionRef.current) setRetrying(false);
        }
    }

    return (
        <div className="checkout-status-page">
            <section className="checkout-status" aria-labelledby="payment-result-title">
                <p className="cart-eyebrow">Pocket Joy · Thanh toán</p>
                <h1 id="payment-result-title">Trạng thái thanh toán</h1>
                {authLoading ? <p role="status">Đang xác minh phiên đăng nhập…</p>
                    : !isAuthenticated ? <p role="status">Đăng nhập bằng tài khoản sở hữu đơn hàng để xem trạng thái.</p>
                        : loading ? <p role="status">Đang xác minh với Backend…</p>
                            : error ? <p role="alert">{error}</p>
                                : !attemptId ? <p role="status">Thiếu mã lần thanh toán để tra cứu.</p>
                                    : attempt ? <div role="status">
                                        <p>{STATUS_LABELS[attempt.status] || 'Backend chưa cung cấp trạng thái đã xác minh.'}</p>
                                        {attempt.amountVnd != null && <p>Số tiền từ Backend: {formatVnd(attempt.amountVnd)}</p>}
                                        <PayosPaymentDetails attempt={attempt} />
                                    </div>
                                        : <p role="status">Backend chưa trả về trạng thái đã xác minh.</p>}
                <p>Thông tin redirect từ trình duyệt không xác nhận giao dịch. Trạng thái hiển thị lấy từ Backend.</p>
                {isAuthenticated && attemptId && <button type="button" className="cart-button cart-button--secondary" disabled={loading || retrying} onClick={refresh}>Kiểm tra lại trạng thái</button>}
                {attempt && RETRYABLE_TERMINAL.has(attempt.status) && attempt.retryAllowed === true && <button type="button" className="cart-button cart-button--primary" disabled={retrying} onClick={retryPayment}>
                    {retrying ? 'Đang xác nhận lần thử mới…' : 'Thử thanh toán lại'}
                </button>}
                {attempt?.status === 'UNKNOWN' && <p role="status">Giao dịch chưa rõ kết quả vẫn đang được giữ để đối soát. Chưa thể tạo lần thanh toán khác.</p>}
                <div className="checkout-status__actions"><Link to="/checkout" className="cart-button cart-button--secondary">Quay lại Checkout</Link></div>
            </section>
        </div>
    );
}
