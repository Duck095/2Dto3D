import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./AdminOverviewPage.jsx', import.meta.url), 'utf8');

test('overview renders task counts from the summary API with loading, empty and error states', () => {
  assert.match(page, /getTaskSummary/);
  assert.match(page, /pendingAcceptance/);
  assert.match(page, /revisionRequested/);
  assert.match(page, /<KpiCard/);
  assert.match(page, /to="\/admin\/tasks"/);
  assert.match(page, /to="\/admin\/acceptance"/);
  assert.match(page, /loading/);
  assert.match(page, /ErrorState/);
  assert.match(page, /EmptyState/);
  assert.doesNotMatch(page, /value="?\d+"?/);
});
