const unavailable = (id, label) => ({ id, label, to: null, available: false });

export const ADMIN_NAVIGATION = [
  {
    id: 'operations',
    label: 'ĐIỀU HÀNH',
    items: [
      { id: 'overview', label: 'Tổng quan', to: '/admin', available: true },
      { id: 'staff', label: 'Nhân sự', to: '/admin/staff', available: true },
      { id: 'tasks', label: 'Công việc', to: '/admin/tasks', available: true },
      { id: 'acceptance', label: 'Nghiệm thu', to: '/admin/acceptance', available: true },
    ],
  },
  {
    id: 'ai-3d',
    label: 'AI & 3D',
    items: [
      unavailable('ai-management', 'AI Management'),
      unavailable('library-3d', 'Thư viện 3D'),
      unavailable('production-qa', 'Sản xuất & QA'),
    ],
  },
  {
    id: 'business',
    label: 'KINH DOANH',
    items: [
      { id: 'products', label: 'Sản phẩm', to: '/admin/products', available: true },
      unavailable('orders', 'Đơn hàng'),
      unavailable('payments', 'Thanh toán'),
    ],
  },
  {
    id: 'resources',
    label: 'TÀI NGUYÊN',
    items: [
      { id: 'materials-inventory', label: 'Vật liệu & Kho', to: '/admin/materials', available: true },
      unavailable('print-equipment', 'Thiết bị in'),
    ],
  },
  {
    id: 'customers',
    label: 'KHÁCH HÀNG',
    items: [
      unavailable('customers', 'Khách hàng'),
      unavailable('membership', 'Membership'),
      unavailable('content-marketing', 'Content & Marketing'),
    ],
  },
  {
    id: 'system',
    label: 'HỆ THỐNG',
    items: [
      unavailable('configuration', 'Cấu hình'),
      unavailable('audit-monitoring', 'Nhật ký & Giám sát'),
    ],
  },
];
