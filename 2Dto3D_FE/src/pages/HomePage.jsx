import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { HiArrowRight, HiOutlineBolt, HiOutlineGift, HiOutlineSparkles, HiOutlineHeart, HiOutlinePlay, HiOutlineCube } from 'react-icons/hi2';
import { productApi } from '../services/api/productApi';
import ProductCard from '../components/storefront/ProductCard';
import './homepage.css';

const vibes = [
  { title: 'Clicky & Fidget', note: 'Bấm một cái, vui một chút.', icon: HiOutlineBolt, tone: 'pink' },
  { title: 'Cute & Sweet', note: 'Nhỏ xinh mang theo mỗi ngày.', icon: HiOutlineHeart, tone: 'lavender' },
  { title: 'Characters & Collectibles', note: 'Sưu tầm điều làm bạn vui.', icon: HiOutlineSparkles, tone: 'mint' },
  { title: 'Personalized Gifts', note: 'Thêm dấu ấn của riêng bạn.', icon: HiOutlineGift, tone: 'butter' },
];

function PocketJoyIllustration() {
  return (
    <div className="pocket-illustration" role="img" aria-label="Minh họa concept nguyên bản cho Pocket Joy, không phải ảnh sản phẩm">
      <svg className="pocket-illustration__orbit pocket-illustration__orbit--one" viewBox="0 0 500 500" aria-hidden="true" focusable="false">
        <ellipse className="pocket-illustration__orbit-track" cx="250" cy="250" rx="232" ry="168" pathLength="1000" />
        <ellipse className="pocket-illustration__orbit-glint" cx="250" cy="250" rx="232" ry="168" pathLength="1000" />
      </svg>
      <svg className="pocket-illustration__orbit pocket-illustration__orbit--two" viewBox="0 0 500 500" aria-hidden="true" focusable="false">
        <ellipse className="pocket-illustration__orbit-track" cx="250" cy="250" rx="196" ry="230" pathLength="1000" />
        <ellipse className="pocket-illustration__orbit-glint" cx="250" cy="250" rx="196" ry="230" pathLength="1000" />
      </svg>
      <span className="pocket-illustration__shadow" aria-hidden="true" />
      <span className="pocket-illustration__deco pocket-illustration__deco--butter" aria-hidden="true" />
      <span className="pocket-illustration__spark pocket-illustration__spark--one">✳</span>
      <span className="pocket-illustration__spark pocket-illustration__spark--two">✦</span>
      <div className="pocket-illustration__tag">
        <span className="pocket-illustration__loop" aria-hidden="true" />
        <span className="pocket-illustration__hole" aria-hidden="true" />
        <span className="pocket-illustration__face"><span>tiny<br />joy</span><span className="pocket-illustration__smile" aria-hidden="true">⌣</span></span>
      </div>
      <span className="pocket-illustration__caption">MINH HỌA CONCEPT · KHÔNG PHẢI ẢNH SẢN PHẨM</span>
    </div>
  );
}

