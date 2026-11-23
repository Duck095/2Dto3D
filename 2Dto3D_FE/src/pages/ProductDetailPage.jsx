import { lazy, Suspense, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getProductDetailState, getProductLoadErrorMessage, isProductNotFound } from '../features/storefront/productApi';
import { getApprovedProductMedia, selectAllowedMeshColor } from '../features/storefront/productPresentation';
import { productApi } from '../services/api/productApi';
import { commerceApi } from '../features/commerce/commerceApiClient';
import { config } from '../app/config';
import { useAuth } from '../context/AuthContext';
import './product-detail.css';

const ThreeDViewer = lazy(() => import('../components/ThreeDViewer'));

function ProductState({ title, message, action = false, onRetry }) {
    return <div className="joy-detail joy-detail--unavailable">
        <div className="joy-detail__unavailable-card" role={title === getProductLoadErrorMessage('detail') ? 'alert' : 'status'}>
            <span className="joy-detail__doodle" aria-hidden="true">✳</span>
            <span className="joy-detail__kicker">A LITTLE PAUSE</span>
            <h1>{title}</h1>
            <p>{message}</p>
            {onRetry && <button type="button" className="joy-detail__button joy-detail__button--secondary" onClick={onRetry}>Thử lại</button>}
            {action && <Link to="/marketplace" className="joy-detail__button joy-detail__button--primary">Khám phá cửa hàng</Link>}
        </div>
    </div>;
}

