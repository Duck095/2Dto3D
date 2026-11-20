export const EMPLOYEE_TASK_PAGE_SIZE = 20;

export function getCurrentCycleSubmission(task) {
  const submission = task?.latestSubmission;
  return submission && Number(submission.assignmentCycle) === Number(task.assignmentCycle)
    ? submission
    : null;
}

export function canStartEmployeeTask(task) {
  return task?.status === 'ASSIGNED';
}

export function canSubmitEmployeeTask(task) {
  return task?.status === 'ASSIGNED'
    || task?.status === 'IN_PROGRESS'
    || task?.status === 'REVISION_REQUESTED';
}

export function getEmployeeTaskActionStatus(task) {
  if (canStartEmployeeTask(task) && canSubmitEmployeeTask(task)) return 'start-and-submit';
  if (canStartEmployeeTask(task)) return 'start';
  if (canSubmitEmployeeTask(task)) return 'submit';
  if (task?.status === 'SUBMITTED') return 'waiting-review';
  if (task?.status === 'APPROVED') return 'approved';
  return 'unavailable';
}

export function buildEmployeeSubmissionPayload(form, expectedVersion) {
  return {
    expectedVersion,
    resultDescription: form?.resultDescription ?? '',
    resultUrl: form?.resultUrl || null,
  };
}

export function getEmployeeTaskMutationErrorType(error) {
  const status = error?.response?.status;
  if (status === 400) return 'validation';
  if (status === 401 || status === 403 || status === 404) return 'access';
  if (status === 409) return 'conflict';
  return 'retryable';
}