export default function HomePage() {
  const [featuredState, setFeaturedState] = useState({ status: 'loading', items: [] });
  const [retryVersion, setRetryVersion] = useState(0);
  const activeRequestRef = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    const requestIdentity = {};
    activeRequestRef.current = requestIdentity;
    setFeaturedState({ status: 'loading', items: [] });

    productApi.getProducts({ page: 0, size: 4, sort: 'newest', signal: controller.signal })
      .then(response => {
        if (activeRequestRef.current !== requestIdentity) return;
        const items = Array.isArray(response?.items) ? response.items.slice(0, 4) : [];
        setFeaturedState({ status: items.length ? 'ready' : 'empty', items });
      })
      .catch(error => {
        if (activeRequestRef.current !== requestIdentity
          || error?.name === 'CanceledError'
          || error?.name === 'AbortError'
          || error?.code === 'ERR_CANCELED') return;
        setFeaturedState({ status: 'error', items: [] });
      });

    return () => {
      controller.abort();
      if (activeRequestRef.current === requestIdentity) activeRequestRef.current = null;
    };
  }, [retryVersion]);

  function retryFeaturedProducts() {
    activeRequestRef.current = null;
    setFeaturedState({ status: 'loading', items: [] });
    setRetryVersion(version => version + 1);
  }

  return (
    <div className="pocket-home">
      <section className="pocket-hero" aria-labelledby="hero-title">
        <div className="pocket-wrap pocket-hero__grid">
          <div className="pocket-hero__copy">
            <span className="pocket-eyebrow"><span /> POCKET JOY — TINY THINGS, BIG FEELINGS</span>
            <h1 id="hero-title">Small keychain.<br /><span>Big happiness.</span></h1>
            <p>Móc khóa xinh xắn, bấm vui tay và mang chút cá tính của bạn đi khắp nơi. Tìm niềm vui nhỏ hợp với mình nhé.</p>
            <Link to="/marketplace" className="pocket-button pocket-button--plum">Khám phá bộ sưu tập <HiArrowRight aria-hidden="true" /></Link>
            <span className="pocket-hero__footnote">Một chiếc nhỏ xíu, một niềm vui vừa đủ ✳</span>
          </div>
          <PocketJoyIllustration />
        </div>
      </section>

      <section className="pocket-section pocket-vibes" aria-labelledby="vibes-title">
        <div className="pocket-wrap">
          <div className="pocket-section__heading"><div><span className="pocket-kicker">FIND YOUR LITTLE THING</span><h2 id="vibes-title">Shop by vibe<span>.</span></h2><p>Hôm nay bạn muốn mang theo cảm giác nào?</p></div><Link to="/marketplace" className="pocket-text-link">Xem tất cả <HiArrowRight aria-hidden="true" /></Link></div>
          <div className="pocket-vibe-grid">
            {vibes.map(({ title, note, icon: Icon, tone }, index) => (
              <article className={`pocket-vibe pocket-vibe--${tone}`} key={title}>
                <span className="pocket-vibe__index">0{index + 1}</span><span className="pocket-vibe__icon"><Icon aria-hidden="true" /></span>
                <span className="pocket-vibe__copy"><strong>{title}</strong><small>{note}</small></span>
              </article>
            ))}
            <Link to="/products" className="pocket-vibe pocket-vibe--discovery" aria-label="Khám phá kho mẫu 3D">
              <span className="pocket-vibe__icon"><HiOutlineCube aria-hidden="true" /></span>
              <span className="pocket-vibe__copy"><strong>Khám phá kho mẫu 3D</strong><small>Duyệt thư viện mô hình 3D</small></span>
              <HiArrowRight className="pocket-vibe__discovery-arrow" aria-hidden="true" />
            </Link>
          </div>
          <Link to="/products" className="pocket-vibe-discovery-link">Khám phá kho mẫu 3D <HiArrowRight aria-hidden="true" /></Link>
        </div>
      </section>

      <section className="pocket-section pocket-featured" aria-labelledby="featured-title">
        <div className="pocket-wrap">
          <div className="pocket-section__heading"><div><span className="pocket-kicker">PUBLIC PRODUCT CATALOG</span><h2 id="featured-title">Sản phẩm mới<span>.</span></h2><p>Các sản phẩm mới nhất hiện được công khai trong danh mục.</p></div><Link to="/marketplace" className="pocket-text-link">Xem tất cả <HiArrowRight aria-hidden="true" /></Link></div>
          {featuredState.status === 'loading' && <div className="pocket-featured__state" role="status" aria-live="polite" aria-busy="true">
            <p>Đang tải sản phẩm mới…</p>
            <div className="pocket-featured__grid" aria-hidden="true">{Array.from({ length: 4 }, (_, index) => <div className="pocket-featured__skeleton" key={index}><div className="pocket-featured__skeleton-media" /><div className="pocket-featured__skeleton-body"><span /><span /><span /></div></div>)}</div>
          </div>}
          {featuredState.status === 'ready' && <div className="pocket-featured__grid">
            {featuredState.items.map(product => <ProductCard compact key={product.id} product={product} />)}
          </div>}
          {featuredState.status === 'empty' && <div className="pocket-featured__state" role="status">
            <p>Hiện chưa có sản phẩm công khai.</p>
            <Link to="/marketplace" className="pocket-featured__state-link">Xem danh mục sản phẩm <HiArrowRight aria-hidden="true" /></Link>
          </div>}
          {featuredState.status === 'error' && <div className="pocket-featured__state" role="alert">
            <p>Không thể tải danh mục sản phẩm. Vui lòng thử lại.</p>
            <button type="button" className="pocket-featured__retry" onClick={retryFeaturedProducts}>Thử lại</button>
          </div>}
        </div>
      </section>

      <section className="pocket-click" aria-labelledby="click-title">
        <div className="pocket-wrap pocket-click__inner">
          <div className="pocket-click__copy"><span className="pocket-kicker">SOUND ON, STRESS OFF</span><h2 id="click-title">See the click<span>.</span><br />Feel the happy.</h2><p>Tiếng “tách” nhỏ, cảm giác vui tay. Video sản phẩm thật sẽ được giới thiệu khi có tư liệu được cấp quyền.</p></div>
          <div className="pocket-click__media" role="status"><span className="pocket-click__play"><HiOutlinePlay aria-hidden="true" /></span><span>Chưa có video sản phẩm được cấp quyền</span><small>KHU VỰC VIDEO · SẴN SÀNG TÍCH HỢP</small></div>
        </div>
      </section>

      <section className="pocket-gift" aria-labelledby="gift-title"><div className="pocket-wrap pocket-gift__inner"><div><span className="pocket-kicker">A LITTLE JOY GOES A LONG WAY</span><h2 id="gift-title">A tiny gift.<br /><span>A big “you get me”.</span></h2><p>Món quà nhỏ dành cho người bạn hiểu rõ — hoặc cho chính bạn, vì bạn xứng đáng.</p><Link to="/marketplace" className="pocket-button pocket-button--cream">Khám phá cửa hàng <HiArrowRight aria-hidden="true" /></Link></div><div className="pocket-gift__art" aria-hidden="true"><span>for<br /><b>you</b></span><i>♥</i><small>just because</small></div></div></section>
    </div>
  );
}
