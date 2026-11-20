import { config } from '../app/config.js';

const featureNavigationGroups = (features) => [
    ...(features.aiStudio ? [{
        id: 'ai',
        label: 'AI Studio',
        items: [
            { label: 'Tạo mô hình từ ảnh', to: '/studio?mode=image', description: 'Bắt đầu với một hình ảnh.' },
            { label: 'Tạo mô hình từ mô tả', to: '/studio?mode=text', description: 'Bắt đầu bằng mô tả văn bản.' },
            { label: 'Xem và tùy chỉnh mô hình 3D', to: '/studio', description: 'Mở AI Studio.' },
        ],
        richMenu: {
            icon: 'sparkles', eyebrow: 'AI STUDIO · 2DTO3D', title: 'Biến ý tưởng thành mô hình 3D.',
            description: 'Bắt đầu trong AI Studio bằng hình ảnh hoặc mô tả văn bản.', ctaIndex: 2,
            actions: [
                { itemIndex: 0, icon: 'photo', description: 'Dùng hình ảnh làm đầu vào cho mô hình 3D.' },
                { itemIndex: 1, icon: 'text', description: 'Dùng mô tả làm đầu vào cho mô hình 3D.' },
                { itemIndex: 2, icon: 'cube', description: 'Mở khu vực xem và chỉnh sửa mô hình 3D.' },
            ],
            discovery: { kind: 'steps', icon: 'route', title: 'Từ ý tưởng đến mô hình', description: 'Tạo bản nháp, sau đó xem và tinh chỉnh.', steps: ['Chọn ảnh hoặc mô tả', 'Tạo mô hình 3D', 'Xem và tinh chỉnh'] },
        },
    }] : []),
    {
        id: 'guides',
        label: 'Hướng dẫn',
        items: [
            { label: 'Quy trình sử dụng', to: '/workflow' },
            { label: 'Tài nguyên hướng dẫn', to: '/resources' },
            ...(features.aiStudio ? [
                { label: 'Bắt đầu từ hình ảnh', to: '/studio?mode=image' },
                { label: 'Bắt đầu từ mô tả', to: '/studio?mode=text' },
            ] : []),
        ],
        richMenu: {
            icon: 'book', eyebrow: 'HƯỚNG DẪN · 2DTO3D', title: 'Bắt đầu theo từng bước.',
            description: 'Xem quy trình hiện có và tài liệu đã được xác minh.', ctaIndex: 0,
            actions: [
                { itemIndex: 0, icon: 'route', description: 'Các bước sử dụng hiện có trên nền tảng.' },
                { itemIndex: 1, icon: 'book', description: 'Tài liệu hướng dẫn đã được xác minh.' },
            ],
            discovery: { kind: 'note', icon: 'info', title: 'Thông tin hiện có', description: 'Các trang sẽ thông báo rõ nội dung nào đã sẵn sàng.' },
        },
    },
    ...(features.printQuote ? [{
        id: 'printing',
        label: 'Dịch vụ in 3D',
        items: [
            ...(features.aiStudio ? [{ label: 'Chuẩn bị mô hình', to: '/studio' }] : []),
            { label: 'Tìm hiểu quy trình in', to: '/workflow#quy-trinh-in' },
            { label: 'Xem ước tính từ bản nháp', to: '/print-quote', requiresPrintDraft: true, disabledReason: 'Cần mở từ một bản nháp trong AI Studio.' },
        ],
        richMenu: {
            icon: 'printer', eyebrow: 'DỊCH VỤ IN 3D · 2DTO3D', title: 'Chuẩn bị mô hình cho bước in.',
            description: 'Xem quy trình và ước tính tham khảo từ bản nháp.', ctaIndex: features.aiStudio ? 0 : 1,
            actions: [
                ...(features.aiStudio ? [{ itemIndex: 0, icon: 'cube', description: 'Mở AI Studio để bắt đầu hoặc tiếp tục mô hình.' }] : []),
                { itemIndex: features.aiStudio ? 1 : 0, icon: 'book', description: 'Đọc quy trình chuẩn bị in hiện có.' },
                { itemIndex: features.aiStudio ? 2 : 1, icon: 'calculator', description: 'Ước tính chỉ tham khảo, chưa phải báo giá chính thức.' },
            ],
            discovery: { kind: 'steps', icon: 'route', title: 'Các bước chuẩn bị', description: 'Ước tính không gửi yêu cầu đặt in.', steps: ['Chuẩn bị mô hình', 'Tìm hiểu cấu hình', 'Xem ước tính từ bản nháp'] },
        },
    }] : []),
    ...(features.cart || features.checkout ? [{
        id: 'commerce',
        label: 'Giỏ hàng & đặt hàng',
        items: [
            ...(features.cart ? [{ label: 'Giỏ hàng', to: '/cart' }] : []),
            ...(features.checkout ? [{ label: 'Đặt hàng', to: '/checkout', description: 'Trang thông tin về trạng thái đặt hàng trực tuyến.' }] : []),
            { label: 'Thanh toán', disabled: true, disabledReason: 'Thanh toán trực tuyến chưa được hỗ trợ.' },
        ],
        richMenu: {
            icon: 'store', eyebrow: 'GIỎ HÀNG · POCKET JOY', title: 'Xem giỏ hàng và trạng thái đặt hàng.',
            description: 'Giá trong giỏ là thông tin tham khảo; đặt hàng và thanh toán sẽ được thông báo khi sẵn sàng.', ctaIndex: 0,
            actions: [
                ...(features.cart ? [{ itemIndex: 0, icon: 'store', description: 'Xem sản phẩm đã lưu trong giỏ hàng.' }] : []),
                ...(features.checkout ? [{ itemIndex: features.cart ? 1 : 0, icon: 'info', description: 'Xem trạng thái hỗ trợ đặt hàng.' }] : []),
            ],
            discovery: { kind: 'note', icon: 'info', title: 'Thanh toán chưa khả dụng', description: 'Chưa có giao dịch thanh toán nào được khởi tạo từ trang này.' },
        },
    }] : []),
];

