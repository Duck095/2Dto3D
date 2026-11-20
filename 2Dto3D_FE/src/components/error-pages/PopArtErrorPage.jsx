import { Link } from 'react-router-dom';
import approvedLogo from '../../assets/approved-3d-keychain-logo.webp';
import './pop-art-error-pages.css';

export default function PopArtErrorPage({
  variant,
  badge,
  title,
  message,
  illustration,
  illustrationAlt,
  children,
  actions = [],
  nativeNavigation = false,
}) {
  const BrandLink = nativeNavigation ? 'a' : Link;
  const brandNavigationProps = nativeNavigation ? { href: '/' } : { to: '/' };

  return (
    <main className={`pop-error pop-error--${variant}`} aria-labelledby="pop-error-title">
      <div className="pop-error__shell">
        <header className="pop-error__brand">
          <BrandLink {...brandNavigationProps} aria-label="2Dto3D Pocket Joy — về trang chủ">
            <img src={approvedLogo} alt="" aria-hidden="true" />
            <span>2Dto3D <small>Pocket Joy</small></span>
          </BrandLink>
        </header>

        <div className="pop-error__layout">
          <div className="pop-error__art" role="img" aria-label={illustrationAlt}>
            <span className="pop-error__burst" aria-hidden="true" />
            <span className="pop-error__dots" aria-hidden="true" />
            <img src={illustration} alt="" aria-hidden="true" />
          </div>

          <section className="pop-error__content">
            <div className="pop-error__speech">
              <span className="pop-error__badge">{badge}</span>
              <h1 id="pop-error-title">{title}</h1>
            </div>
            <p className="pop-error__message">{message}</p>
            {children}
            <nav className="pop-error__actions" aria-label="Tùy chọn khắc phục">
              {actions.map(({ label, to, onClick, href, icon: Icon, trailingIcon: TrailingIcon, kind = 'secondary', ...props }) => {
                const className = `pop-error__button pop-error__button--${kind}`;
                const content = <>{Icon && <Icon aria-hidden="true" />}{label}{TrailingIcon && <TrailingIcon aria-hidden="true" />}</>;
                if (to) return nativeNavigation
                  ? <a key={label} href={to} className={className} {...props}>{content}</a>
                  : <Link key={label} to={to} className={className} {...props}>{content}</Link>;
                if (href) return <a key={label} href={href} className={className} {...props}>{content}</a>;
                return <button key={label} type="button" onClick={onClick} className={className} {...props}>{content}</button>;
              })}
            </nav>
            <p className="pop-error__signature">2Dto3D <span>·</span> Tiny things, big feelings</p>
          </section>
        </div>
      </div>
    </main>
  );
}
