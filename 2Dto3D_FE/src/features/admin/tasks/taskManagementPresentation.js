const EDITABLE_STATUSES = new Set(['ASSIGNED', 'IN_PROGRESS', 'REVISION_REQUESTED']);

export function canEditTask(status) {
  return EDITABLE_STATUSES.has(status);
}

export function buildTaskListParams(filters, page, size) {
  const params = { page, size };
  for (const key of ['q', 'status', 'priority', 'overdue']) {
    const value = typeof filters?.[key] === 'string' ? filters[key].trim() : filters?.[key];
    if (value !== '' && value !== undefined && value !== null) params[key] = value;
  }
  if (filters?.assigneeStaffProfileId !== '' && filters?.assigneeStaffProfileId != null) {
    const id = Number(filters.assigneeStaffProfileId);
    if (Number.isInteger(id) && id > 0) params.assigneeStaffProfileId = id;
  }
  return params;
}

function toIsoInstant(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function buildTaskUpdatePayload(values, original) {
  const payload = { expectedVersion: original.version };
  const title = typeof values.title === 'string' ? values.title.trim() : '';
  const description = typeof values.description === 'string' ? values.description.trim() : '';

  if (title !== original.title) payload.title = title;
  if (description !== original.description) payload.description = description;
  if (values.priority !== original.priority) payload.priority = values.priority;

  const assigneeStaffProfileId = Number(values.assigneeStaffProfileId);
  if (assigneeStaffProfileId !== Number(original.assigneeStaffProfileId)) {
    payload.assigneeStaffProfileId = assigneeStaffProfileId;
  }

  if (values.dueAtLocal !== original.dueAtLocal) {
    const dueAt = toIsoInstant(values.dueAtLocal);
    if (!dueAt) return { payload: null, error: 'Nhập hạn hoàn thành hợp lệ.' };
    payload.dueAt = dueAt;
  }

  if (Object.keys(payload).length === 1) return { payload: null, error: 'Không có thay đổi để lưu.' };
  if (Object.hasOwn(payload, 'title') && (!title || title.length > 180)) return { payload: null, error: 'Tiêu đề là bắt buộc và tối đa 180 ký tự.' };
  if (Object.hasOwn(payload, 'description') && (!description || description.length > 20000)) return { payload: null, error: 'Mô tả là bắt buộc và tối đa 20000 ký tự.' };
  if (Object.hasOwn(payload, 'assigneeStaffProfileId') && (!Number.isInteger(assigneeStaffProfileId) || assigneeStaffProfileId <= 0)) {
    return { payload: null, error: 'Chọn người phụ trách hợp lệ.' };
  }
  return { payload, error: null };
}

export function markCurrentCycleSubmissions(submissions = [], currentCycle, latestSubmissionId = null) {
  return submissions.map(submission => ({
    ...submission,
    isCurrentCycle: Number(submission.assignmentCycle) === Number(currentCycle),
    isLatestSubmission: latestSubmissionId != null && Number(submission.id) === Number(latestSubmissionId),
  }));
}