export default function ProductDetailPage() {
    const { isAuthenticated, user } = useAuth();
    const { id } = useParams();
    const [product, setProduct] = useState(null);
    const [state, setState] = useState('loading');
    const [activeImage, setActiveImage] = useState(0);
    const [showModel, setShowModel] = useState(false);
    const [meshColorSelections, setMeshColorSelections] = useState({});
    const [retry, setRetry] = useState(0);
    const [quoteOptions, setQuoteOptions] = useState([]);
    const [selection, setSelection] = useState('');
    const [quoteMessage, setQuoteMessage] = useState('');
    const [quoteBusy, setQuoteBusy] = useState(false);

    const commerceEnabled = config.features.printQuote && config.features.cart;

    useEffect(() => {
        let current = true;
        if (!commerceEnabled || !isAuthenticated || !product?.id) {
            setQuoteOptions([]);
            setSelection('');
            setQuoteMessage('');
            return () => { current = false; };
        }
        setQuoteOptions([]);
        setSelection('');
        setQuoteMessage('');
        commerceApi.getEstimateOptions(Number(product.id))
            .then(result => {
                if (!current) return;
                const options = Array.isArray(result?.options) ? result.options : [];
                setQuoteOptions(options);
                setSelection(options.length ? `${options[0].materialId}:${options[0].printerId}:${options[0].configurationFingerprint}` : '');
            })
            .catch(error => {
                if (current) setQuoteMessage(error?.response?.status === 401
                    ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để xem tùy chọn in.'
                    : 'Chưa có cấu hình in và dữ liệu slicing đã xác thực cho sản phẩm này.');
            });
        return () => { current = false; };
    }, [commerceEnabled, isAuthenticated, user?.id, user?.email, product?.id]);

    async function createEstimateAndAddToCart() {
        const option = quoteOptions.find(item => `${item.materialId}:${item.printerId}:${item.configurationFingerprint}` === selection);
        if (!option || quoteBusy) return;
        setQuoteBusy(true);
        setQuoteMessage('');
        try {
            const estimate = await commerceApi.createEstimate({ modelId: Number(product.id), ...option });
            if (estimate?.status !== 'VALID' || !Number.isSafeInteger(estimate?.totalPriceVnd) || estimate.totalPriceVnd <= 0) {
                throw new Error('PRICING_NOT_CONFIGURED');
            }
            await commerceApi.addCartItem({ modelId: Number(product.id), estimateId: estimate.estimateId, quantity: 1 });
            setQuoteMessage('Đã thêm vào giỏ hàng Backend với ước tính còn hiệu lực.');
        } catch (error) {
            const code = error?.response?.data?.error || error?.response?.data?.code || error?.message;
            setQuoteMessage(code === 'ESTIMATE_EXPIRED' ? 'Ước tính đã hết hạn; hãy tạo lại.'
                : code === 'PRICING_NOT_CONFIGURED' ? 'Báo giá chưa được cấu hình đầy đủ.'
                : error?.response?.status === 401 ? 'Vui lòng đăng nhập để tiếp tục.'
                : 'Không thể tạo ước tính hoặc thêm sản phẩm vào giỏ. Giỏ hàng hiện tại vẫn được giữ nguyên.');
        } finally {
            setQuoteBusy(false);
        }
    }

    useEffect(() => {
        const controller = new AbortController();
        setProduct(null);
        setState('loading');
        setActiveImage(0);
        setShowModel(false);
        setMeshColorSelections({});
        productApi.getProduct(id, { signal: controller.signal })
            .then(data => { setProduct(data); setState('ready'); })
            .catch(error => {
                if (error.name === 'CanceledError' || error.name === 'AbortError') return;
                setState(isProductNotFound(error) ? 'not-found' : 'error');
            });
        return () => controller.abort();
    }, [id, retry]);

    const detailState = getProductDetailState({ loading: state === 'loading', notFound: state === 'not-found', error: state === 'error', product });
    if (detailState === 'loading') return <ProductState title="Đang tải sản phẩm…" message="Đang lấy thông tin sản phẩm công khai." />;
    if (detailState === 'not-found' || detailState === 'empty') return <ProductState title="Sản phẩm này chưa khả dụng" message="Không tìm thấy sản phẩm công khai với đường dẫn này." action />;
    if (detailState === 'error') return <ProductState title={getProductLoadErrorMessage('detail')} message="Sản phẩm chưa được hiển thị vì không thể xác nhận dữ liệu hiện tại." action onRetry={() => setRetry(value => value + 1)} />;

    const media = getApprovedProductMedia(product);
    const activeImageUrl = media.images[activeImage] || media.images[0] || '';
    const dimensions = product.publicModelDimensions;

    return (
        <div className="joy-detail">
            <nav className="joy-detail__breadcrumbs" aria-label="Đường dẫn">
                <Link to="/">Trang chủ</Link><span aria-hidden="true">/</span>
                <Link to="/marketplace">Cửa hàng</Link><span aria-hidden="true">/</span>
                <span aria-current="page">{product.title}</span>
            </nav>

            <section className="joy-detail__top">
                <div className="joy-detail__gallery">
                    <div className="joy-detail__stage">
                        {showModel && media.modelUrl ? (
                            <div className="joy-detail__model-viewer">
                                <Suspense fallback={<p className="joy-detail__loading" role="status">Đang tải bản xem trước 3D…</p>}>
                                    <ThreeDViewer
                                        modelUrl={media.modelUrl}
                                        modelErrorMessage="Không thể tải bản xem trước 3D cho sản phẩm này. Vui lòng thử lại sau."
                                        imgSrc={activeImageUrl || undefined}
                                        dimensions={dimensions ? `${dimensions.x} x ${dimensions.y} x ${dimensions.z} mm` : undefined}
                                        partDefinitions={product.publicModelParts}
                                        meshAppearances={product.modelMeshAppearances}
                                        selectedMeshColors={meshColorSelections}
                                        allowColorCustomization={false}
                                        preserveOriginalMaterials
                                        allowDimensionCustomization={false}
                                        allowSegmentedCustomization={false}
                                        allowDownload={false}
                                    />
                                </Suspense>
                            </div>
                        ) : activeImageUrl ? (
                            <img className="joy-detail__main-image" src={activeImageUrl} alt={product.title} fetchPriority="high" />
                        ) : (
                            <div className="joy-detail__media-empty" role="status">
                                <span aria-hidden="true">✳</span>
                                <strong>Chưa có ảnh sản phẩm</strong>
                                <small>Hình ảnh sẽ xuất hiện khi có dữ liệu công khai.</small>
                            </div>
                        )}
                    </div>
                    {showModel && product.modelMeshAppearances?.some(item => item.customizable && item.allowedColors.length > 0) && <section className="joy-detail__mesh-colors" aria-label="Màu bộ phận sản phẩm">
                        <h2>Chọn màu bộ phận</h2>
                        {product.modelMeshAppearances.filter(item => item.customizable && item.allowedColors.length > 0).map((appearance, index) => {
                            const key = `${appearance.nodeIndex}:${appearance.meshIndex}:${appearance.primitiveIndex}`;
                            const selected = selectAllowedMeshColor(appearance, meshColorSelections[key]) || appearance.defaultColor;
                            return <label key={key}><span>Bộ phận {index + 1} · Node {appearance.nodeIndex} / Mesh {appearance.meshIndex} / Primitive {appearance.primitiveIndex}</span>
                                <select aria-label={`Màu bộ phận ${index + 1}`} value={selected.id} onChange={event => {
                                    const allowed = selectAllowedMeshColor(appearance, Number(event.target.value));
                                    if (allowed && String(allowed.id) === event.target.value) setMeshColorSelections(current => ({ ...current, [key]: allowed.id }));
                                }}>{appearance.allowedColors.map(color => <option key={color.id} value={color.id}>{color.name}</option>)}</select>
                            </label>;
                        })}
                    </section>}
                    {(media.images.length > 1 || media.modelUrl) && <div className="joy-detail__media-controls" aria-label="Tư liệu sản phẩm">
                        {media.images.map((image, index) => <button
                            type="button" key={image} aria-label={'Xem ảnh ' + (index + 1)}
                            aria-pressed={!showModel && activeImage === index}
                            className={!showModel && activeImage === index ? 'is-active' : ''}
                            onClick={() => { setShowModel(false); setActiveImage(index); }}
                        ><img src={image} alt="" loading="lazy" /></button>)}
                        {media.modelUrl && <button type="button" aria-pressed={showModel} className={'joy-detail__media-chip' + (showModel ? ' is-active' : '')} onClick={() => setShowModel(value => !value)}>Xem mô hình 3D</button>}
                    </div>}
                </div>

                <div className="joy-detail__summary">
                    {product.category && <div className="joy-detail__eyebrows"><span>{product.category}</span></div>}
                    <span className="joy-detail__kicker">A LITTLE THING, MADE FOR YOU</span>
                    <h1>{product.title}</h1>
                    {product.description && <p className="joy-detail__description">{product.description}</p>}
                    <div className="joy-detail__price-row"><span className="joy-detail__price-note">Giá được xác định bởi Backend sau khi chọn cấu hình in</span></div>
                    <p className="joy-detail__availability" role="status">Không có giá tham khảo phía trình duyệt; estimate cần dữ liệu slicing và chính sách giá hợp lệ.</p>
                    {commerceEnabled && <div className="joy-detail__commerce" aria-label="Ước tính và thêm vào giỏ">
                        {!isAuthenticated ? <p>Vui lòng <Link to="/login">đăng nhập</Link> để chọn cấu hình và tạo ước tính.</p> : <>
                            <label htmlFor="print-option">Vật liệu, máy in và cấu hình slicing</label>
                            <select id="print-option" value={selection} onChange={event => setSelection(event.target.value)} disabled={!quoteOptions.length || quoteBusy}>
                                {quoteOptions.length ? quoteOptions.map(option => {
                                    const value = `${option.materialId}:${option.printerId}:${option.configurationFingerprint}`;
                                    return <option key={value} value={value}>{option.materialName} · {option.printerName}</option>;
                                }) : <option value="">Chưa có cấu hình in đã xác thực</option>}
                            </select>
                            <button type="button" disabled={!quoteOptions.length || quoteBusy} onClick={createEstimateAndAddToCart}>
                                {quoteBusy ? 'Đang xác nhận với Backend…' : 'Tạo ước tính và thêm vào giỏ'}
                            </button>
                        </>}
                        {quoteMessage && <p role="status">{quoteMessage}</p>}
                    </div>}
                    <div className="joy-detail__trust-note"><span aria-hidden="true">✳</span><p>Thông tin sản phẩm lấy từ danh mục công khai.</p></div>
                </div>
            </section>

            <section className="joy-detail__information" aria-labelledby="product-information-title">
                <div className="joy-detail__section-heading">
                    <span className="joy-detail__kicker">THE LITTLE DETAILS</span>
                    <h2 id="product-information-title">Về món đồ này<span>.</span></h2>
                </div>
                <div className="joy-detail__information-grid">
                    <article className="joy-detail__description-card">
                        <h3>Thông tin sản phẩm</h3>
                        <p>{product.description || 'Chưa có mô tả công khai cho sản phẩm này.'}</p>
                    </article>
                    {dimensions && <article className="joy-detail__facts-card">
                        <h3>Kích thước mô hình</h3>
                        <dl>
                            <div><dt>Chiều rộng</dt><dd>{dimensions.x} mm</dd></div>
                            <div><dt>Chiều cao</dt><dd>{dimensions.y} mm</dd></div>
                            <div><dt>Chiều sâu</dt><dd>{dimensions.z} mm</dd></div>
                        </dl>
                    </article>}
                </div>
            </section>
        </div>
    );
}
