// Top-level router. Public route: /admin/login. Everything else is wrapped
// by ProtectedAdminRoute + the persistent sidebar Layout.

import { Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { ProtectedAdminRoute } from './components/ProtectedAdminRoute'
import { AdminLogin } from './pages/AdminLogin'
import { AdminChangePasswordPage } from './pages/AdminChangePasswordPage'
import { AdminUsers } from './pages/AdminUsers'
import { AdminDevices } from './pages/AdminDevices'
import { AdminMetrics } from './pages/AdminMetrics'
import { AdminLives } from './pages/AdminLives'
import { AdminSubscriptions } from './pages/AdminSubscriptions'
import { AdminVideos } from './pages/AdminVideos'
import { AdminReleases } from './pages/AdminReleases'

export default function App() {
  return (
    <Routes>
      <Route path="/admin/login" element={<AdminLogin />} />

      {/* Auth-required but without the admin Layout shell — usable while
          must_change_password forces redirect away from main app. */}
      <Route
        path="/admin/change-password"
        element={
          <ProtectedAdminRoute>
            <AdminChangePasswordPage />
          </ProtectedAdminRoute>
        }
      />

      <Route
        element={
          <ProtectedAdminRoute>
            <Layout />
          </ProtectedAdminRoute>
        }
      >
        <Route path="/admin/metrics" element={<AdminMetrics />} />
        <Route path="/admin/users" element={<AdminUsers />} />
        <Route path="/admin/devices" element={<AdminDevices />} />
        <Route path="/admin/lives" element={<AdminLives />} />
        <Route path="/admin/videos" element={<AdminVideos />} />
        <Route path="/admin/subscriptions" element={<AdminSubscriptions />} />
        <Route path="/admin/releases" element={<AdminReleases />} />
      </Route>

      {/* Default + fallback → Metrics dashboard. */}
      <Route path="/" element={<Navigate to="/admin/metrics" replace />} />
      <Route path="/admin" element={<Navigate to="/admin/metrics" replace />} />
      <Route path="*" element={<Navigate to="/admin/metrics" replace />} />
    </Routes>
  )
}
