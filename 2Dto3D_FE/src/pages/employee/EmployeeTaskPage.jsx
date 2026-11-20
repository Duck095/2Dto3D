import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import Button from '../../components/ui/Button';
import ErrorState from '../../components/ui/ErrorState';
import { getEmployeeTaskErrorMessage, getMyTask, startMyTask, submitMyTask } from '../../features/employee/tasks/employeeTaskService';
import { buildEmployeeSubmissionPayload, canStartEmployeeTask, canSubmitEmployeeTask, getCurrentCycleSubmission, getEmployeeTaskMutationErrorType } from '../../features/employee/tasks/employeeTaskPresentation';
import './employee-workspace.css';

const formatDate = value => value ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : 'Chưa đặt hạn';

export default function EmployeeTaskPage() {
  const { taskId } = useParams();
  const [task, setTask] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [notice, setNotice] = useState('');
  const [mutationError, setMutationError] = useState(null);
  const [retryAction, setRetryAction] = useState(null);
  const [form, setForm] = useState({ resultDescription: '', resultUrl: '' });
  const mutationLockRef = useRef(false);

  const reloadTask = useCallback(async (signal, { showLoading = true } = {}) => {
    if (showLoading) setLoading(true);
    setError(null);
    try {
      const result = await getMyTask(taskId, { signal });
      if (!signal?.aborted) { setTask(result); setLoading(false); }
    } catch (reason) {
      if (!signal?.aborted) {
        if (showLoading) setError(reason);
        else {
          setRetryAction({ type: 'reload' });
          setMutationError({ type: 'retryable', message: 'Không thể tải dữ liệu mới nhất. Nội dung đã nhập vẫn được giữ; hãy thử tải lại.' });
        }
        setLoading(false);
      }
    }
  }, [taskId]);

  useEffect(() => {
    const controller = new AbortController();
    void reloadTask(controller.signal);
    return () => controller.abort();
  }, [reloadTask]);

  const handleMutationError = async (reason, retry = null) => {
    const type = getEmployeeTaskMutationErrorType(reason);
    if (type === 'conflict') {
      setRetryAction(null);
      setNotice('Công việc đã được cập nhật ở nơi khác. Đã tải dữ liệu mới nhất.');
      setMutationError(null);
      await reloadTask(undefined, { showLoading: false });
      return;
    }

    setNotice('');
    if (type === 'retryable') {
      setRetryAction(retry);
      setMutationError({
        type,
        message: 'Không thể kết nối hoặc máy chủ gặp sự cố. Dữ liệu đã nhập vẫn được giữ; hãy thử lại.',
      });
      return;
    }

    setRetryAction(null);
    const serverMessage = reason?.response?.data?.message || reason?.response?.data?.detail;
    setMutationError({ type, message: serverMessage || getEmployeeTaskErrorMessage(reason) });
  };

  const handleStart = async () => {
    if (mutationLockRef.current) return;
    mutationLockRef.current = true;
    setActionLoading(true); setNotice('');
    setMutationError(null); setRetryAction(null);
    try {
      setTask(await startMyTask(task.id, task.version));
    } catch (reason) {
      await handleMutationError(reason, { type: 'start' });
    } finally {
      mutationLockRef.current = false;
      setActionLoading(false);
    }
  };

  const submitResult = async (submissionForm) => {
    if (mutationLockRef.current) return;
    mutationLockRef.current = true;
    setActionLoading(true); setNotice('');
    setMutationError(null); setRetryAction(null);
    try {
      await submitMyTask(task.id, buildEmployeeSubmissionPayload(submissionForm, task.version));
      setNotice('Đã gửi kết quả.');
      setMutationError(null); setRetryAction(null);
      setForm({ resultDescription: '', resultUrl: '' });
      await reloadTask();
    } catch (reason) {
      const type = getEmployeeTaskMutationErrorType(reason);
      if (type === 'retryable') {
        await handleMutationError(reason, { type: 'submit' });
      } else {
        await handleMutationError(reason);
      }
    } finally {
      mutationLockRef.current = false;
      setActionLoading(false);
    }
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    await submitResult(form);
  };

  const handleRetry = async () => {
    if (!retryAction || mutationLockRef.current) return;
    if (retryAction.type === 'start') await handleStart();
    if (retryAction.type === 'submit') await submitResult(form);
    if (retryAction.type === 'reload') {
      setMutationError(null);
      setRetryAction(null);
      await reloadTask(undefined, { showLoading: false });
    }
  };

  if (loading) return <p role="status">Đang tải công việc…</p>;
  if (error) return <ErrorState title="Không thể mở công việc" message={getEmployeeTaskErrorMessage(error)} onRetry={() => void reloadTask()} />;
  if (!task) return <ErrorState title="Không tìm thấy công việc" message="Công việc này không được giao cho tài khoản hiện tại." />;

  const currentSubmission = getCurrentCycleSubmission(task);
  return <article className="employee-task-detail">
    <Link className="employee-back-link" to="/employee">← Công việc của tôi</Link>
    <header className="employee-page-heading"><p className="employee-eyebrow">CHI TIẾT CÔNG VIỆC · CHU KỲ {task.assignmentCycle}</p><h2>{task.title}</h2><p>{task.description || 'Không có mô tả.'}</p></header>
    <dl className="employee-task-facts"><div><dt>Trạng thái</dt><dd>{task.status}</dd></div><div><dt>Ưu tiên</dt><dd>{task.priority}</dd></div><div><dt>Hạn hoàn thành</dt><dd>{formatDate(task.dueAt)}{task.overdue && <strong className="employee-overdue"> · Quá hạn</strong>}</dd></div><div><dt>Chu kỳ giao việc</dt><dd>{task.assignmentCycle}</dd></div></dl>
    {task.latestRevisionFeedback && <section className="employee-feedback" aria-label="Phản hồi nghiệm thu"><h3>Phản hồi nghiệm thu</h3><p>{task.latestRevisionFeedback}</p>{task.reviewedAt && <small>{formatDate(task.reviewedAt)}</small>}</section>}
    {currentSubmission && <section className="employee-submission-card"><h3>Kết quả hiện tại · Lần nộp {currentSubmission.submissionNumber}</h3><p>{currentSubmission.resultDescription || 'Không có mô tả kết quả.'}</p>{currentSubmission.resultUrl && <p><a href={currentSubmission.resultUrl} target="_blank" rel="noreferrer">Mở liên kết kết quả</a></p>}<small>{formatDate(currentSubmission.submittedAt)}</small></section>}
    {notice && <p className="employee-notice" role="status">{notice}</p>}
    {mutationError && <div className={`employee-mutation-error employee-mutation-error--${mutationError.type}`} role="alert"><p>{mutationError.message}</p>{retryAction && <Button type="button" variant="outline" size="sm" loading={actionLoading} onClick={handleRetry}>Thử gửi lại</Button>}</div>}
    {canStartEmployeeTask(task) && <Button loading={actionLoading} onClick={handleStart}>Bắt đầu công việc</Button>}
    {canSubmitEmployeeTask(task) && <form className="employee-result-form" onSubmit={handleSubmit} aria-busy={actionLoading}><h3>{task.status === 'REVISION_REQUESTED' ? 'Gửi lại kết quả' : 'Gửi kết quả'}</h3><label>Mô tả kết quả<textarea maxLength={10000} value={form.resultDescription} onChange={event => setForm(value => ({ ...value, resultDescription: event.target.value }))} rows={5} /></label><label>Liên kết kết quả<input type="url" pattern="https://.+" maxLength={2048} value={form.resultUrl} onChange={event => setForm(value => ({ ...value, resultUrl: event.target.value }))} placeholder="https://…" /></label><small>Chỉ chấp nhận liên kết HTTPS.</small><Button loading={actionLoading} type="submit" disabled={actionLoading || (!form.resultDescription.trim() && !form.resultUrl.trim())}>Nộp kết quả</Button></form>}
    {task.status === 'SUBMITTED' && <p className="employee-notice" role="status">Kết quả đang chờ quản lý nghiệm thu.</p>}
    {task.status === 'APPROVED' && <p className="employee-notice" role="status">Công việc đã được duyệt.</p>}
    <button type="button" className="employee-text-button" disabled={actionLoading} onClick={() => void reloadTask()}>Tải lại dữ liệu</button>
  </article>;
}
