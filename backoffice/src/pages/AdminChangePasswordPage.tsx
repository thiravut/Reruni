// Forced password change after admin reset (must_change_password === true),
// also usable voluntarily anytime via /admin/change-password.

import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAdminAuth } from '../contexts/AdminAuthContext'
import { authApi } from '../api/auth'
import { ApiError } from '../api/client'

function validateNewPassword(p: string): string | null {
  if (!p) return 'กรุณากรอกรหัสผ่านใหม่'
  if (p.length < 8) return 'รหัสผ่านต้องมีอย่างน้อย 8 ตัวอักษร'
  if (!/[A-Za-z]/.test(p)) return 'รหัสผ่านต้องมีตัวอักษรอย่างน้อย 1 ตัว'
  if (!/[0-9]/.test(p)) return 'รหัสผ่านต้องมีตัวเลขอย่างน้อย 1 ตัว'
  return null
}

export function AdminChangePasswordPage() {
  const { user, setUser, logout } = useAdminAuth()
  const nav = useNavigate()
  const forced = user?.must_change_password ?? false

  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setErr(null)
    setOk(null)

    if (!current) {
      setErr('กรุณากรอกรหัสผ่านปัจจุบัน')
      return
    }
    const newErr = validateNewPassword(next)
    if (newErr) {
      setErr(newErr)
      return
    }
    if (next !== confirm) {
      setErr('รหัสผ่านใหม่กับยืนยันไม่ตรงกัน')
      return
    }
    if (next === current) {
      setErr('รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม')
      return
    }

    setBusy(true)
    try {
      const res = await authApi.changePassword({
        current_password: current,
        new_password: next,
      })
      setUser(res.user)
      setOk('เปลี่ยนรหัสผ่านสำเร็จ')
      nav('/admin/metrics', { replace: true })
    } catch (e: unknown) {
      if (e instanceof ApiError) {
        if (e.code === 'INVALID_CREDENTIALS') {
          setErr('รหัสผ่านปัจจุบันไม่ถูกต้อง')
        } else if (e.code === 'WEAK_PASSWORD') {
          setErr(e.message || 'รหัสผ่านใหม่ไม่ผ่านเงื่อนไข')
        } else {
          setErr(e.message || 'เปลี่ยนรหัสผ่านไม่สำเร็จ')
        }
      } else {
        setErr('เปลี่ยนรหัสผ่านไม่สำเร็จ')
      }
    } finally {
      setBusy(false)
    }
  }

  async function onSignOut() {
    await logout()
    nav('/admin/login', { replace: true })
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
      >
        <div className="mb-4">
          <h1 className="text-lg font-semibold text-slate-900">
            ตั้งรหัสผ่านใหม่
          </h1>
          <p className="text-sm text-slate-500">
            {forced
              ? 'ผู้ดูแลรีเซ็ตรหัสผ่านบัญชีนี้ กรุณาตั้งรหัสผ่านใหม่ก่อนใช้งาน'
              : 'อัปเดตรหัสผ่านบัญชี Admin'}
          </p>
        </div>

        <label className="mb-3 block">
          <span className="mb-1 block text-xs font-medium text-slate-700">
            {forced ? 'รหัสผ่านชั่วคราว' : 'รหัสผ่านปัจจุบัน'}
          </span>
          <input
            type="password"
            required
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </label>

        <label className="mb-3 block">
          <span className="mb-1 block text-xs font-medium text-slate-700">
            รหัสผ่านใหม่
          </span>
          <input
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <span className="mt-1 block text-[11px] text-slate-500">
            อย่างน้อย 8 ตัวอักษร พร้อมตัวอักษรและตัวเลข
          </span>
        </label>

        <label className="mb-4 block">
          <span className="mb-1 block text-xs font-medium text-slate-700">
            ยืนยันรหัสผ่านใหม่
          </span>
          <input
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            className="w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </label>

        {err ? (
          <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800">
            {err}
          </div>
        ) : null}
        {ok ? (
          <div className="mb-3 rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-800">
            {ok}
          </div>
        ) : null}

        <button
          type="submit"
          disabled={busy}
          className="w-full rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {busy ? 'กำลังบันทึก…' : 'บันทึกรหัสผ่านใหม่'}
        </button>

        {forced && (
          <button
            type="button"
            onClick={onSignOut}
            className="mt-3 w-full text-center text-xs text-slate-500 hover:text-slate-700"
          >
            ออกจากระบบ
          </button>
        )}
      </form>
    </div>
  )
}
