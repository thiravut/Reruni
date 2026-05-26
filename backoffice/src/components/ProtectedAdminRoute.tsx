// Route guard — sends anyone without an admin session back to /admin/login.
// Used to wrap the Layout in App.tsx.

import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAdminAuth } from '../contexts/AdminAuthContext'

export function ProtectedAdminRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAdminAuth()
  const loc = useLocation()

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center text-sm text-slate-500">
        กำลังตรวจสอบสิทธิ์…
      </div>
    )
  }

  if (!user) {
    return <Navigate to="/admin/login" replace state={{ from: loc.pathname }} />
  }

  // Forced password change after admin reset — block normal app navigation.
  if (user.must_change_password && loc.pathname !== '/admin/change-password') {
    return <Navigate to="/admin/change-password" replace />
  }

  return <>{children}</>
}
