// Persistent admin shell: left sidebar + top bar + content slot.
// Renders via React Router's <Outlet/> so it stays mounted across route changes.

import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAdminAuth } from '../contexts/AdminAuthContext'

const NAV = [
  { to: '/admin/metrics', label: 'Metrics' },
  { to: '/admin/users', label: 'Users' },
  { to: '/admin/devices', label: 'Devices' },
  { to: '/admin/lives', label: 'Lives' },
  { to: '/admin/videos', label: 'Videos' },
  { to: '/admin/subscriptions', label: 'Subscriptions' },
]

export function Layout() {
  const { user, logout } = useAdminAuth()
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    navigate('/admin/login', { replace: true })
  }

  return (
    <div className="flex h-full min-h-screen bg-slate-50">
      <aside className="flex w-56 shrink-0 flex-col border-r border-slate-200 bg-white">
        <div className="border-b border-slate-200 px-4 py-4">
          <div className="text-sm font-semibold text-slate-900">Reruni</div>
          <div className="text-xs text-slate-500">Backoffice</div>
        </div>
        <nav className="flex-1 px-2 py-2">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `mb-0.5 block rounded px-3 py-1.5 text-sm ${
                  isActive
                    ? 'bg-blue-50 font-semibold text-blue-700'
                    : 'text-slate-700 hover:bg-slate-100'
                }`
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-slate-200 px-4 py-3 text-xs">
          <div className="truncate text-slate-700" title={user?.email ?? ''}>
            {user?.email}
          </div>
          <div className="text-slate-500">role: {user?.role}</div>
          <button
            type="button"
            onClick={handleLogout}
            className="mt-2 w-full rounded border border-slate-300 bg-white px-2 py-1 text-xs font-medium hover:bg-slate-100"
          >
            ออกจากระบบ
          </button>
        </div>
      </aside>
      <main className="min-w-0 flex-1 overflow-x-hidden">
        <Outlet />
      </main>
    </div>
  )
}
