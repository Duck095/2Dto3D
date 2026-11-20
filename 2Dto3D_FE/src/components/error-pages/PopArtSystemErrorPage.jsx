import { HiArrowPath, HiOutlineHome, HiOutlineLifebuoy } from 'react-icons/hi2';
import PopArtErrorPage from './PopArtErrorPage.jsx';

export default function PopArtSystemErrorPage({ onRetry, supportUrl, nativeNavigation = false }) {
  const actions = [
    ...(onRetry ? [{ label: 'Thử lại ngay', onClick: onRetry, icon: HiArrowPath, kind: 'primary' }] : []),
    { label: 'Quay về trang chủ', to: '/', icon: HiOutlineHome },
    ...(supportUrl ? [{ label: 'Liên hệ hỗ trợ', href: supportUrl, icon: HiOutlineLifebuoy, target: '_blank', rel: 'noreferrer', kind: 'text' }] : []),
  ];

  return (
    <PopArtErrorPage
      variant="system-error"
      badge="Sự cố tạm thời"
      title={<>Oops! Hệ thống đang gặp <em>trục trặc nhỏ!</em></>}
      message="Hệ thống đang xử lý một sự cố ngoài ý muốn. Bạn thử lại sau ít phút nhé!"
      illustration="/images/error-pages/system-error.webp"
      illustrationAlt="Mascot Pocket Joy lo lắng bên máy chủ, màn hình cảnh báo và rào chắn"
      nativeNavigation={nativeNavigation}
      actions={actions}
    />
  );
}
