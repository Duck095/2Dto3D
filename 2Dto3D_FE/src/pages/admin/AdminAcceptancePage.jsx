import { useEffect, useState } from 'react';
import AdminTable from '../../components/admin/AdminTable';
import PopCard from '../../components/admin/PopCard';
import StatusBadge from '../../components/admin/StatusBadge';
import Button from '../../components/ui/Button';
import EmptyState from '../../components/ui/EmptyState';
import ErrorState from '../../components/ui/ErrorState';
import { getTask, getTaskApiErrorMessage, listAcceptanceTasks, reviewTask } from '../../features/admin/tasks/taskService';
import { markCurrentCycleSubmissions } from '../../features/admin/tasks/taskManagementPresentation';
import './task-management.css';

const PAGE_SIZE = 20;

export default function AdminAcceptancePage() {
  const [pageIndex, setPageIndex] = useState(0);
  const [reload, setReload] = useState(0);
  const [queue, setQueue] = useState({ items: [], totalPages: 0, loading: true, error: null });
  const [selectedId, setSelectedId] = useState(null);
  const [detail, setDetail] = useState({ task: null, loading: false, error: null });
  const [feedback, setFeedback] = useState('');
  const [mutation, setMutation] = useState({ loading: false, error: '', success: '', conflict: false });
  const refresh = () => setReload(value => value + 1);

  useEffect(() => {
    const controller = new AbortController();
    setQueue(current => ({ ...current, loading: true, error: null }));
    listAcceptanceTasks({ page: pageIndex, size: PAGE_SIZE }, { signal: controller.signal }).then(data => {
      if (controller.signal.aborted) return;
      if (!Array.isArray(data?.items)) throw new Error('Invalid acceptance page response');
      setQueue({ items: data.items, totalPages: data.totalPages, totalItems: data.totalItems, loading: false, error: null });
    }).catch(error => { if (!controller.signal.aborted) setQueue(current => ({ ...current, items: [], loading: false, error })); });
    return () => controller.abort();
  }, [pageIndex, reload]);

  useEffect(() => {
    if (selectedId == null) { setDetail({ task: null, loading: false, error: null }); return undefined; }
    const controller = new AbortController();
    setDetail({ task: null, loading: true, error: null });
    getTask(selectedId, { signal: controller.signal }).then(task => {
      if (!controller.signal.aborted) setDetail({ task, loading: false, error: null });
    }).catch(error => { if (!controller.signal.aborted) setDetail({ task: null, loading: false, error }); });
    return () => controller.abort();
  }, [selectedId, reload]);

  const handleReview = async decision => {
    const task = detail.task;
    if (!task || task.status !== 'SUBMITTED' || mutation.loading) return;
    const note = feedback.trim();
    if (decision === 'REQUEST_CHANGES' && !note) { setMutation({ loading: false, error: 'Nhập phản hồi bắt buộc khi yêu cầu chỉnh sửa.', success: '', conflict: false }); return; }
    setMutation({ loading: true, error: '', success: '', conflict: false });
    try {
      await reviewTask(task.id, { expectedVersion: task.version, decision, ...(decision === 'REQUEST_CHANGES' ? { feedback: note } : {}) });
      setSelectedId(null); setFeedback('');
      setMutation({ loading: false, error: '', success: decision === 'APPROVE' ? 'Đã duyệt kết quả.' : 'Đã yêu cầu chỉnh sửa kết quả.', conflict: false });
      refresh();
    } catch (error) {
      setMutation({ loading: false, error: getTaskApiErrorMessage(error), success: '', conflict: error?.response?.status === 409 });
    }
  };

  const canNext = !queue.loading && pageIndex + 1 < (queue.totalPages || 0);
  const submissionRows = detail.task
    ? markCurrentCycleSubmissions(detail.task.submissions || [], detail.task.assignmentCycle, detail.task.latestSubmission?.id)
    : [];
  return <div className="admin-acceptance-page">
    <header className="admin-page-heading"><p className="admin-page-heading__eyebrow">ĐIỀU HÀNH</p><h2>Nghiệm thu</h2><p>Xem kết quả hiện tại và lịch sử công việc đang chờ duyệt.</p></header>
    <div className="admin-task-toolbar"><Button variant="outline" onClick={refresh}>Tải lại</Button></div>
    {mutation.success && <p role="status" className="admin-task-notice">{mutation.success}</p>}
    {mutation.error && <div role="alert" className="admin-task-error"><p>{mutation.error}</p>{mutation.conflict && <Button size="sm" variant="outline" onClick={refresh}>Tải lại dữ liệu</Button>}</div>}
    {queue.error ? <ErrorState title="Không thể tải danh sách nghiệm thu" message={getTaskApiErrorMessage(queue.error)} onRetry={refresh} /> : queue.loading ? <p role="status" className="admin-task-loading">Đang tải danh sách nghiệm thu…</p> : !queue.items.length ? <EmptyState title="Không có công việc chờ nghiệm thu" description="Công việc đã nộp sẽ xuất hiện tại đây." /> : <>
      <AdminTable label="Công việc chờ nghiệm thu"><thead><tr><th>Công việc</th><th>Người phụ trách</th><th>Đã nộp</th><th>Ưu tiên</th><th></th></tr></thead><tbody>{queue.items.map(task => <tr key={task.id}><td><strong>{task.title}</strong></td><td>{task.assignee?.displayName || '—'}</td><td><StatusBadge tone="info">Chờ nghiệm thu</StatusBadge></td><td>{task.priority}</td><td><Button size="sm" variant="outline" onClick={() => { setSelectedId(task.id); setFeedback(''); setMutation({ loading: false, error: '', success: '', conflict: false }); }}>Xem kết quả</Button></td></tr>)}</tbody></AdminTable>
      <div className="admin-task-pagination"><span>{queue.totalItems ?? queue.items.length} công việc · Trang {pageIndex + 1} / {queue.totalPages || 1}</span><div><Button size="sm" variant="outline" disabled={pageIndex === 0} onClick={() => setPageIndex(value => Math.max(0, value - 1))}>Trước</Button><Button size="sm" variant="outline" disabled={!canNext} onClick={() => setPageIndex(value => value + 1)}>Tiếp</Button></div></div>
    </>}
    {selectedId != null && <PopCard as="section" tone="lavender" className="admin-acceptance-detail" aria-labelledby="admin-acceptance-detail-title">
      <div className="admin-task-form-heading"><div><p className="admin-task-eyebrow">CHI TIẾT NGHIỆM THU</p><h3 id="admin-acceptance-detail-title">{detail.task?.title || 'Kết quả công việc'}</h3></div><Button size="sm" variant="ghost" onClick={() => setSelectedId(null)}>Đóng</Button></div>
      {detail.loading ? <p role="status">Đang tải chi tiết…</p> : detail.error ? <ErrorState message={getTaskApiErrorMessage(detail.error)} onRetry={refresh} /> : detail.task && <>
        {detail.task.status !== 'SUBMITTED' && <p role="status" className="admin-task-notice">Công việc đã được xử lý; hành động nghiệm thu đã khóa.</p>}
        <p><strong>Nhân viên:</strong> {detail.task.assignee?.displayName} · {detail.task.assignee?.employeeNumber}</p>
        <p><strong>Chu kỳ giao việc:</strong> {detail.task.assignmentCycle} · <strong>Trạng thái:</strong> {detail.task.status}</p>
        <section className="admin-acceptance-result"><h4>Tất cả kết quả đã nộp</h4>{submissionRows.length ? <ol className="admin-task-submission-list">{submissionRows.map(submission => <li className={`${submission.isCurrentCycle ? 'is-current-cycle' : 'is-previous-cycle'}${submission.isLatestSubmission ? ' is-latest-submission' : ''}`} key={submission.id}><div className="admin-submission-heading"><strong>Lần nộp {submission.submissionNumber} · Chu kỳ {submission.assignmentCycle}</strong><StatusBadge tone={submission.isLatestSubmission ? 'success' : submission.isCurrentCycle ? 'info' : 'neutral'}>{submission.isLatestSubmission ? 'Kết quả hiện tại' : submission.isCurrentCycle ? 'Chu kỳ hiện tại' : 'Chu kỳ trước'}</StatusBadge></div><p>{submission.resultDescription || 'Không có mô tả kết quả.'}</p>{submission.resultUrl && <p><a href={submission.resultUrl} target="_blank" rel="noreferrer">Mở liên kết kết quả</a></p>}<small>Nộp bởi {submission.submitterName || '—'} · {submission.submittedAt ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(submission.submittedAt)) : '—'}</small></li>)}</ol> : <p>Chưa có kết quả được nộp.</p>}</section>
        <section className="admin-acceptance-history"><h4>Lịch sử thao tác</h4>{detail.task.history?.length ? <ol>{detail.task.history.map(event => <li key={event.id}><strong>{event.action}</strong> · {event.actorName || 'Hệ thống'} · {event.occurredAt ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(event.occurredAt)) : ''}{event.note && <p>{event.note}</p>}{event.details && Object.keys(event.details).length > 0 && <pre>{JSON.stringify(event.details, null, 2)}</pre>}</li>)}</ol> : <p>Chưa có lịch sử.</p>}</section>
        {detail.task.status === 'SUBMITTED' && <div className="admin-acceptance-review"><label className="admin-field"><span className="admin-field__label">Phản hồi khi yêu cầu chỉnh sửa</span><textarea name="feedback" value={feedback} onChange={event => setFeedback(event.target.value)} maxLength={2000} disabled={mutation.loading} aria-describedby="admin-acceptance-feedback-hint" /></label><p id="admin-acceptance-feedback-hint">Bắt buộc khi yêu cầu chỉnh sửa; không gửi nội dung này khi duyệt.</p><div className="admin-task-form__actions"><Button loading={mutation.loading} onClick={() => handleReview('APPROVE')}>Duyệt kết quả</Button><Button variant="outline" disabled={mutation.loading} onClick={() => handleReview('REQUEST_CHANGES')}>Yêu cầu chỉnh sửa</Button></div></div>}
      </>}
    </PopCard>}
  </div>;
}
