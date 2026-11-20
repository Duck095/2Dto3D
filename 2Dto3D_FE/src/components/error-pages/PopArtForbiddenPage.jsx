import { HiArrowRight, HiOutlineHome, HiOutlineLifebuoy } from 'react-icons/hi2';
import PopArtErrorPage from './PopArtErrorPage.jsx';

export default function PopArtForbiddenPage({ workspacePath, supportUrl }) {
  const actions = [
    { label: 'Quay về trang chủ', to: '/', icon: HiOutlineHome, kind: 'primary', trailingIcon: HiArrowRight },
    ...(supportUrl ? [{ label: 'Liên hệ hỗ trợ', href: supportUrl, icon: HiOutlineLifebuoy, target: '_blank', rel: 'noreferrer' }] : []),
    ...(workspacePath ? [{ label: 'Về không gian làm việc', to: workspacePath, icon: HiArrowRight, kind: 'text' }] : []),
  ];

  return (
    <PopArtErrorPage
      variant="forbidden"
      badge="Truy cập giới hạn"
      title={<>Oops! Khu vực này <em>chưa mở</em> cho bạn</>}
      message="Tài khoản hiện tại chưa có quyền truy cập không gian này. Bạn có thể quay về khu vực an toàn hoặc liên hệ quản trị viên nếu cần hỗ trợ."
      illustration="/images/error-pages/forbidden.webp"
      illustrationAlt="Mascot Pocket Joy bên cạnh khối hologram trên đảo nổi có biển giới hạn truy cập"
      actions={actions}
    />
  );
}
