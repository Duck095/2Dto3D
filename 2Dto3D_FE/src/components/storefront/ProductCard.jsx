import { Link } from 'react-router-dom';
import { getApprovedProductMedia } from '../../features/storefront/productPresentation';
import './product-card.css';

export default function ProductCard({ product, compact = false }) {
    const media = getApprovedProductMedia(product);
    const detailPath = '/product/' + product.id;

    return (
        <article className={'joy-product-card' + (compact ? ' joy-product-card--compact' : '')}>
            <Link className="joy-product-card__media" to={detailPath} aria-label={'Xem ' + product.title}>
                {media.images[0]
                    ? <img src={media.images[0]} alt={product.title} loading="lazy" />
                    : <span className="joy-product-card__placeholder" aria-label="Chưa có ảnh sản phẩm được duyệt">
                        <span aria-hidden="true">✳</span>
                        <small>ẢNH SẢN PHẨM SẼ CẬP NHẬT</small>
                    </span>}
                {product.category && <span className="joy-product-card__collection">{product.category}</span>}
            </Link>
            <div className="joy-product-card__body">
                <div className="joy-product-card__heading">
                    <Link to={detailPath} className="joy-product-card__title">{product.title}</Link>
                    <span className="joy-product-card__price-note">Giá chưa công bố</span>
                </div>
                {!compact && product.description && <p className="joy-product-card__description">{product.description}</p>}
                <div className="joy-product-card__actions">
                    <Link className="joy-shop-button joy-shop-button--secondary" to={detailPath}>Xem chi tiết</Link>
                </div>
            </div>
        </article>
    );
}