export const createCustomerNavigationGroups = (features = {}) => [
    {
        id: 'store',
        label: 'Cửa hàng',
        items: [
            { label: 'Tất cả sản phẩm', to: '/marketplace', description: 'Khám phá cửa hàng Pocket Joy.' },
        ],
        richMenu: {
            icon: 'sparkles',
            eyebrow: 'POCKET JOY · TINY THINGS, BIG FEELINGS',
            title: 'Niềm vui nhỏ, luôn bên bạn.',
            description: 'Khám phá móc khóa vui mắt, vui tay và những món quà mang dấu ấn riêng.',
            ctaIndex: 0,
            actions: [
                { itemIndex: 0, icon: 'store', description: 'Xem cửa hàng Pocket Joy.' },
            ],
            discovery: { kind: 'search', icon: 'search', title: 'Bạn đang tìm món gì?', description: 'Tìm trong cửa hàng theo tên hoặc ý tưởng của bạn.', actionLabel: 'Mở tìm kiếm' },
        },
    },
    {
        id: 'models',
        label: 'Kho mẫu',
        items: [
            { label: 'Thư viện mô hình 3D', to: '/products', description: 'Mô hình công khai đã được duyệt.' },
        ],
        richMenu: {
            icon: 'cube',
            eyebrow: 'THƯ VIỆN 3D · 2DTO3D',
            title: 'Kho mô hình 3D.',
            description: 'Dành cho các mô hình được xác nhận để chia sẻ công khai.',
            ctaIndex: 0,
            actions: [
                { itemIndex: 0, icon: 'cube', description: 'Mở thư viện mô hình 3D công khai.' },
            ],
            discovery: { kind: 'empty', icon: 'sparkles', title: 'Chưa có mô hình công khai được duyệt', description: 'Thư viện sẽ hiển thị mô hình khi trạng thái chia sẻ công khai đã được xác nhận.' },
        },
    },
    ...featureNavigationGroups(features),
    {
        id: 'about',
        label: 'Về chúng tôi',
        items: [
            { label: 'Nền tảng 2Dto3D', to: '/about#nen-tang' },
            { label: 'Pocket Joy', to: '/about#pocket-joy' },
        ],
        richMenu: {
            icon: 'heart',
            eyebrow: 'VỀ 2DTO3D',
            title: 'Ý tưởng 3D và niềm vui nhỏ.',
            description: 'Tìm hiểu nền tảng 2Dto3D và dòng Pocket Joy.',
            ctaIndex: 0,
            actions: [
                { itemIndex: 0, icon: 'cube', description: 'Tìm hiểu định hướng nền tảng.' },
                { itemIndex: 1, icon: 'heart', description: 'Khám phá ý tưởng Pocket Joy.' },
            ],
            discovery: {
                kind: 'facts', icon: 'sparkles', title: 'Tìm hiểu thêm', description: 'Các liên kết dẫn đến nội dung đang có trên trang Về chúng tôi.',
                links: [{ label: 'Nền tảng 2Dto3D', itemIndex: 0 }, { label: 'Pocket Joy', itemIndex: 1 }],
            },
        },
    },
];

