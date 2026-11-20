import { useNavigate } from 'react-router-dom';
import { HiArrowLeft, HiArrowRight, HiOutlineCube, HiOutlineHome } from 'react-icons/hi2';
import PopArtErrorPage from '../components/error-pages/PopArtErrorPage.jsx';

export default function NotFoundPage() {
  const navigate = useNavigate();
  const goBackSafely = () => {
    if (Number.isInteger(window.history.state?.idx) && window.history.state.idx > 0) {
      navigate(-1);
      return;
    }
    navigate('/');
  };

  return (
    <PopArtErrorPage
      variant="not-found"
      badge="Đường đi lạc mất rồi"
      title={<>Không tìm thấy <em>trang này!</em></>}
      message="Có vẻ như trang bạn đang tìm không tồn tại hoặc đã được di chuyển sang vị trí khác."
      illustration="/images/error-pages/not-found.webp"
      illustrationAlt="Mascot Pocket Joy cầm kính lúp xem bản đồ trên đảo nổi xanh với biển chỉ đường"
      actions={[
        { label: 'Quay về trang chủ', to: '/', icon: HiOutlineHome, trailingIcon: HiArrowRight, kind: 'primary' },
        { label: 'Khám phá sản phẩm', to: '/products', icon: HiOutlineCube },
        { label: 'Quay lại trang trước', onClick: goBackSafely, icon: HiArrowLeft, kind: 'text' },
      ]}
    />
  );
}
