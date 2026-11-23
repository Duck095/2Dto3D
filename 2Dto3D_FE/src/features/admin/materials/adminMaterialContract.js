import { isMaterialList } from '../products/adminProductContract.js';

const isPositiveInteger = value => Number.isSafeInteger(value) && value > 0;

export function isAdminColorList(value) {
  return Array.isArray(value) && value.every(color => color
    && isPositiveInteger(color.colorId)
    && typeof color.name === 'string' && color.name.trim().length > 0
    && (color.hexCode == null || /^#[\da-f]{6}$/i.test(color.hexCode)))
    && new Set(value.map(color => color.colorId)).size === value.length;
}

export function isAdminMaterialList(value) {
  return isMaterialList(value);
}

export function normalizeMaterialForm(values) {
  const name = typeof values?.name === 'string' ? values.name.trim() : '';
  const type = typeof values?.type === 'string' ? values.type.trim() : '';
  const unit = typeof values?.unit === 'string' ? values.unit.trim() : '';
  const pricePerGram = Number(values?.pricePerGram);
  const stockQuantity = Number(values?.stockQuantity);
  const colorIds = Array.isArray(values?.colorIds) ? values.colorIds.map(Number) : [];
  if (!name || name.length > 100 || type.length > 50 || !Number.isFinite(pricePerGram) || pricePerGram < 0
    || !Number.isFinite(stockQuantity) || stockQuantity < 0 || unit.length > 20
    || colorIds.some(id => !isPositiveInteger(id)) || new Set(colorIds).size !== colorIds.length) {
    throw new Error('ตรวจสอบชื่อ ชนิด ราคา จำนวนคงเหลือ หน่วย และสีที่เลือกให้ถูกต้อง');
  }
  return {
    name, type: type || null, pricePerGram, stockQuantity, unit: unit || 'g',
    colors: colorIds.map(colorId => ({ colorId, quantity: 0 })),
  };
}