export const CUSTOMER_NAV_GROUPS = createCustomerNavigationGroups(config.features);

export function isNavigationGroupActive(groupId, pathname) {
    if (groupId === 'store') return pathname === '/marketplace' || pathname.startsWith('/product/');
    if (groupId === 'ai') return pathname === '/studio';
    if (groupId === 'models') return pathname === '/products';
    if (groupId === 'guides') return pathname === '/workflow' || pathname === '/resources';
    if (groupId === 'printing') return pathname === '/print-quote';
    if (groupId === 'commerce') return pathname === '/cart' || pathname === '/checkout';
    if (groupId === 'about') return pathname === '/about';
    return false;
}

export function getNavigationState(to, currentState) {
    if (to?.startsWith('/studio') && currentState?.modelEditDraft) return currentState;
    if (to === '/print-quote' && currentState?.printDraft) return currentState;
    return undefined;
}

export function getNavigationPresentation({ compact, mobileOpen, openGroup }) {
    const showMobileMenu = Boolean(compact && mobileOpen);
    const showDesktopMegaMenu = Boolean(!compact && openGroup);
    return {
        showDesktopMegaMenu,
        showMobileMenu,
        visibleGroup: showDesktopMegaMenu || showMobileMenu ? openGroup : null,
    };
}

export function transitionNavigationOverlay(state, action) {
    const closed = { mobileOpen: false, openGroup: null, searchOpen: false, accountOpen: false };
    switch (action.type) {
        case 'open-group': {
            const openGroup = state.openGroup === action.groupId ? null : action.groupId;
            return { ...closed, mobileOpen: Boolean(action.compact && state.mobileOpen), openGroup };
        }
        case 'toggle-mobile':
            return state.mobileOpen ? closed : { ...closed, mobileOpen: true };
        case 'toggle-search':
            return state.searchOpen ? { ...state, searchOpen: false } : { ...closed, searchOpen: true };
        case 'open-search':
            return { ...closed, searchOpen: true };
        case 'toggle-account':
            return state.accountOpen ? { ...state, accountOpen: false } : { ...closed, accountOpen: true };
        case 'open-account':
            return { ...closed, accountOpen: true };
        case 'close-all':
            return closed;
        default:
            return state;
    }
}

export function isNavigationItemActive(item, location) {
    if (!item?.to || item.disabled) return false;
    const [path, targetHash = ''] = item.to.split('#');
    const [targetPath, targetSearch = ''] = path.split('?');
    if (targetPath !== location.pathname) return false;
    if (targetHash) return location.hash === `#${targetHash}`;

    const current = new URLSearchParams(location.search);
    const target = new URLSearchParams(targetSearch);
    for (const [key, value] of target) {
        if (current.get(key) !== value) return false;
    }

    if (item.collection) return current.get('collection') === item.collection;
    if (targetSearch) return true;
    if (targetPath === '/marketplace') return true;
    return current.toString() === '';
}

export function isNavigationItemAvailable(item, location) {
    return Boolean(item && !item.disabled && (!item.requiresPrintDraft || location?.state?.printDraft));
}
