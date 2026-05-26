import { useState } from 'react';
import type * as React from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useRealtime } from '../contexts/RealtimeContext';
import { tierLabel } from '../utils/billing';

const NAV_ITEMS = [
  { to: '/dashboard', label: 'แดชบอร์ด', icon: HomeIcon },
  { to: '/devices', label: 'อุปกรณ์', icon: DeviceIcon },
  { to: '/videos', label: 'วิดีโอ', icon: VideoIcon },
  { to: '/live', label: 'เริ่ม Live', icon: LiveIcon },
  { to: '/live/active', label: 'Live ที่กำลังออน', icon: BroadcastIcon },
  { to: '/history', label: 'ประวัติ', icon: HistoryIcon },
  { to: '/billing', label: 'การชำระเงิน', icon: BillingIcon },
];

export function AppLayout() {
  const { state, logout } = useAuth();
  const { connected } = useRealtime();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);

  async function handleLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="min-h-full flex flex-col">
      {/* Top bar */}
      <header className="bg-white border-b border-slate-200 flex items-center px-4 py-3 sticky top-0 z-30">
        <button
          type="button"
          className="md:hidden mr-2 p-1.5 rounded hover:bg-slate-100"
          onClick={() => setMenuOpen((o) => !o)}
          aria-label="เมนู"
        >
          <BurgerIcon />
        </button>
        <div className="flex items-center gap-2">
          <span className="w-7 h-7 rounded bg-brand-600 text-white flex items-center justify-center font-bold text-sm">
            T
          </span>
          <span className="font-semibold text-slate-800">TiktokRerun</span>
        </div>
        <div className="ml-auto flex items-center gap-3 text-sm">
          <span
            className={`hidden sm:inline-flex items-center gap-1.5 text-xs px-2 py-1 rounded-full border ${
              connected
                ? 'bg-emerald-50 border-emerald-200 text-emerald-700'
                : 'bg-slate-50 border-slate-200 text-slate-500'
            }`}
            title={connected ? 'เชื่อมต่อแบบเรียลไทม์' : 'กำลังเชื่อมต่อ…'}
          >
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                connected ? 'bg-emerald-500' : 'bg-slate-400'
              }`}
            />
            {connected ? 'เรียลไทม์' : 'ออฟไลน์'}
          </span>
          {state.user?.subscription_tier ? (
            <span
              className="hidden sm:inline-flex items-center text-xs px-2 py-1 rounded-full border bg-brand-50 border-brand-200 text-brand-700 font-medium"
              title="แพ็กเกจปัจจุบัน"
            >
              {tierLabel(state.user.subscription_tier)}
            </span>
          ) : null}
          <span className="hidden sm:inline text-slate-600 truncate max-w-[180px]">
            {state.user?.email}
          </span>
          <button
            type="button"
            onClick={handleLogout}
            className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
          >
            ออกจากระบบ
          </button>
        </div>
      </header>

      <div className="flex-1 flex">
        {/* Side nav (desktop) */}
        <aside className="hidden md:block w-56 border-r border-slate-200 bg-white">
          <nav className="p-3 flex flex-col gap-1">
            {NAV_ITEMS.map((item) => (
              <NavItem key={item.to} {...item} />
            ))}
          </nav>
        </aside>

        {/* Side nav (mobile drawer) */}
        {menuOpen && (
          <>
            <div
              className="fixed inset-0 bg-black/30 z-30 md:hidden"
              onClick={() => setMenuOpen(false)}
            />
            <aside className="fixed left-0 top-0 bottom-0 w-64 bg-white z-40 shadow-xl md:hidden">
              <div className="p-3 border-b border-slate-200 flex items-center justify-between">
                <span className="font-semibold">เมนู</span>
                <button
                  type="button"
                  onClick={() => setMenuOpen(false)}
                  className="p-1 text-slate-500"
                  aria-label="ปิด"
                >
                  ✕
                </button>
              </div>
              <nav className="p-3 flex flex-col gap-1">
                {NAV_ITEMS.map((item) => (
                  <NavItem
                    key={item.to}
                    {...item}
                    onClick={() => setMenuOpen(false)}
                  />
                ))}
              </nav>
            </aside>
          </>
        )}

        <main className="flex-1 min-w-0 p-4 sm:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

interface NavItemProps {
  to: string;
  label: string;
  icon: () => React.JSX.Element;
  onClick?: () => void;
}

function NavItem({ to, label, icon: Icon, onClick }: NavItemProps) {
  return (
    <NavLink
      to={to}
      end={to === '/live'}
      onClick={onClick}
      className={({ isActive }) =>
        [
          'flex items-center gap-2 rounded px-3 py-2 text-sm transition-colors',
          isActive
            ? 'bg-brand-50 text-brand-700 font-medium'
            : 'text-slate-700 hover:bg-slate-100',
        ].join(' ')
      }
    >
      <Icon />
      {label}
    </NavLink>
  );
}

function HomeIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 12 12 3l9 9" />
      <path d="M5 10v10h14V10" />
    </svg>
  );
}
function DeviceIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="6" y="2" width="12" height="20" rx="2" />
      <path d="M11 18h2" />
    </svg>
  );
}
function VideoIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="5" width="14" height="14" rx="2" />
      <path d="m22 8-5 4 5 4V8Z" />
    </svg>
  );
}
function LiveIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M5 12a7 7 0 0 1 14 0" />
      <path d="M2 12a10 10 0 0 1 20 0" />
    </svg>
  );
}
function BroadcastIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="2" />
      <path d="M7 7a7 7 0 0 0 0 10" />
      <path d="M17 7a7 7 0 0 1 0 10" />
    </svg>
  );
}
function HistoryIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 12a9 9 0 1 0 3-6.7" />
      <path d="M3 5v5h5" />
      <path d="M12 7v5l3 2" />
    </svg>
  );
}
function BillingIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2" y="6" width="20" height="12" rx="2" />
      <path d="M2 10h20" />
      <path d="M6 14h4" />
    </svg>
  );
}
function BurgerIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 6h16M4 12h16M4 18h16" />
    </svg>
  );
}
