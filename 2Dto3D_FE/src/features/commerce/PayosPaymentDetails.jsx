import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { getPayosPaymentForDisplay } from './paymentAttemptPresentation.js';

function formatVnd(value) {
    if (typeof value === 'string' && /^\d+$/.test(value)) return `${new Intl.NumberFormat('vi-VN').format(BigInt(value))} ₫`;
    if (Number.isSafeInteger(value) && value >= 0) return `${new Intl.NumberFormat('vi-VN').format(value)} ₫`;
    return 'Chưa xác định';
}

export default function PayosPaymentDetails({ attempt }) {
    const payment = getPayosPaymentForDisplay(attempt);
    const [qrImage, setQrImage] = useState('');
    const [qrError, setQrError] = useState(false);

    useEffect(() => {
        let current = true;
        setQrImage('');
        setQrError(false);
        if (!payment) return () => { current = false; };
        QRCode.toDataURL(payment.qrPayload, {
            width: 256,
            margin: 2,
            errorCorrectionLevel: 'M',
            color: { dark: '#1c1722', light: '#ffffff' },
        }).then(dataUrl => {
            if (current) setQrImage(dataUrl);
        }).catch(() => {
            if (current) setQrError(true);
        });
        return () => { current = false; };
    }, [payment?.qrPayload]);

    if (attempt?.provider !== 'PAYOS' || attempt?.status !== 'PENDING') return null;
    if (attempt.payosPayment?.provenance === 'TEST_ONLY' || attempt.payosPayment?.testOnly === true) {
        return <p className="checkout-status__note" role="status">Đây là dữ liệu TEST_ONLY; không dùng mã này để chuyển tiền.</p>;
    }
    if (!payment) return <p className="checkout-status__note" role="status">Không thể xác minh mã VietQR hoặc mã đã hết hạn. Hãy tải lại trạng thái từ Backend.</p>;

    const expiresAt = new Date(payment.expiresAt);
    return (
        <section className="payos-payment" aria-labelledby="payos-payment-title">
            <h2 id="payos-payment-title">Thanh toán bằng VietQR</h2>
            <p className="payos-payment__amount">{formatVnd(attempt.amountVnd)}</p>
            <p role="status">Đang chờ payOS xác nhận chuyển khoản.</p>
            {qrImage ? <img className="payos-payment__qr" src={qrImage} alt="Mã VietQR cho lần thanh toán này" />
                : qrError ? <p role="alert">Không thể tạo mã QR từ dữ liệu Backend.</p>
                    : <p role="status">Đang tạo mã VietQR…</p>}
            <p>Hạn thanh toán: <time dateTime={expiresAt.toISOString()}>{expiresAt.toLocaleString('vi-VN')}</time></p>
            <a className="cart-text-link" href={payment.checkoutUrl} target="_blank" rel="noreferrer noopener">
                Mở trang thanh toán payOS
            </a>
        </section>
    );
}
