// Admin login page — calls the backoffice-scoped POST /api/admin/auth/login,
// which sets the dedicated `tkr_admin_session` cookie (distinct from the
// portal `tkr_session`). The server enforces role='admin' and returns 403
// FORBIDDEN for non-admin accounts; AdminAuthContext maps that to a
// "บัญชีนี้ไม่มีสิทธิ์เข้าใช้ Backoffice" message.

import { useEffect, useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAdminAuth } from '../contexts/AdminAuthContext'
import { ApiError } from '../api/client'

interface LocationState {
  from?: string
}

export function AdminLogin() {
  const { user, loading, forbidden, login } = useAdminAuth()
  const nav = useNavigate()
  const loc = useLocation()
  const from = (loc.state as LocationState | null)?.from ?? '/admin/metrics'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (forbidden) {
      setErr('บัญชีนี้ไม่ใช่ Admin — กรุณาใช้บัญชี Admin เท่านั้น')
    }
  }, [forbidden])

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center text-sm text-slate-500">
        กำลังโหลด…
      </div>
    )
  }

  if (user) {
    return <Navigate to={from} replace />
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setErr(null)
    setBusy(true)
    try {
      await login(email.trim(), password)
      nav(from, { replace: true })
    } catch (e: unknown) {
      if (e instanceof ApiError) {
        setErr(e.message || 'เข้าสู่ระบบไม่สำเร็จ')
      } else {
        setErr('เข้าสู่ระบบไม่สำเร็จ')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
      >
        <div className="mb-4">
          <h1 className="text-lg font-semibold text-slate-900">
            TiktokRerun Backoffice
          </h1>
          <p className="text-sm text-slate-500">
            เข้าใช้งานสำหรับทีมแอดมินเท่านั้น
          </p>
        </div>

        <label className="mb-3 block">
          <span className="mb-1 block text-xs font-medium text-slate-700">
            อีเมล
          </span>
          <input
            type="email"
            required
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </label>
        <label className="mb-4 block">
          <span className="mb-1 block text-xs font-medium text-slate-700">
            รหัสผ่าน
          </span>
          <input
            type="password"
            required
            minLength={8}
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </label>

        {err ? (
          <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800">
            {err}
          </div>
        ) : null}

        <button
          type="submit"
          disabled={busy}
          className="w-full rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {busy ? 'กำลังเข้าสู่ระบบ…' : 'เข้าสู่ระบบ'}
        </button>
      </form>
    </div>
  )
}
