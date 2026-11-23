import assert from 'node:assert/strict';
import test from 'node:test';
import { isAdminColorList, normalizeMaterialForm } from './adminMaterialContract.js';

test('color catalog contract accepts empty and valid lists but rejects malformed or duplicate ids', () => {
  assert.equal(isAdminColorList([]), true);
  assert.equal(isAdminColorList([{ colorId: 2, name: 'Blue', hexCode: '#123456' }]), true);
  assert.equal(isAdminColorList([{ colorId: 2, name: 'Blue', hexCode: '#bad' }]), false);
  assert.equal(isAdminColorList([
    { colorId: 2, name: 'Blue', hexCode: '#123456' }, { colorId: 2, name: 'Other', hexCode: '#654321' },
  ]), false);
});

test('material form payload trims fields, associates only selected catalog color ids, and validates required fields', () => {
  assert.deepEqual(normalizeMaterialForm({
    name: '  PLA  ', type: 'PLA', pricePerGram: '0.05', stockQuantity: '100', unit: ' g ', colorIds: ['2', '4'],
  }), {
    name: 'PLA', type: 'PLA', pricePerGram: 0.05, stockQuantity: 100, unit: 'g',
    colors: [{ colorId: 2, quantity: 0 }, { colorId: 4, quantity: 0 }],
  });
  assert.throws(() => normalizeMaterialForm({ name: '', pricePerGram: '-1', stockQuantity: 'x', colorIds: [] }));
});
