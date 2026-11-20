import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { getCurrentCycleSubmission, getEmployeeTaskActionStatus } from '../../features/employee/tasks/employeeTaskPresentation.js';

const page = await readFile(new URL('./EmployeeTaskPage.jsx', import.meta.url), 'utf8');

test('task detail renders priority, deadline, current-cycle feedback and current submission', () => {
  assert.match(page, /task\.priority/);
  assert.match(page, /task\.dueAt/);
  assert.match(page, /task\.latestRevisionFeedback/);
  assert.match(page, /getCurrentCycleSubmission\(task\)/);
  assert.match(page, /currentSubmission\.assignmentCycle|currentSubmission\.submissionNumber/);
  assert.equal(getCurrentCycleSubmission({ assignmentCycle: 3, latestSubmission: { assignmentCycle: 2 } }), null);
  assert.equal(getCurrentCycleSubmission({ assignmentCycle: 3, latestSubmission: { assignmentCycle: 3, id: 5 } }).id, 5);
});

test('start, submit and resubmit use current version and 409 reloads current state', () => {
  assert.match(page, /startMyTask\(task\.id, task\.version\)/);
  assert.match(page, /buildEmployeeSubmissionPayload\(submissionForm, task\.version\)/);
  assert.match(page, /task\.status === 'REVISION_REQUESTED'/);
  assert.match(page, /if \(type === 'conflict'\)/);
  assert.match(page, /reloadTask\(undefined,\s*\{ showLoading: false \}\)/);
  assert.equal(getEmployeeTaskActionStatus({ status: 'REVISION_REQUESTED' }), 'submit');
  assert.equal(getEmployeeTaskActionStatus({ status: 'SUBMITTED' }), 'waiting-review');
});

test('assigned tasks expose both start and direct submit; submitted and approved stay read-only', () => {
  assert.match(page, /canStartEmployeeTask\(task\)[\s\S]*?Bắt đầu công việc/);
  assert.match(page, /canSubmitEmployeeTask\(task\)[\s\S]*?<form/);
  assert.match(page, /task\.status === 'SUBMITTED'[\s\S]*?Kết quả đang chờ quản lý nghiệm thu/);
  assert.match(page, /task\.status === 'APPROVED'[\s\S]*?Công việc đã được duyệt/);
});

test('mutation errors preserve form data, show inline feedback and offer retry for network/5xx', () => {
  assert.match(page, /mutationError/);
  assert.match(page, /role="alert"[\s\S]*mutationError\.message/);
  assert.match(page, /handleMutationError\(reason, \{ type: 'submit' \}\)/);
  assert.match(page, /Thử gửi lại/);
  assert.doesNotMatch(page, /catch \(reason\) \{ setError\(reason\)/);
  assert.match(page, /mutationLockRef\.current/);
});

test('retry keeps only the action type and submits the latest controlled form with the loaded version', () => {
  assert.match(page, /handleMutationError\(reason,\s*\{\s*type:\s*'submit'\s*\}\)/);
  assert.match(page, /retryAction\.type === 'submit'\) await submitResult\(form\)/);
  assert.doesNotMatch(page, /retryAction\.payload/);
  assert.match(page, /buildEmployeeSubmissionPayload\(submissionForm, task\.version\)/);
});

test('task detail has explicit loading, permission/not-found and error states', () => {
  assert.match(page, /Đang tải công việc/);
  assert.match(page, /Không thể mở công việc/);
  assert.match(page, /Không tìm thấy công việc/);
});

test('result link validation matches the Backend HTTPS contract', () => {
  assert.match(page, /type="url"[^>]*pattern="https:\/\/.\+"/);
  assert.match(page, /Chỉ chấp nhận liên kết HTTPS/);
});
