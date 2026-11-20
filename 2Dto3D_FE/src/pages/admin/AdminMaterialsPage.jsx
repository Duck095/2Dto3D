import { useEffect, useState } from 'react';
import EmptyState from '../../components/ui/EmptyState';
import ErrorState from '../../components/ui/ErrorState';
import { isValidHexColor, classifyAdminApiError } from '../../features/admin/products/adminProductContract';
import { listAdminMaterials } from '../../features/admin/products/adminProductApi';
import './admin-products.css';

export default function AdminMaterialsPage() {
  const [state, setState] = useState({ items: [], loading: true, error: null });
  const [reload, setReload] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setState(current => ({ ...current, loading: true, error: null }));
    listAdminMaterials({ signal: controller.signal }).then(items => {
      if (!controller.signal.aborted) setState({ items, loading: false, error: null });
    }).catch(error => {
      if (!controller.signal.aborted) setState({ items: [], loading: false, error });
    });
    return () => controller.abort();
  }, [reload]);

  return <div className="admin-products-page admin-materials-page">
    <header className="admin-page-heading"><p className="admin-page-heading__eyebrow">TÀI NGUYÊN</p><h2>Vật liệu &amp; màu</h2><p>Danh mục toàn cục do Backend cung cấp để tham khảo trong vận hành.</p></header>
    <p className="admin-material-disclaimer">Danh sách vật liệu và màu toàn cục — chưa xác định tương thích cho từng sản phẩm.</p>
    {state.error ? <ErrorState title="Không thể tải vật liệu" message={classifyAdminApiError(state.error).message} onRetry={() => setReload(value => value + 1)} /> : state.loading ? <p role="status" aria-live="polite" className="admin-product-loading">Đang tải danh sách vật liệu…</p> : !state.items.length ? <EmptyState title="Chưa có vật liệu" description="Backend hiện chưa trả về vật liệu nào." /> : <ul className="admin-material-grid" aria-label="Danh sách vật liệu">{state.items.map(material => <li key={material.id} className="admin-product-card admin-material-card">
      <div className="admin-material-heading"><div><p className="admin-products-kicker">{material.type || 'VẬT LIỆU'}</p><h3>{material.name}</h3></div><span className={`admin-material-status ${material.isAvailable === 'AVAILABLE' ? 'is-available' : ''}`}>{material.isAvailable === 'AVAILABLE' ? 'Khả dụng' : 'Không khả dụng'}</span></div>
      <dl className="admin-product-file-info"><div><dt>Mã vật liệu</dt><dd>{material.id}</dd></div>{material.stockQuantity != null && <div><dt>Tồn kho ghi nhận</dt><dd>{material.stockQuantity}{material.unit ? ` ${material.unit}` : ''}</dd></div>}</dl>
      <div className="admin-material-colors"><h4>Màu liên kết toàn cục</h4>{material.colors.length ? <ul>{material.colors.map(color => <li key={color.colorId}><span className="admin-material-swatch" aria-hidden="true" style={isValidHexColor(color.hexCode) ? { backgroundColor: color.hexCode } : undefined} /> <span>{color.name}</span>{isValidHexColor(color.hexCode) && <code>{color.hexCode}</code>}</li>)}</ul> : <p>Backend chưa cung cấp màu liên kết.</p>}</div>
      <p className="admin-product-note">Chỉ xem. Chỉnh sửa vật liệu, màu và gán tương thích chưa được bật.</p>
    </li>)}</ul>}
  </div>;
}
