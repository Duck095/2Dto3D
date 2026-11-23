import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('storefront exposes only configured customizable occurrence color choices and passes selections to the viewer', async () => {
  const page = await readFile(new URL('./ProductDetailPage.jsx', import.meta.url), 'utf8');
  assert.match(page, /item\.customizable && item\.allowedColors\.length > 0/);
  assert.match(page, /selectAllowedMeshColor\(appearance, Number\(event\.target\.value\)\)/);
  assert.match(page, /meshAppearances=\{product\.modelMeshAppearances\}/);
  assert.match(page, /selectedMeshColors=\{meshColorSelections\}/);
  assert.match(page, /product\.modelMeshAppearances\.filter\(item => item\.customizable/);
});
