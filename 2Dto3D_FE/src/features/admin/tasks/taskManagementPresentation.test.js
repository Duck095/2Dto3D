import assert from 'node:assert/strict';
import test from 'node:test';
import { buildTaskListParams, buildTaskUpdatePayload, canEditTask, markCurrentCycleSubmissions } from './taskManagementPresentation.js';

test('task list query includes assignee, selected filters and pagination without empty values', () => {
  assert.deepEqual(buildTaskListParams({ q: '  model  ', status: '', priority: 'HIGH', overdue: '', assigneeStaffProfileId: '17' }, 2, 20), {
    q: 'model', priority: 'HIGH', assigneeStaffProfileId: 17, page: 2, size: 20,
  });
});

test('only editable workflow states permit metadata changes or reassignment', () => {
  assert.equal(canEditTask('ASSIGNED'), true);
  assert.equal(canEditTask('IN_PROGRESS'), true);
  assert.equal(canEditTask('REVISION_REQUESTED'), true);
  assert.equal(canEditTask('SUBMITTED'), false);
  assert.equal(canEditTask('APPROVED'), false);
});

test('unchanged task values do not produce a PATCH payload', () => {
  const original = { id: 9, version: 4, title: 'Model review', description: 'Review notes', priority: 'NORMAL', assigneeStaffProfileId: 12, dueAtLocal: '2026-11-30T10:00' };
  const result = buildTaskUpdatePayload({ ...original }, original);
  assert.equal(result.payload, null);
  assert.equal(result.error, 'Không có thay đổi để lưu.');
});

test('metadata-only PATCH omits unchanged assignee and dueAt while keeping expectedVersion', () => {
  const original = { id: 9, version: 4, title: 'Model review', description: 'Review notes', priority: 'NORMAL', assigneeStaffProfileId: 12, dueAtLocal: '2026-11-30T10:00' };
  const result = buildTaskUpdatePayload({ ...original, title: 'Model review v2' }, original);
  assert.deepEqual(result.payload, { expectedVersion: 4, title: 'Model review v2' });
});

test('reassignment and due date changes are included only when their values differ', () => {
  const original = { id: 9, version: 4, title: 'Model review', description: 'Review notes', priority: 'NORMAL', assigneeStaffProfileId: 12, dueAtLocal: '2026-11-30T10:00' };
  const result = buildTaskUpdatePayload({ ...original, assigneeStaffProfileId: '13', dueAtLocal: '2026-12-01T10:00' }, original);
  assert.deepEqual(result.payload, {
    expectedVersion: 4,
    assigneeStaffProfileId: 13,
    dueAt: new Date('2026-12-01T10:00').toISOString(),
  });
});

test('all submissions remain visible and only current-cycle results are marked current', () => {
  const rows = markCurrentCycleSubmissions([
    { id: 1, submissionNumber: 1, assignmentCycle: 1 },
    { id: 2, submissionNumber: 2, assignmentCycle: 2 },
    { id: 3, submissionNumber: 3, assignmentCycle: 2 },
  ], 2);
  assert.deepEqual(rows.map(({ submissionNumber, assignmentCycle, isCurrentCycle }) => [submissionNumber, assignmentCycle, isCurrentCycle]), [
    [1, 1, false], [2, 2, true], [3, 2, true],
  ]);
});

test('acceptance history identifies the backend-selected latest submission separately', () => {
  const rows = markCurrentCycleSubmissions([
    { id: 4, assignmentCycle: 3 },
    { id: 5, assignmentCycle: 3 },
    { id: 6, assignmentCycle: 2 },
  ], 3, 5);
  assert.deepEqual(rows.map(({ id, isCurrentCycle, isLatestSubmission }) => [id, isCurrentCycle, isLatestSubmission]), [
    [4, true, false], [5, true, true], [6, false, false],
  ]);
});
