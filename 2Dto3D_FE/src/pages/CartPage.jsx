import { Link } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useCart } from '../context/CartContext';
import { commerceApi } from '../features/commerce/commerceApiClient';
import { config } from '../app/config';
import { useAuth } from '../context/AuthContext';

function formatVND(value) {
    return `${value.toLocaleString('vi-VN')} ₫`;
}

function ProductImage({ item }) {
    const [failed, setFailed] = useState(false);
    if (!item.img || failed) return <div className="cart-item__placeholder" aria-label="Chưa có hình ảnh sản phẩm được duyệt"><span aria-hidden="true">✿</span></div>;
    return <img src={item.img} alt={item.title} className="cart-item__image" onError={() => setFailed(true)} />;
}

function RestoreNotice({ notice, onDismiss, migrationBlocked, onReplace }) {
    if (!notice) return null;
    return (
        <div className="cart-notice" role="status">
            <p>{notice}</p>
            {migrationBlocked ? (
                <button type="button" className="cart-notice__replace" onClick={onReplace}>Xóa dữ liệu giỏ cũ và lưu giỏ đã xác thực</button>
            ) : (
                <button type="button" onClick={onDismiss} aria-label="Đóng thông báo">×</button>
            )}
        </div>
    );
}

export default function CartPage() {
    const { items, updateQuantity, removeItem, cartNotice, dismissCartNotice, migrationBlocked, replaceUnmigratedCart } = useCart();
    const { isAuthenticated, isLoading: authLoading } = useAuth();
    const [backendCart, setBackendCart] = useState(null);
    const [backendCartMessage, setBackendCartMessage] = useState('');
    const commerceCartEnabled = config.features.cart;
    useEffect(() => {
        let current = true;
        if (!commerceCartEnabled || authLoading || !isAuthenticated) {
            setBackendCart(null);
            return () => { current = false; };
        }
        commerceApi.getCart()
            .then(result => { if (current) { setBackendCart(result); setBackendCartMessage(''); } })
            .catch(error => {
                if (current) setBackendCartMessage(error?.response?.status === 401
                    ? 'Phiên đăng nhập đã hết hạn; vui lòng đăng nhập lại để xem giỏ Backend.'
                    : 'Không thể tải giỏ hàng Backend hiện tại. Dữ liệu giỏ trên thiết bị vẫn được giữ nguyên.');
            });
        return () => { current = false; };
    }, [commerceCartEnabled, authLoading, isAuthenticated]);
    const backendItems = Array.isArray(backendCart?.items) ? backendCart.items : [];
    const backendTotal = backendItems.reduce((sum, item) => Number.isSafeInteger(item.unitPriceVnd) && Number.isSafeInteger(item.quantity)
        ? sum + item.unitPriceVnd * item.quantity : sum, 0);
    const subtotal = items.reduce((total, item) => total + (item.priceNum + item.materialPremium) * item.quantity, 0);

    return (
        <div className="cart-page">
            <div className="cart-page__inner">
                <RestoreNotice
                    notice={cartNotice}
                    onDismiss={dismissCartNotice}
                    migrationBlocked={migrationBlocked}
                    onReplace={replaceUnmigratedCart}
                />
                <header className="cart-page__header">
                    <div>
                        <p className="cart-eyebrow">Pocket Joy · Giỏ hàng</p>
                        <h1>Những món bạn đã chọn</h1>
                    </div>
                    <Link to="/marketplace" className="cart-text-link">← Tiếp tục khám phá</Link>
                </header>

                {commerceCartEnabled && <section className="cart-backend" aria-label="Giỏ hàng thương mại trên Backend">
                    <h2>Giỏ hàng đã xác nhận trên tài khoản</h2>
                    {!isAuthenticated ? <p>Đăng nhập để xem giỏ thương mại đã lưu trên Backend.</p>
                        : backendCartMessage ? <p role="alert">{backendCartMessage}</p>
                        : !backendCart ? <p role="status">Đang tải giỏ hàng Backend…</p>
                        : backendItems.length === 0 ? <p>Giỏ Backend đang trống. Các lựa chọn mua hàng từ trang sản phẩm sẽ xuất hiện tại đây.</p>
                        : <>
                            <ul>{backendItems.map(item => <li key={item.id}>
                                Sản phẩm #{item.modelId} · vật liệu #{item.materialId} · máy in #{item.printerId} · số lượng {item.quantity}
                                {Number.isSafeInteger(item.unitPriceVnd) ? ` · ${formatVND(item.unitPriceVnd)} / sản phẩm` : ' · chưa có số tiền VND hợp lệ'}
                                {item.estimateExpiresAt ? ` · estimate hết hạn ${new Date(item.estimateExpiresAt).toLocaleString('vi-VN')}` : ''}
                            </li>)}</ul>
                            <p>Tổng từ Backend: <strong>{formatVND(backendTotal)}</strong></p>
                            <Link to="/checkout" className="cart-button cart-button--primary">Tiếp tục Checkout</Link>
                        </>}
                </section>}

                {items.length === 0 ? (
                    <section className="cart-empty" aria-labelledby="cart-empty-title">
                        <div className="cart-empty__art" aria-hidden="true"><span>♡</span><i>✦</i></div>
                        <p className="cart-eyebrow">Một chiếc giỏ đang chờ niềm vui</p>
                        <h2 id="cart-empty-title">Giỏ hàng đang trống</h2>
                        <p>Các sản phẩm bạn chọn sẽ xuất hiện ở đây. Hiện chưa có sản phẩm nào được duyệt để bán.</p>
                        <Link to="/marketplace" className="cart-button cart-button--secondary">Khám phá cửa hàng</Link>
                    </section>
                ) : (
                    <div className="cart-layout">
                        <section className="cart-items" aria-label="Sản phẩm trong giỏ">
                            <div className="cart-items__heading"><strong>{items.length} sản phẩm</strong><span>Giá tham khảo từ dữ liệu giỏ hàng trên thiết bị</span></div>
                            {items.map(item => (
                                <article className="cart-item" key={item.key}>
                                    <ProductImage item={item} />
                                    <div className="cart-item__details">
                                        <h2>{item.title}</h2>
                                        <p className="cart-item__options">{[item.variantLabel, item.materialName].filter(Boolean).join(' · ') || 'Tuỳ chọn mặc định'}</p>
                                        <p className="cart-item__price">{formatVND(item.priceNum + item.materialPremium)} <span>/ sản phẩm · tham khảo</span></p>
                                    </div>
                                    <div className="cart-item__controls">
                                        <button type="button" className="cart-remove" onClick={() => removeItem(item.key)} aria-label={`Xóa ${item.title} khỏi giỏ`}>Xóa</button>
                                        <div className="cart-quantity" aria-label={`Số lượng ${item.title}`}>
                                            <button type="button" onClick={() => updateQuantity(item.key, item.quantity - 1)} aria-label="Giảm số lượng">−</button>
                                            <span>{item.quantity}</span>
                                            <button type="button" onClick={() => updateQuantity(item.key, item.quantity + 1)} aria-label="Tăng số lượng">+</button>
                                        </div>
                                    </div>
                                </article>
                            ))}
                        </section>

                        <aside className="cart-summary" aria-label="Tóm tắt giỏ hàng">
                            <p className="cart-eyebrow">Tóm tắt</p>
                            <h2>Đơn hàng của bạn</h2>
                            <div className="cart-summary__row"><span>Tổng giá tham khảo</span><strong>{formatVND(subtotal)}</strong></div>
                            <div className="cart-summary__shipping">Phí vận chuyển chưa được xác định.</div>
                            <div className="cart-summary__total"><span>Tổng tham khảo — chưa phải số tiền phải thanh toán</span><strong>{formatVND(subtotal)}</strong></div>
                            <Link to="/checkout" className="cart-button cart-button--primary">Xem thông tin đặt hàng <span aria-hidden="true">→</span></Link>
                            <p className="cart-summary__note">Giá, phí vận chuyển và báo giá chính thức chưa được hệ thống xác nhận. Đặt hàng và thanh toán trực tuyến hiện chưa được hỗ trợ.</p>
                        </aside>
                    </div>
                )}
            </div>
        </div>
    );
}
