import assert from 'node:assert/strict';
import test from 'node:test';
import { buildEmployeeSubmissionPayload, canStartEmployeeTask, canSubmitEmployeeTask, getCurrentCycleSubmission, getEmployeeTaskActionStatus, getEmployeeTaskMutationErrorType } from './employeeTaskPresentation.js';

test('shows only a submission matching the current assignment cycle', () => {
  const task = { assignmentCycle: 3, latestSubmission: { id: 9, assignmentCycle: 3 } };
  assert.equal(getCurrentCycleSubmission(task).id, 9);
  assert.equal(getCurrentCycleSubmission({ ...task, latestSubmission: { id: 8, assignmentCycle: 2 } }), null);
  assert.equal(getCurrentCycleSubmission({ ...task, latestSubmission: null }), null);
});

test('exposes only state-machine employee actions', () => {
  assert.equal(canStartEmployeeTask({ status: 'ASSIGNED' }), true);
  assert.equal(canSubmitEmployeeTask({ status: 'ASSIGNED' }), true);
  assert.equal(getEmployeeTaskActionStatus({ status: 'ASSIGNED' }), 'start-and-submit');
  assert.equal(canStartEmployeeTask({ status: 'IN_PROGRESS' }), false);
  assert.equal(canSubmitEmployeeTask({ status: 'IN_PROGRESS' }), true);
  assert.equal(canSubmitEmployeeTask({ status: 'REVISION_REQUESTED' }), true);
  assert.equal(canSubmitEmployeeTask({ status: 'SUBMITTED' }), false);
  assert.equal(canSubmitEmployeeTask({ status: 'APPROVED' }), false);
  assert.equal(canStartEmployeeTask({ status: 'SUBMITTED' }), false);
  assert.equal(canStartEmployeeTask({ status: 'APPROVED' }), false);
  assert.equal(getEmployeeTaskActionStatus({ status: 'SUBMITTED' }), 'waiting-review');
  assert.equal(getEmployeeTaskActionStatus({ status: 'APPROVED' }), 'approved');
});

test('direct submission carries the loaded expectedVersion and only submission fields', () => {
  assert.deepEqual(buildEmployeeSubmissionPayload({ resultDescription: 'done', resultUrl: '' }, 12), {
    expectedVersion: 12, resultDescription: 'done', resultUrl: null,
  });
});

test('classifies mutation errors for field validation, access, conflict and retry recovery', () => {
  assert.equal(getEmployeeTaskMutationErrorType({ response: { status: 400 } }), 'validation');
  assert.equal(getEmployeeTaskMutationErrorType({ response: { status: 401 } }), 'access');
  assert.equal(getEmployeeTaskMutationErrorType({ response: { status: 403 } }), 'access');
  assert.equal(getEmployeeTaskMutationErrorType({ response: { status: 404 } }), 'access');
  assert.equal(getEmployeeTaskMutationErrorType({ response: { status: 409 } }), 'conflict');
  assert.equal(getEmployeeTaskMutationErrorType({ response: { status: 503 } }), 'retryable');
  assert.equal(getEmployeeTaskMutationErrorType(new TypeError('network')), 'retryable');
});
