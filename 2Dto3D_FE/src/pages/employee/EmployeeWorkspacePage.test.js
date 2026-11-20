import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./EmployeeWorkspacePage.jsx', import.meta.url), 'utf8');

test('workspace loads the authenticated profile and paginated assigned tasks from services', () => {
  assert.match(page, /getMyStaffProfile\(\{ signal \}\)/);
  assert.match(page, /listMyTasks\(\{ page: pageIndex, size: EMPLOYEE_TASK_PAGE_SIZE \}/);
  assert.match(page, /Promise\.all/);
  assert.match(page, /page\.profile\?\.employeeNumber/);
  assert.match(page, /page\.profile\?\.status/);
  assert.match(page, /page\.tasks\.items\.map/);
  assert.match(page, /page\.tasks\.totalPages/);
  assert.match(page, /setPageIndex/);
  assert.match(page, /\/employee\/tasks\/\$\{task\.id\}/);
});

test('workspace exposes loading, retryable error and empty states and supports reload', () => {
  assert.match(page, /role="status"/);
  assert.match(page, /<ErrorState/);
  assert.match(page, /onRetry=\{\(\) => setReload/);
  assert.match(page, /<EmptyState/);
  assert.match(page, /Tải lại/);
});
