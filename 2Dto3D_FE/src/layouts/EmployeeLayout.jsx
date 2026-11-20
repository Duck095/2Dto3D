import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import Button from '../components/ui/Button';
import { useAuth } from '../context/AuthContext';

export default function EmployeeLayout() {
  const { logout, currentUser, currentUserStatus } = useAuth();
  const navigate = useNavigate();
  const identity = currentUserStatus === 'verified' ? currentUser : null;
  const signOut = () => { logout(); navigate('/login', { replace: true }); };

  return (
    <div className="min-h-screen bg-pocket-paper text-pocket-plum md:flex">
      <aside className="border-b border-pocket-line bg-white/80 px-4 py-5 md:min-h-screen md:w-60 md:shrink-0 md:border-b-0 md:border-r md:px-5">
        <p className="text-xs font-bold uppercase tracking-[0.16em] text-slate-500">2Dto3D</p>
        <p className="mt-1 text-sm font-semibold">Employee workspace</p>
        <nav className="mt-6 flex gap-3 md:flex-col" aria-label="Điều hướng nhân viên">
          <NavLink end to="/employee" className={({ isActive }) => `rounded-xl px-3 py-2 text-sm font-semibold ${isActive ? 'bg-pocket-lavender/50' : 'hover:bg-slate-100'}`}>Công việc của tôi</NavLink>
        </nav>
        <p className="mt-8 text-xs text-slate-500">Quyền nhân viên đã xác minh</p>
      </aside>
      <div className="min-w-0 flex-1">
        <header className="flex min-h-16 items-center justify-between gap-4 border-b border-pocket-line bg-white/70 px-4 py-3 sm:px-6">
          <div><p className="text-xs text-slate-500">Không gian làm việc</p><h1 className="font-bold">Xin chào{identity?.name ? `, ${identity.name}` : ''}</h1></div>
          <Button variant="outline" size="sm" onClick={signOut}>Đăng xuất</Button>
        </header>
        <main className="p-4 sm:p-6 lg:p-8"><div className="mx-auto w-full max-w-5xl"><Outlet /></div></main>
      </div>
    </div>
  );
}
