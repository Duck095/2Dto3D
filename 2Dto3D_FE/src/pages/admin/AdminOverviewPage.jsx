import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import KpiCard from '../../components/admin/KpiCard';
import EmptyState from '../../components/ui/EmptyState';
import ErrorState from '../../components/ui/ErrorState';
import { getTaskApiErrorMessage, getTaskSummary } from '../../features/admin/tasks/taskService';
import './task-management.css';

function TaskKpiLink({ to, ...props }) {
  return <Link className="admin-task-kpi-link" to={to}><KpiCard {...props} /></Link>;
}

export default function AdminOverviewPage() {
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null);
    getTaskSummary({ signal: controller.signal }).then(data => {
      if (!controller.signal.aborted) { setSummary(data); setLoading(false); }
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason); setSummary(null); setLoading(false); }
    });
    return () => controller.abort();
  }, [reload]);

  return (
    <div className="admin-overview">
      <header className="admin-page-heading">
        <p className="admin-page-heading__eyebrow">ĐIỀU HÀNH</p>
        <h2>Tổng quan</h2>
        <p>Không gian quản trị 2Dto3D.</p>
      </header>

      {loading ? <p role="status">Đang tải số liệu công việc…</p> : error ? <ErrorState title="Không thể tải tổng quan" message={getTaskApiErrorMessage(error)} onRetry={() => setReload(value => value + 1)} /> : !summary || summary.total === 0 ? <EmptyState title="Chưa có công việc" description="Số liệu sẽ xuất hiện sau khi có công việc trong hệ thống." /> : <section className="admin-task-summary" aria-label="Tổng quan công việc">
        <TaskKpiLink to="/admin/tasks" title="Tổng công việc" value={summary.total} tone="lavender" detail="Tất cả trạng thái" />
        <TaskKpiLink to="/admin/tasks" title="Đã giao" value={summary.assigned} tone="cyan" />
        <TaskKpiLink to="/admin/tasks" title="Đang thực hiện" value={summary.inProgress} tone="sun" />
        <TaskKpiLink to="/admin/acceptance" title="Chờ nghiệm thu" value={summary.pendingAcceptance} tone="coral" />
        <TaskKpiLink to="/admin/tasks" title="Cần chỉnh sửa" value={summary.revisionRequested} tone="pink" />
        <TaskKpiLink to="/admin/tasks" title="Đã duyệt" value={summary.approved} tone="mint" />
        <TaskKpiLink to="/admin/tasks" title="Quá hạn" value={summary.overdue} tone="coral" detail={`Đã nộp: ${summary.submitted}`} />
      </section>}
    </div>
  );
}
