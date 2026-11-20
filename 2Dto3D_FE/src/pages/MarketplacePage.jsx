import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getCatalogState, getProductLoadErrorMessage } from '../features/storefront/productApi';
import { productApi } from '../services/api/productApi';
import ProductCard from '../components/storefront/ProductCard';
import './marketplace.css';

const PAGE_SIZE = 12;
const MAX_BACKEND_PAGE = 2_147_483_647;

export function parseCatalogPage(values) {
    if (!Array.isArray(values) || values.length !== 1 || !/^\d+$/.test(values[0])) return 0;
    const page = Number(values[0]);
    return Number.isSafeInteger(page) && page <= MAX_BACKEND_PAGE ? page : 0;
}

export default function MarketplacePage() {
    const [searchParams, setSearchParams] = useSearchParams();
    const rawPageValues = searchParams.getAll('page');
    const rawPageKey = JSON.stringify(rawPageValues);
    const pageFromUrl = parseCatalogPage(rawPageValues);
    const searchQuery = (searchParams.get('search') || '').trim();
    const [searchDraft, setSearchDraft] = useState(searchQuery);
    const [sort, setSort] = useState('newest');
    const [sortResetPending, setSortResetPending] = useState(false);
    const [retry, setRetry] = useState(0);
    const page = sortResetPending ? 0 : pageFromUrl;
    const requestKey = JSON.stringify({ page, searchQuery, sort, retry });
    const activeRequestRef = useRef(null);
    const [requestState, setRequestState] = useState({ key: null, status: 'loading', data: null });

    useEffect(() => setSearchDraft(searchQuery), [searchQuery]);

    useEffect(() => {
        if (rawPageValues.length === 0) return;
        const canonicalPage = rawPageValues.length === 1 && pageFromUrl > 0 ? String(pageFromUrl) : null;
        if (canonicalPage !== rawPageValues[0] || rawPageValues.length !== 1) {
            setSearchParams(current => {
                const next = new URLSearchParams(current);
                next.delete('page');
                if (canonicalPage !== null) next.set('page', canonicalPage);
                return next;
            }, { replace: true });
        }
    }, [rawPageKey, pageFromUrl, rawPageValues.length, rawPageValues[0], setSearchParams]);

    useEffect(() => {
        if (sortResetPending && pageFromUrl === 0) setSortResetPending(false);
    }, [sortResetPending, pageFromUrl]);

    useEffect(() => {
        const controller = new AbortController();
        const requestIdentity = {};
        activeRequestRef.current = requestIdentity;
        setRequestState({ key: requestKey, status: 'loading', data: null });
        productApi.getProducts({ page, size: PAGE_SIZE, q: searchQuery, sort, signal: controller.signal })
            .then(data => {
                if (activeRequestRef.current === requestIdentity) {
                    setRequestState({ key: requestKey, status: 'success', data });
                }
            })
            .catch(requestError => {
                if (activeRequestRef.current === requestIdentity
                    && requestError.name !== 'CanceledError'
                    && requestError.name !== 'AbortError') {
                    setRequestState({ key: requestKey, status: 'error', data: null });
                }
            });
        return () => {
            controller.abort();
            if (activeRequestRef.current === requestIdentity) activeRequestRef.current = null;
        };
    }, [page, searchQuery, sort, retry, requestKey]);

    const hasCurrentRequest = requestState.key === requestKey;
    const result = hasCurrentRequest && requestState.status === 'success' ? requestState.data : null;
    const loading = !hasCurrentRequest || requestState.status === 'loading';
    const error = hasCurrentRequest && requestState.status === 'error';

    useEffect(() => {
        if (!result || page === 0 || page < result.totalPages) return;
        const lastValidPage = Math.max(0, result.totalPages - 1);
        if (lastValidPage !== page) {
            setSearchParams(current => {
                const next = new URLSearchParams(current);
                next.delete('page');
                if (lastValidPage > 0) next.set('page', String(lastValidPage));
                return next;
            }, { replace: true });
        }
    }, [page, requestKey, result, setSearchParams]);

    const updateQuery = useCallback((values) => {
        setSearchParams(current => {
            const next = new URLSearchParams(current);
            for (const [key, value] of Object.entries(values)) {
                if (value) next.set(key, value);
                else next.delete(key);
            }
            return next;
        });
    }, [setSearchParams]);

    function submitSearch(event) {
        event.preventDefault();
        updateQuery({ search: searchDraft.trim(), page: '' });
    }

    function clearSearch() {
        setSearchDraft('');
        updateQuery({ search: '', page: '' });
    }

    function changePage(nextPage) {
        updateQuery({ page: nextPage === 0 ? '' : String(nextPage) });
        document.getElementById('catalog-title')?.focus({ preventScroll: true });
    }

    const products = result?.items || [];
    const count = result?.totalElements ?? 0;
    const catalogState = getCatalogState({ loading, error, items: products });

    return (
        <div className="joy-shop">
            <header className="joy-shop__hero">
                <div className="joy-shop__wrap">
                    <span className="joy-shop__eyebrow"><i aria-hidden="true" /> POCKET JOY · THE LITTLE THINGS SHOP</span>
                    <h1>Small things.<br /><span>Big feelings.</span></h1>
                    <p>Móc khóa vui tay, món đồ sưu tầm dễ thương và những món quà mang dấu ấn riêng.</p>
                    <span className="joy-shop__hero-note">Khám phá các sản phẩm đang được công khai ✳</span>
                </div>
                <span className="joy-shop__hero-art" aria-hidden="true"><b>✳</b><i>little<br />joy</i><small>POCKET SIZE · BIG MOOD</small></span>
            </header>

            <div className="joy-shop__main">
                <section className="joy-shop__catalog" aria-labelledby="catalog-title">
                    <div className="joy-shop__catalog-heading">
                        <div><span className="joy-shop__kicker">MADE TO MAKE YOU SMILE</span><h2 id="catalog-title" tabIndex="-1">The collection<span>.</span></h2></div>
                        <span className="joy-shop__result-count" aria-live="polite">{loading ? 'Đang tải…' : `${count} sản phẩm`}</span>
                    </div>

                    <form className="joy-shop__toolbar" role="search" onSubmit={submitSearch}>
                        <label className="joy-shop__search">
                            <span aria-hidden="true">⌕</span>
                            <span className="sr-only">Tìm sản phẩm</span>
                            <input type="search" value={searchDraft} onChange={event => setSearchDraft(event.target.value)} placeholder="Tìm sản phẩm bạn thích..." />
                        </label>
                        <button className="joy-shop-button joy-shop-button--primary" type="submit">Tìm kiếm</button>
                        <label className="joy-shop__sort">
                            <span>Sắp xếp</span>
                            <select value={sort} onChange={event => {
                                setSortResetPending(pageFromUrl > 0);
                                setSort(event.target.value);
                                updateQuery({ page: '' });
                            }}>
                                <option value="newest">Mới nhất</option>
                                <option value="name_asc">Tên: A đến Z</option>
                                <option value="name_desc">Tên: Z đến A</option>
                            </select>
                        </label>
                    </form>

                    {catalogState === 'error' ? (
                        <div className="joy-shop__empty" role="alert">
                            <span className="joy-shop__empty-icon" aria-hidden="true">!</span>
                            <h3>{getProductLoadErrorMessage()}</h3>
                            <p>Sản phẩm chưa được hiển thị vì không thể xác nhận dữ liệu hiện tại.</p>
                            <button type="button" className="joy-shop-button joy-shop-button--secondary" onClick={() => setRetry(value => value + 1)}>Thử lại</button>
                        </div>
                    ) : catalogState === 'loading' ? (
                        <div className="joy-shop__empty" role="status" aria-live="polite"><span className="joy-shop__empty-icon" aria-hidden="true">✳</span><h3>Đang tải sản phẩm…</h3></div>
                    ) : catalogState === 'ready' ? (
                        <>
                            <div className="joy-shop__grid">{products.map(product => <ProductCard key={product.id} product={product} />)}</div>
                            {result.totalPages > 1 && <nav className="joy-shop__pagination" aria-label="Phân trang sản phẩm">
                                <button type="button" className="joy-shop-button joy-shop-button--secondary" disabled={page <= 0} onClick={() => changePage(page - 1)}>Trước</button>
                                <span aria-live="polite">Trang {page + 1} / {result.totalPages}</span>
                                <button type="button" className="joy-shop-button joy-shop-button--secondary" disabled={page + 1 >= result.totalPages} onClick={() => changePage(page + 1)}>Tiếp</button>
                            </nav>}
                        </>
                    ) : (
                        <div className="joy-shop__empty" role="status">
                            <span className="joy-shop__empty-icon" aria-hidden="true">✳</span>
                            <span className="joy-shop__kicker">{searchQuery ? 'NOT FOUND YET' : 'COMING WITH JOY'}</span>
                            <h3>{searchQuery ? 'Chưa tìm thấy sản phẩm phù hợp' : 'Đang chuẩn bị những niềm vui đầu tiên'}</h3>
                            <p>{searchQuery ? 'Thử từ khóa khác để tìm sản phẩm nhé.' : 'Hiện chưa có sản phẩm công khai để trưng bày. Ghé lại sau nhé!'}</p>
                            {searchQuery && <button type="button" className="joy-shop-button joy-shop-button--secondary" onClick={clearSearch}>Xem tất cả sản phẩm</button>}
                            <span className="joy-shop__empty-stamp" aria-hidden="true">TINY<br />THINGS<br />BIG FEELINGS</span>
                        </div>
                    )}
                </section>
            </div>
        </div>
    );
}
