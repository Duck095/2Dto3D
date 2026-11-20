import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import EmptyState from '../../components/ui/EmptyState';
import ErrorState from '../../components/ui/ErrorState';
import { getMyStaffProfile } from '../../features/employee/employeeService';
import { getEmployeeTaskErrorMessage, listMyTasks } from '../../features/employee/tasks/employeeTaskService';
import { EMPLOYEE_TASK_PAGE_SIZE } from '../../features/employee/tasks/employeeTaskPresentation';
import './employee-workspace.css';

function TaskStatus({ status }) {
  const label = ({ ASSIGNED: 'Đã giao', IN_PROGRESS: 'Đang thực hiện', SUBMITTED: 'Chờ nghiệm thu', REVISION_REQUESTED: 'Cần chỉnh sửa', APPROVED: 'Đã duyệt' })[status] || status;
  return <span className={`employee-status employee-status--${String(status).toLowerCase()}`}>{label}</span>;
}

export default function EmployeeWorkspacePage() {
  const [reload, setReload] = useState(0);
  const [pageIndex, setPageIndex] = useState(0);
  const [page, setPage] = useState({ profile: null, tasks: null, loading: true, error: null });

  const load = useCallback(async (signal) => {
    setPage(current => ({ ...current, loading: true, error: null }));
    try {
      const [profile, tasks] = await Promise.all([
        getMyStaffProfile({ signal }),
        listMyTasks({ page: pageIndex, size: EMPLOYEE_TASK_PAGE_SIZE }, { signal }),
      ]);
      if (!signal?.aborted) setPage({ profile, tasks, loading: false, error: null });
    } catch (error) {
      if (!signal?.aborted) setPage(current => ({ ...current, loading: false, error }));
    }
  }, [pageIndex]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, reload]);

  return <div className="employee-workspace">
    <header className="employee-page-heading"><p className="employee-eyebrow">KHU VỰC NHÂN VIÊN</p><h2>Công việc của tôi</h2><p>Hồ sơ và các công việc được giao cho tài khoản hiện tại.</p></header>
    {page.loading ? <p role="status">Đang tải hồ sơ và công việc…</p> : page.error ? <ErrorState title="Không thể tải khu vực nhân viên" message={getEmployeeTaskErrorMessage(page.error)} onRetry={() => setReload(value => value + 1)} /> : <>
      <section className="employee-profile-card" aria-labelledby="employee-profile-title">
        <h3 id="employee-profile-title">Hồ sơ nhân viên</h3>
        <dl><div><dt>Mã nhân viên</dt><dd>{page.profile?.employeeNumber || '—'}</dd></div><div><dt>Bộ phận</dt><dd>{page.profile?.department || '—'}</dd></div><div><dt>Chức danh</dt><dd>{page.profile?.jobTitle || '—'}</dd></div><div><dt>Trạng thái</dt><dd>{page.profile?.status || '—'}</dd></div><div><dt>Ngày vào làm</dt><dd>{page.profile?.joinedAt || '—'}</dd></div></dl>
      </section>
      <section className="employee-task-section" aria-labelledby="employee-tasks-title">
        <div className="employee-section-heading"><div><h3 id="employee-tasks-title">Danh sách công việc</h3><p>{page.tasks?.totalItems ?? 0} công việc được giao</p></div><button type="button" className="employee-text-button" onClick={() => setReload(value => value + 1)}>Tải lại</button></div>
        {!page.tasks?.items?.length ? <EmptyState title="Chưa có công việc được giao" description="Công việc mới sẽ xuất hiện tại đây sau khi được phân công." /> : <div className="employee-task-list">{page.tasks.items.map(task => <article className="employee-task-card" key={task.id}>
          <div className="employee-task-card__top"><h4><Link to={`/employee/tasks/${task.id}`}>{task.title}</Link></h4><TaskStatus status={task.status} /></div>
          <p>{task.description || 'Không có mô tả.'}</p>
          <div className="employee-task-card__meta"><span>Ưu tiên: {task.priority}</span><span>Hạn: {task.dueAt ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(task.dueAt)) : 'Chưa đặt hạn'}</span>{task.overdue && <span className="employee-overdue">Quá hạn</span>}</div>
        </article>)}</div>}
        {page.tasks?.totalPages > 1 && <nav className="employee-pagination" aria-label="Phân trang công việc"><button type="button" disabled={pageIndex <= 0} onClick={() => setPageIndex(value => value - 1)}>Trước</button><span>Trang {pageIndex + 1} / {page.tasks.totalPages}</span><button type="button" disabled={pageIndex + 1 >= page.tasks.totalPages} onClick={() => setPageIndex(value => value + 1)}>Sau</button></nav>}
      </section>
    </>}
  </div>;
}
