import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const context = await readFile(new URL('./CartContext.jsx', import.meta.url), 'utf8');

test('legacy price refresh notice identifies local reference pricing, not an official quote', () => {
    const notice = context.match(/if \(restored\.updatedPriceCount > 0\) \{\s*messages\.push\('([^']+)'\);\s*\}/)?.[1];

    assert.ok(notice, 'price refresh notice should remain guarded by updatedPriceCount');
    assert.match(notice, /tham khảo/i);
    assert.match(notice, /danh mục cục bộ/);
    assert.match(notice, /không phải báo giá do Backend xác nhận/);
    assert.match(notice, /số tiền phải thanh toán/);
    assert.doesNotMatch(notice, /đã được cập nhật theo catalog hiện tại/i);
});

test('legacy price refresh message remains conditional on changed local prices', () => {
    assert.match(context, /if \(restored\.updatedPriceCount > 0\) \{\s*messages\.push\(/);
});
