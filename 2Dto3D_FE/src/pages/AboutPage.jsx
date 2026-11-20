import { Link } from 'react-router-dom';

export default function AboutPage() {
  return (
    <div className="joy-about">
      <div className="joy-about__inner">
        <header className="joy-about__hero">
          <p className="joy-about__eyebrow">Về chúng tôi · 2Dto3D</p>
          <h1>Ý tưởng của bạn, gần hơn với thế giới 3D.</h1>
          <p>2Dto3D theo đuổi những ý tưởng 3D và các sản phẩm nhỏ mang niềm vui vào cuộc sống hằng ngày.</p>
        </header>

        <section className="joy-about__card" id="nen-tang">
          <span aria-hidden="true">◇</span>
          <div><p className="joy-about__eyebrow">Nền tảng</p><h2>Từ ý tưởng đến mô hình</h2>
            <p>Chúng tôi đang hoàn thiện các công cụ và quy trình để đưa ý tưởng 3D đến gần hơn với sản phẩm trưng bày.</p>
          </div>
        </section>

        <section className="joy-about__card joy-about__card--pink" id="pocket-joy">
          <span aria-hidden="true">✳</span>
          <div><p className="joy-about__eyebrow">Dòng sản phẩm</p><h2>Pocket Joy — Tiny Things, Big Feelings</h2>
            <p>Pocket Joy là hướng cửa hàng tập trung vào Clicky/Fidget Keychains, cùng các nhóm móc khóa dễ thương, đồ sưu tầm và quà tặng cá nhân hóa.</p>
            <Link to="/marketplace">Xem cửa hàng <span aria-hidden="true">→</span></Link>
          </div>
        </section>

      </div>
    </div>
  );
}
