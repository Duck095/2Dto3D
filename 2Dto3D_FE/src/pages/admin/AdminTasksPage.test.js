import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./AdminTasksPage.jsx', import.meta.url), 'utf8').catch(() => '');

test('task workspace loads real task and staff pages, including every staff page', () => {
  assert.match(page, /listTasks/);
  assert.match(page, /listStaff/);
  assert.match(page, /totalPages/);
  assert.match(page, /accountStatus\s*===\s*['"]ACTIVE['"]/);
  assert.match(page, /role.*USER.*DESIGNER|\['USER',\s*'DESIGNER'\]/s);
  assert.match(page, /staff\.status\s*===\s*['"]ACTIVE['"]/);
  assert.match(page, /assigneeStaffProfileId/);
  assert.match(page, /draftFilters\.assigneeStaffProfileId/);
  assert.match(page, /buildTaskUpdatePayload/);
  assert.match(page, /assigneeStaffProfileId/);
});

test('task screen supports filters, pagination, create, edit/reassign and conflict reload', () => {
  for (const token of ['pageIndex', 'PAGE_SIZE', 'priority', 'status', 'overdue', 'createTask', 'updateTask', '409', 'reload']) {
    assert.ok(page.includes(token), `missing ${token}`);
  }
  assert.match(page, /AdminTable/);
  assert.match(page, /ErrorState/);
  assert.match(page, /EmptyState/);
});

test('every task row can open Admin detail while edit remains state-gated', () => {
  assert.match(page, /openDetail\(task\)/);
  assert.match(page, /Xem chi tiết/);
  assert.match(page, /taskDetail\.task\.description/);
  assert.match(page, /taskDetail\.task\.submissions/);
  assert.match(page, /taskDetail\.task\.history/);
  assert.match(page, /canEditTask\(task\.status\)/);
});
