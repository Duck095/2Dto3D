import { useCallback, useEffect, useState } from 'react';
import EmptyState from '../../components/ui/EmptyState';
import ErrorState from '../../components/ui/ErrorState';
import { classifyAdminApiError, isValidHexColor } from '../../features/admin/products/adminProductContract';
import {
  createAdminColor, createAdminMaterial, listAdminColors, listAdminMaterials, updateAdminMaterial,
} from '../../features/admin/materials/adminMaterialApi.js';
import { normalizeMaterialForm } from '../../features/admin/materials/adminMaterialContract.js';
import './admin-products.css';

const emptyMaterialForm = () => ({ name: '', type: '', pricePerGram: '', stockQuantity: '', unit: 'g', colorIds: [] });

function describeMutationError(error) {
  const classified = classifyAdminApiError(error);
  if (classified.kind === 'conflict') return 'Tên đã tồn tại hoặc dữ liệu vừa thay đổi. Hãy kiểm tra danh sách rồi thử lại.';
  if (classified.kind === 'validation') return 'Thông tin chưa hợp lệ. Hãy kiểm tra tên, mã màu, giá, số lượng và các màu đã chọn.';
  return classified.message;
}

export default function AdminMaterialsPage() {
  const [catalog, setCatalog] = useState({ colors: [], materials: [], loading: true, error: null });
  const [reload, setReload] = useState(0);
  const [colorForm, setColorForm] = useState({ name: '', hexCode: '' });
  const [colorError, setColorError] = useState('');
  const [materialForm, setMaterialForm] = useState(emptyMaterialForm);
  const [editingId, setEditingId] = useState(null);
  const [materialError, setMaterialError] = useState('');
  const [savingColor, setSavingColor] = useState(false);
  const [savingMaterial, setSavingMaterial] = useState(false);

  const refreshCatalog = useCallback(() => setReload(value => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setCatalog(current => ({ ...current, loading: true, error: null }));
    Promise.all([
      listAdminColors({ signal: controller.signal }),
      listAdminMaterials({ signal: controller.signal }),
    ]).then(([colors, materials]) => {
      if (!controller.signal.aborted) setCatalog({ colors, materials, loading: false, error: null });
    }).catch(error => {
      if (!controller.signal.aborted) setCatalog({ colors: [], materials: [], loading: false, error });
    });
    return () => controller.abort();
  }, [reload]);

  async function submitColor(event) {
    event.preventDefault();
    setColorError('');
    const name = colorForm.name.trim();
    const hexCode = colorForm.hexCode.trim();
    if (!name || name.length > 50 || !/^#[\da-f]{6}$/i.test(hexCode)) {
      setColorError('Nhập tên màu (tối đa 50 ký tự) và mã HEX gồm đủ 6 ký tự, ví dụ #12A4EF.');
      return;
    }
    setSavingColor(true);
    try {
      await createAdminColor({ name, hexCode });
      setColorForm({ name: '', hexCode: '' });
      refreshCatalog();
    } catch (error) {
      setColorError(describeMutationError(error));
    } finally {
      setSavingColor(false);
    }
  }

  function beginEdit(material) {
    setEditingId(material.id);
    setMaterialForm({
      name: material.name, type: material.type || '', pricePerGram: String(material.pricePerGram ?? ''),
      stockQuantity: String(material.stockQuantity ?? ''), unit: material.unit || 'g',
      colorIds: material.colors.map(color => String(color.colorId)),
    });
    setMaterialError('');
  }

  function cancelEdit() {
    setEditingId(null);
    setMaterialForm(emptyMaterialForm());
    setMaterialError('');
  }

  async function submitMaterial(event) {
    event.preventDefault();
    setMaterialError('');
    let payload;
    try {
      payload = normalizeMaterialForm(materialForm);
    } catch (error) {
      setMaterialError(error.message);
      return;
    }
    setSavingMaterial(true);
    try {
      if (editingId == null) await createAdminMaterial(payload);
      else await updateAdminMaterial(editingId, payload);
      cancelEdit();
      refreshCatalog();
    } catch (error) {
      setMaterialError(describeMutationError(error));
    } finally {
      setSavingMaterial(false);
    }
  }

  const setMaterialField = (field, value) => setMaterialForm(current => ({ ...current, [field]: value }));
  const toggleColor = (colorId, checked) => setMaterialForm(current => ({
    ...current,
    colorIds: checked ? [...current.colorIds, String(colorId)] : current.colorIds.filter(id => id !== String(colorId)),
  }));

  return <div className="admin-products-page admin-materials-page">
    <header className="admin-page-heading"><p className="admin-page-heading__eyebrow">TÀI NGUYÊN</p><h2>Vật liệu &amp; màu</h2><p>Quản lý danh mục màu và vật liệu dùng trong cấu hình sản phẩm.</p></header>
    {catalog.error ? <ErrorState title="Không thể tải danh mục" message={classifyAdminApiError(catalog.error).message} onRetry={refreshCatalog} /> : catalog.loading ? <p role="status" aria-live="polite" className="admin-product-loading">Đang tải danh mục màu và vật liệu…</p> : <>
      <section className="admin-product-card admin-catalog-section" aria-labelledby="admin-colors-title">
        <div className="admin-product-section-heading"><div><p className="admin-products-kicker">DANH MỤC</p><h3 id="admin-colors-title">Màu sắc</h3></div></div>
        <form className="admin-product-form admin-catalog-form" onSubmit={submitColor}>
          <label className="admin-product-field"><span>Tên màu</span><input value={colorForm.name} maxLength={50} onChange={event => setColorForm(current => ({ ...current, name: event.target.value }))} required /></label>
          <label className="admin-product-field"><span>Mã HEX</span><input value={colorForm.hexCode} placeholder="#12A4EF" maxLength={7} onChange={event => setColorForm(current => ({ ...current, hexCode: event.target.value }))} required aria-describedby="admin-color-hex-help" /><small id="admin-color-hex-help">Dùng định dạng #RRGGBB.</small></label>
          <div className="admin-product-actions"><button type="submit" disabled={savingColor}>{savingColor ? 'Đang lưu…' : 'Thêm màu'}</button></div>
          {colorError && <p className="admin-product-error" role="alert">{colorError}</p>}
        </form>
        {!catalog.colors.length ? <EmptyState title="Chưa có màu" description="Thêm màu để liên kết với vật liệu." /> : <ul className="admin-material-colors admin-catalog-color-list" aria-label="Danh sách màu">{catalog.colors.map(color => <li key={color.colorId}><span className="admin-material-swatch" aria-hidden="true" style={isValidHexColor(color.hexCode) ? { backgroundColor: color.hexCode } : undefined} /><span>{color.name}</span>{color.hexCode && <code>{color.hexCode}</code>}</li>)}</ul>}
      </section>

      <section className="admin-product-card admin-catalog-section" aria-labelledby="admin-material-form-title">
        <div className="admin-product-section-heading"><div><p className="admin-products-kicker">DANH MỤC</p><h3 id="admin-material-form-title">{editingId == null ? 'Thêm vật liệu' : 'Cập nhật vật liệu'}</h3></div></div>
        <form className="admin-product-form admin-catalog-form" onSubmit={submitMaterial}>
          <label className="admin-product-field"><span>Tên vật liệu</span><input value={materialForm.name} maxLength={100} onChange={event => setMaterialField('name', event.target.value)} required /></label>
          <label className="admin-product-field"><span>Loại</span><input value={materialForm.type} maxLength={50} onChange={event => setMaterialField('type', event.target.value)} /></label>
          <label className="admin-product-field"><span>Giá mỗi gram</span><input type="number" min="0" step="any" value={materialForm.pricePerGram} onChange={event => setMaterialField('pricePerGram', event.target.value)} required /></label>
          <label className="admin-product-field"><span>Số lượng tồn</span><input type="number" min="0" step="any" value={materialForm.stockQuantity} onChange={event => setMaterialField('stockQuantity', event.target.value)} required /></label>
          <label className="admin-product-field"><span>Đơn vị</span><input value={materialForm.unit} maxLength={20} onChange={event => setMaterialField('unit', event.target.value)} /></label>
          <fieldset className="admin-product-field admin-catalog-color-options"><legend>Màu liên kết</legend>{catalog.colors.length ? catalog.colors.map(color => <label key={color.colorId}><input type="checkbox" checked={materialForm.colorIds.includes(String(color.colorId))} onChange={event => toggleColor(color.colorId, event.target.checked)} /> <span>{color.name}</span></label>) : <p>Chưa có màu. Hãy thêm màu trước khi liên kết.</p>}</fieldset>
          <div className="admin-product-actions"><button type="submit" disabled={savingMaterial}>{savingMaterial ? 'Đang lưu…' : editingId == null ? 'Thêm vật liệu' : 'Lưu thay đổi'}</button>{editingId != null && <button type="button" onClick={cancelEdit} disabled={savingMaterial}>Hủy</button>}</div>
          {materialError && <p className="admin-product-error" role="alert">{materialError}</p>}
        </form>
      </section>

      <section className="admin-catalog-section" aria-labelledby="admin-materials-title">
        <div className="admin-product-section-heading"><div><p className="admin-products-kicker">DANH SÁCH</p><h3 id="admin-materials-title">Vật liệu đã cấu hình</h3></div></div>
        {!catalog.materials.length ? <EmptyState title="Chưa có vật liệu" description="Tạo vật liệu để xem và cập nhật danh mục." /> : <ul className="admin-material-grid" aria-label="Danh sách vật liệu">{catalog.materials.map(material => <li key={material.id} className="admin-product-card admin-material-card">
          <div className="admin-material-heading"><div><p className="admin-products-kicker">{material.type || 'VẬT LIỆU'}</p><h4>{material.name}</h4></div><span className={`admin-material-status ${material.isAvailable === 'AVAILABLE' ? 'is-available' : ''}`}>{material.isAvailable === 'AVAILABLE' ? 'Khả dụng' : 'Không khả dụng'}</span></div>
          <dl className="admin-product-file-info"><div><dt>Mã vật liệu</dt><dd>{material.id}</dd></div>{material.stockQuantity != null && <div><dt>Tồn kho ghi nhận</dt><dd>{material.stockQuantity}{material.unit ? ` ${material.unit}` : ''}</dd></div>}</dl>
          <div className="admin-material-colors"><h4>Màu liên kết</h4>{material.colors.length ? <ul>{material.colors.map(color => <li key={color.colorId}><span className="admin-material-swatch" aria-hidden="true" style={isValidHexColor(color.hexCode) ? { backgroundColor: color.hexCode } : undefined} /><span>{color.name}</span>{isValidHexColor(color.hexCode) && <code>{color.hexCode}</code>}</li>)}</ul> : <p>Chưa liên kết màu.</p>}</div>
          <div className="admin-product-actions"><button type="button" onClick={() => beginEdit(material)}>Sửa vật liệu</button></div>
        </li>)}</ul>}
      </section>
    </>}
  </div>;
}
