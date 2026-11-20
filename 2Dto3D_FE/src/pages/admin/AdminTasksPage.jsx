import { useCallback, useEffect, useMemo, useState } from 'react';
import AdminField from '../../components/admin/AdminField';
import AdminTable from '../../components/admin/AdminTable';
import PopCard from '../../components/admin/PopCard';
import StatusBadge from '../../components/admin/StatusBadge';
import Button from '../../components/ui/Button';
import EmptyState from '../../components/ui/EmptyState';
import ErrorState from '../../components/ui/ErrorState';
import { listStaff } from '../../features/admin/staff/staffService';
import { createTask, getTask, getTaskApiErrorMessage, listTasks, updateTask } from '../../features/admin/tasks/taskService';
import { buildTaskListParams, buildTaskUpdatePayload, canEditTask } from '../../features/admin/tasks/taskManagementPresentation';
import './task-management.css';

const PAGE_SIZE = 20;
const PRIORITIES = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];
const STATUSES = ['ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'REVISION_REQUESTED', 'APPROVED'];
const eligibleRoles = new Set(['USER', 'DESIGNER']);
const statusLabels = { ASSIGNED: 'Đã giao', IN_PROGRESS: 'Đang thực hiện', SUBMITTED: 'Chờ nghiệm thu', REVISION_REQUESTED: 'Cần chỉnh sửa', APPROVED: 'Đã duyệt' };

function toLocalDateTime(value) {
  if (!value) return '';
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function toInstant(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function eligible(staff) {
  return staff?.accountStatus === 'ACTIVE' && eligibleRoles.has(staff.role) && staff.status === 'ACTIVE';
}

function TaskStatus({ status }) {
  const tone = status === 'APPROVED' ? 'success' : status === 'SUBMITTED' ? 'info' : status === 'REVISION_REQUESTED' ? 'warning' : 'neutral';
  return <StatusBadge tone={tone}>{statusLabels[status] || status}</StatusBadge>;
}

export default function AdminTasksPage() {
  const [filters, setFilters] = useState({ q: '', status: '', priority: '', overdue: '', assigneeStaffProfileId: '' });
  const [draftFilters, setDraftFilters] = useState(filters);
  const [pageIndex, setPageIndex] = useState(0);
  const [reload, setReload] = useState(0);
  const [taskPage, setTaskPage] = useState({ items: [], totalPages: 0, loading: true, error: null });
  const [staff, setStaff] = useState({ items: [], loading: true, error: null });
  const [formMode, setFormMode] = useState(null);
  const [formValues, setFormValues] = useState({ title: '', description: '', priority: 'NORMAL', assigneeStaffProfileId: '', dueAt: '' });
  const [baseline, setBaseline] = useState(null);
  const [formError, setFormError] = useState('');
  const [formLoading, setFormLoading] = useState(false);
  const [notice, setNotice] = useState('');
  const [conflict, setConflict] = useState(false);
  const [selectedTaskId, setSelectedTaskId] = useState(null);
  const [pendingEditId, setPendingEditId] = useState(null);
  const [taskDetail, setTaskDetail] = useState({ task: null, loading: false, error: null });

  const params = useMemo(() => buildTaskListParams(filters, pageIndex, PAGE_SIZE), [filters, pageIndex]);
  const reloadData = useCallback(() => setReload(value => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    setTaskPage(current => ({ ...current, loading: true, error: null }));
    listTasks(params, { signal: controller.signal }).then(data => {
      if (controller.signal.aborted) return;
      if (!Array.isArray(data?.items)) throw new Error('Invalid task page response');
      setTaskPage({ items: data.items, page: data.page, totalPages: data.totalPages, totalItems: data.totalItems, loading: false, error: null });
    }).catch(error => {
      if (!controller.signal.aborted) setTaskPage(current => ({ ...current, items: [], loading: false, error }));
    });
    return () => controller.abort();
  }, [params, reload]);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setStaff(current => ({ ...current, loading: true, error: null }));
    (async () => {
      const all = [];
      let page = 0;
      let totalPages = 1;
      do {
        const data = await listStaff({ page, size: 100, status: 'ACTIVE' }, { signal: controller.signal });
        if (!Array.isArray(data?.content)) throw new Error('Invalid staff page response');
        all.push(...data.content);
        totalPages = Number.isInteger(data.totalPages) ? data.totalPages : 1;
        page += 1;
      } while (page < totalPages && !controller.signal.aborted);
      if (active) setStaff({ items: all.filter(eligible), loading: false, error: null });
    })().catch(error => {
      if (active && !controller.signal.aborted) setStaff({ items: [], loading: false, error });
    });
    return () => { active = false; controller.abort(); };
  }, [reload]);

  useEffect(() => {
    if (selectedTaskId == null) {
      setTaskDetail({ task: null, loading: false, error: null });
      return undefined;
    }
    const controller = new AbortController();
    setTaskDetail({ task: null, loading: true, error: null });
    getTask(selectedTaskId, { signal: controller.signal }).then(task => {
      if (!controller.signal.aborted) setTaskDetail({ task, loading: false, error: null });
    }).catch(error => {
      if (!controller.signal.aborted) setTaskDetail({ task: null, loading: false, error });
    });
    return () => controller.abort();
  }, [selectedTaskId, reload]);

  useEffect(() => {
    if (pendingEditId == null) return;
    if (taskDetail.error && selectedTaskId === pendingEditId) {
      setFormMode(null); setPendingEditId(null); setFormError(getTaskApiErrorMessage(taskDetail.error)); return;
    }
    const detail = taskDetail.task;
    if (!detail || detail.id !== pendingEditId) return;
    if (!canEditTask(detail.status)) {
      setFormMode(null); setPendingEditId(null);
      setNotice('Chỉ có thể chỉnh sửa hoặc giao lại công việc đang được thực hiện hoặc cần chỉnh sửa.');
      return;
    }
    const dueAtLocal = toLocalDateTime(detail.dueAt);
    const taskBaseline = {
      id: detail.id,
      version: detail.version,
      title: detail.title || '',
      description: detail.description || '',
      priority: detail.priority,
      assigneeStaffProfileId: Number(detail.assignee?.id),
      dueAtLocal,
    };
    setBaseline(taskBaseline);
    setFormValues({ ...taskBaseline, assigneeStaffProfileId: String(taskBaseline.assigneeStaffProfileId), dueAt: dueAtLocal });
    setFormMode('edit'); setPendingEditId(null);
  }, [pendingEditId, selectedTaskId, taskDetail]);

  const openCreate = () => {
    setSelectedTaskId(null); setPendingEditId(null); setBaseline(null);
    setFormMode('create');
    setFormValues({ title: '', description: '', priority: 'NORMAL', assigneeStaffProfileId: '', dueAt: '' });
    setFormError('');
    setNotice('');
  };

  const openEdit = task => {
    setSelectedTaskId(task.id); setPendingEditId(task.id); setFormMode('loading');
    setBaseline(null); setFormError(''); setNotice(''); setConflict(false);
  };

  const openDetail = task => {
    setSelectedTaskId(task.id); setPendingEditId(null); setFormMode(null); setBaseline(null);
    setFormError(''); setNotice('');
  };

  const onChange = event => setFormValues(current => ({ ...current, [event.target.name]: event.target.value }));
  const submitForm = async event => {
    event.preventDefault(); setFormError(''); setNotice(''); setConflict(false);
    const title = formValues.title.trim(); const description = formValues.description.trim(); const dueAt = toInstant(formValues.dueAt);
    if (!title || title.length > 180 || !description || description.length > 20000 || !formValues.assigneeStaffProfileId || !dueAt || !PRIORITIES.includes(formValues.priority)) {
      setFormError('Nhập tiêu đề, mô tả, mức ưu tiên, người phụ trách đủ điều kiện và hạn hoàn thành hợp lệ.'); return;
    }
    let payload = { title, description, priority: formValues.priority, assigneeStaffProfileId: Number(formValues.assigneeStaffProfileId), dueAt };
    if (formMode === 'edit') {
      if (!baseline) { setFormError('Không tìm thấy phiên bản công việc. Tải lại chi tiết rồi thử lại.'); return; }
      const result = buildTaskUpdatePayload({ ...formValues, dueAtLocal: formValues.dueAt }, baseline);
      if (!result.payload) { setFormError(result.error); return; }
      payload = result.payload;
    }
    setFormLoading(true);
    try {
      if (formMode === 'create') await createTask(payload);
      else await updateTask(formValues.id, payload);
      setFormMode(null); setBaseline(null); setNotice(formMode === 'create' ? 'Đã tạo và phân công công việc.' : 'Đã cập nhật công việc.'); reloadData();
    } catch (error) {
      setFormError(getTaskApiErrorMessage(error));
      if (error?.response?.status === 409) setConflict(true);
    } finally { setFormLoading(false); }
  };

  const submitFilters = event => { event.preventDefault(); setPageIndex(0); setFilters({ ...draftFilters }); };
  const canGoNext = !taskPage.loading && pageIndex + 1 < (taskPage.totalPages || 0);
  const rows = taskPage.items || [];

  return <div className="admin-task-page">
    <header className="admin-page-heading"><p className="admin-page-heading__eyebrow">ĐIỀU HÀNH</p><h2>Công việc</h2><p>Tạo, phân công và theo dõi công việc từ dữ liệu hệ thống.</p></header>
    <div className="admin-task-toolbar"><Button onClick={openCreate}>Tạo công việc</Button><Button variant="outline" onClick={reloadData}>Tải lại</Button></div>
    {notice && <p role="status" className="admin-task-notice">{notice}</p>}
    {(staff.error || formError) && <p role="alert" className="admin-task-error">{staff.error ? getTaskApiErrorMessage(staff.error) : formError}</p>}
    {staff.items.length === 0 && !staff.loading && !staff.error && <p role="status" className="admin-task-notice">Không có nhân viên thỏa điều kiện User ACTIVE, role USER/DESIGNER và hồ sơ ACTIVE.</p>}
    {formMode === 'loading' && <p role="status" className="admin-task-loading">Đang tải chi tiết công việc…</p>}
    {formMode && formMode !== 'loading' && <PopCard as="section" tone="lavender" className="admin-task-form-card" aria-labelledby="admin-task-form-title">
      <div className="admin-task-form-heading"><div><p className="admin-task-eyebrow">{formMode === 'create' ? 'PHÂN CÔNG MỚI' : 'CẬP NHẬT CÔNG VIỆC'}</p><h3 id="admin-task-form-title">{formMode === 'create' ? 'Tạo công việc' : 'Chỉnh sửa và phân công lại'}</h3></div><Button variant="ghost" size="sm" onClick={() => { setFormMode(null); setBaseline(null); }}>Đóng</Button></div>
      <form className="admin-task-form" onSubmit={submitForm} noValidate aria-busy={formLoading}>
        <AdminField label="Tiêu đề" name="title" value={formValues.title} onChange={onChange} required maxLength={180} disabled={formLoading} />
        <AdminField as="select" label="Mức ưu tiên" name="priority" value={formValues.priority} onChange={onChange} disabled={formLoading}>{PRIORITIES.map(value => <option key={value} value={value}>{value}</option>)}</AdminField>
        <AdminField as="select" label="Người phụ trách" name="assigneeStaffProfileId" value={formValues.assigneeStaffProfileId} onChange={onChange} required disabled={formLoading || staff.loading || !!staff.error}>
          <option value="">{staff.loading ? 'Đang tải nhân viên…' : 'Chọn nhân viên đủ điều kiện'}</option>
          {staff.items.map(person => <option key={person.id} value={person.id}>{person.employeeNumber} — {person.name} ({person.role})</option>)}
        </AdminField>
        <AdminField label="Hạn hoàn thành" name="dueAt" type="datetime-local" value={formValues.dueAt} onChange={onChange} required disabled={formLoading} />
        <label className="admin-task-form__wide admin-field"><span className="admin-field__label">Mô tả <span aria-hidden="true">*</span></span><textarea name="description" value={formValues.description} onChange={onChange} required maxLength={20000} disabled={formLoading} aria-label="Mô tả" /></label>
        <div className="admin-task-form__actions"><Button type="submit" loading={formLoading} disabled={staff.loading || !!staff.error}>Lưu công việc</Button>{conflict && <Button type="button" variant="outline" onClick={() => { setFormMode(null); setBaseline(null); reloadData(); }}>Tải lại dữ liệu</Button>}</div>
      </form>
    </PopCard>}
    <form className="admin-task-filters" onSubmit={submitFilters}>
      <AdminField label="Tìm kiếm" name="q" value={draftFilters.q} onChange={event => setDraftFilters(current => ({ ...current, q: event.target.value }))} placeholder="Tiêu đề hoặc mô tả" />
      <AdminField as="select" label="Trạng thái" name="status" value={draftFilters.status} onChange={event => setDraftFilters(current => ({ ...current, status: event.target.value }))}><option value="">Tất cả</option>{STATUSES.map(status => <option key={status} value={status}>{statusLabels[status]}</option>)}</AdminField>
      <AdminField as="select" label="Ưu tiên" name="priority" value={draftFilters.priority} onChange={event => setDraftFilters(current => ({ ...current, priority: event.target.value }))}><option value="">Tất cả</option>{PRIORITIES.map(value => <option key={value}>{value}</option>)}</AdminField>
      <AdminField as="select" label="Hạn hoàn thành" name="overdue" value={draftFilters.overdue} onChange={event => setDraftFilters(current => ({ ...current, overdue: event.target.value }))}><option value="">Tất cả</option><option value="true">Quá hạn</option><option value="false">Chưa quá hạn</option></AdminField>
      <AdminField as="select" label="Người phụ trách" name="assigneeStaffProfileId" value={draftFilters.assigneeStaffProfileId} onChange={event => setDraftFilters(current => ({ ...current, assigneeStaffProfileId: event.target.value }))}><option value="">Tất cả nhân viên</option>{staff.items.map(person => <option key={person.id} value={person.id}>{person.employeeNumber} — {person.name}</option>)}</AdminField>
      <Button type="submit">Lọc</Button>
    </form>
    {taskPage.error ? <ErrorState title="Không thể tải công việc" message={getTaskApiErrorMessage(taskPage.error)} onRetry={reloadData} /> : taskPage.loading ? <p role="status" className="admin-task-loading">Đang tải công việc…</p> : !rows.length ? <EmptyState title="Chưa có công việc" description="Điều chỉnh bộ lọc hoặc tạo công việc mới." /> : <>
      <AdminTable label="Danh sách công việc"><thead><tr><th>Tiêu đề</th><th>Người phụ trách</th><th>Ưu tiên</th><th>Trạng thái</th><th>Hạn hoàn thành</th><th>Thao tác</th></tr></thead><tbody>{rows.map(task => <tr key={task.id}><td><strong>{task.title}</strong></td><td>{task.assignee?.displayName || '—'} <small>{task.assignee?.employeeNumber}</small></td><td>{task.priority}</td><td><TaskStatus status={task.status} /></td><td>{task.dueAt ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(task.dueAt)) : '—'}{task.overdue && <StatusBadge tone="danger">Quá hạn</StatusBadge>}</td><td className="admin-task-row-actions"><Button size="sm" variant="outline" onClick={() => openDetail(task)}>Xem chi tiết</Button>{canEditTask(task.status) && <Button size="sm" variant="outline" onClick={() => openEdit(task)}>Chỉnh sửa / Giao lại</Button>}</td></tr>)}</tbody></AdminTable>
      <div className="admin-task-pagination"><span>{taskPage.totalItems ?? rows.length} công việc · Trang {pageIndex + 1} / {taskPage.totalPages || 1}</span><div><Button size="sm" variant="outline" disabled={pageIndex === 0 || taskPage.loading} onClick={() => setPageIndex(value => Math.max(0, value - 1))}>Trước</Button><Button size="sm" variant="outline" disabled={!canGoNext} onClick={() => setPageIndex(value => value + 1)}>Tiếp</Button></div></div>
    </>}
    {selectedTaskId != null && <PopCard as="section" tone="lavender" className="admin-task-detail" aria-labelledby="admin-task-detail-title">
      <div className="admin-task-form-heading"><div><p className="admin-task-eyebrow">CHI TIẾT CÔNG VIỆC</p><h3 id="admin-task-detail-title">{taskDetail.task?.title || 'Công việc'}</h3></div><Button size="sm" variant="ghost" onClick={() => { setSelectedTaskId(null); setPendingEditId(null); }}>Đóng</Button></div>
      {taskDetail.loading ? <p role="status">Đang tải chi tiết…</p> : taskDetail.error ? <ErrorState message={getTaskApiErrorMessage(taskDetail.error)} onRetry={reloadData} /> : taskDetail.task && <>
        <dl className="admin-task-detail-grid"><div><dt>Trạng thái</dt><dd><TaskStatus status={taskDetail.task.status} /></dd></div><div><dt>Ưu tiên</dt><dd>{taskDetail.task.priority}</dd></div><div><dt>Người phụ trách</dt><dd>{taskDetail.task.assignee?.displayName || '—'} · {taskDetail.task.assignee?.employeeNumber || '—'}</dd></div><div><dt>Hạn hoàn thành</dt><dd>{taskDetail.task.dueAt ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(taskDetail.task.dueAt)) : '—'}{taskDetail.task.overdue && <StatusBadge tone="danger">Quá hạn</StatusBadge>}</dd></div><div><dt>Chu kỳ giao việc</dt><dd>{taskDetail.task.assignmentCycle}</dd></div><div><dt>Phiên bản</dt><dd>{taskDetail.task.version}</dd></div></dl>
        <section className="admin-task-detail-section"><h4>Mô tả</h4><p>{taskDetail.task.description || 'Không có mô tả.'}</p></section>
        <section className="admin-task-detail-section"><h4>Kết quả nộp ({taskDetail.task.submissions?.length || 0})</h4>{taskDetail.task.submissions?.length ? <ol className="admin-task-submission-list">{taskDetail.task.submissions.map(submission => <li key={submission.id}><strong>Lần nộp {submission.submissionNumber} · Chu kỳ {submission.assignmentCycle}</strong><p>{submission.resultDescription || 'Không có mô tả kết quả.'}</p>{submission.resultUrl && <a href={submission.resultUrl} target="_blank" rel="noreferrer">Mở liên kết kết quả</a>}<small>{submission.submitterName || '—'} · {submission.submittedAt ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(submission.submittedAt)) : '—'}</small></li>)}</ol> : <p>Chưa có kết quả được nộp.</p>}</section>
        <section className="admin-task-detail-section"><h4>Lịch sử thao tác</h4>{taskDetail.task.history?.length ? <ol className="admin-task-history-list">{taskDetail.task.history.map(event => <li key={event.id}><strong>{event.action}</strong> · {event.actorName || 'Hệ thống'} · Chu kỳ {event.assignmentCycle}<small>{event.occurredAt ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(event.occurredAt)) : '—'}</small>{event.note && <p>{event.note}</p>}{event.details && Object.keys(event.details).length > 0 && <pre>{JSON.stringify(event.details, null, 2)}</pre>}</li>)}</ol> : <p>Chưa có lịch sử.</p>}</section>
        {canEditTask(taskDetail.task.status) && <Button className="admin-task-detail-edit" variant="outline" onClick={() => openEdit(taskDetail.task)}>Chỉnh sửa / Giao lại</Button>}
      </>}
    </PopCard>}
  </div>;
}
