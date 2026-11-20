import { HiArrowPath, HiOutlineHome, HiOutlineMegaphone } from 'react-icons/hi2';
import PopArtErrorPage from './PopArtErrorPage.jsx';

export default function PopArtServiceUnavailablePage({ mode = 'unavailable', onRetry, noticeUrl }) {
  const isMaintenance = mode === 'maintenance';
  const actions = [
    ...(onRetry ? [{ label: 'Kiểm tra lại', onClick: onRetry, icon: HiArrowPath, kind: 'primary' }] : []),
    { label: 'Quay về trang chủ', to: '/', icon: HiOutlineHome },
    ...(noticeUrl ? [{ label: 'Xem thông báo mới nhất', href: noticeUrl, icon: HiOutlineMegaphone, target: '_blank', rel: 'noreferrer', kind: 'text' }] : []),
  ];

  return (
    <PopArtErrorPage
      variant="service-unavailable"
      badge={isMaintenance ? 'Bảo trì hệ thống' : 'Tạm thời chưa sẵn sàng'}
      title={isMaintenance ? <>Hệ thống đang được <em>bảo trì!</em></> : <>Hệ thống hiện chưa thể <em>phục vụ bạn!</em></>}
      message={isMaintenance
        ? 'Chúng tôi đang nâng cấp và tối ưu hệ thống để mang đến trải nghiệm tốt hơn. Rất mong bạn thông cảm!'
        : 'Dịch vụ hiện chưa sẵn sàng. Vui lòng thử kiểm tra lại sau ít phút nhé.'}
      illustration="/images/error-pages/service-unavailable.webp"
      illustrationAlt="Mascot kỹ thuật Pocket Joy đội mũ vàng bên màn hình bảo trì và dụng cụ"
      actions={actions}
    />
  );
}
