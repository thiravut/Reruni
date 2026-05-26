// /admin/users — searchable, paginated, sortable user list with inline
// role change + reset password + delete actions.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { adminApi } from '../api/admin'
import { ApiError } from '../api/client'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { SortableHeader, type SortState } from '../components/SortableHeader'
import { StatusBadge } from '../components/StatusBadge'
import type { AdminUserRow, Role } from '../types/api'
import {
  formatDateTime,
  formatNumber,
  formatRelative,
} from '../utils/format'

type SortField = 'email' | 'role' | 'created_at' | 'device_count' | 'video_count' | 'last_active_at'

const PAGE_SIZE = 50

interface PendingAction {
  kind: 'delete' | 'reset' | 'role'
  user: AdminUserRow
  newRole?: Role
}

export function AdminUsers() {
  const [rows, setRows] = useState<AdminUserRow[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(0)
  const [q, setQ] = useState('')
  const [qDebounced, setQDebounced] = useState('')
  const [sort, setSort] = useState<SortState<SortField>>({
    field: 'created_at',
    direction: 'desc',
  })
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [pending, setPending] = useState<PendingAction | null>(null)
  const [busy, setBusy] = useState(false)
  const [tempPassword, setTempPassword] = useState<{
    email: string
    pwd: string
  } | null>(null)

  // 300ms debounce on the search box.
  useEffect(() => {
    const t = window.setTimeout(() => setQDebounced(q.trim()), 300)
    return () => window.clearTimeout(t)
  }, [q])

  // Reset to page 0 when search changes.
  useEffect(() => {
    setOffset(0)
  }, [qDebounced])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminApi.listUsers({
        limit: PAGE_SIZE,
        offset,
        q: qDebounced || undefined,
      })
      setRows(res.items)
      setTotal(res.total)
      setErr(null)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลดข้อมูลผู้ใช้ไม่สำเร็จ')
    } finally {
      setLoading(false)
    }
  }, [offset, qDebounced])

  useEffect(() => {
    void load()
  }, [load])

  // Client-side sort over the current page — API does not yet expose sort params.
  const sorted = useMemo(() => {
    const copy = [...rows]
    copy.sort((a, b) => {
      const dir = sort.direction === 'asc' ? 1 : -1
      const va = pickSort(a, sort.field)
      const vb = pickSort(b, sort.field)
      if (va == null && vb == null) return 0
      if (va == null) return 1
      if (vb == null) return -1
      if (va < vb) return -1 * dir
      if (va > vb) return 1 * dir
      return 0
    })
    return copy
  }, [rows, sort])

  function toggleSort(field: SortField) {
    setSort((s) =>
      s.field === field
        ? { field, direction: s.direction === 'asc' ? 'desc' : 'asc' }
        : { field, direction: 'asc' },
    )
  }

  async function confirmAction() {
    if (!pending) return
    setBusy(true)
    setErr(null)
    try {
      if (pending.kind === 'delete') {
        await adminApi.deleteUser(pending.user.id)
        setInfo(`ลบผู้ใช้ ${pending.user.email} เรียบร้อย`)
      } else if (pending.kind === 'reset') {
        const r = await adminApi.resetUserPassword(pending.user.id)
        setTempPassword({ email: pending.user.email, pwd: r.temp_password })
      } else if (pending.kind === 'role' && pending.newRole) {
        await adminApi.setUserRole(pending.user.id, pending.newRole)
        setInfo(`อัปเดต role ของ ${pending.user.email} เป็น ${pending.newRole}`)
      }
      setPending(null)
      await load()
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'การดำเนินการล้มเหลว')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        title="Users"
        subtitle="จัดการผู้ใช้ทั้งหมดในระบบ"
        actions={
          <button
            type="button"
            onClick={() => void load()}
            disabled={loading}
            className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-100 disabled:opacity-50"
          >
            {loading ? 'กำลังโหลด…' : 'รีเฟรช'}
          </button>
        }
      />
      <ErrorBanner message={err} onDismiss={() => setErr(null)} />
      {info ? (
        <div className="mx-6 mt-4 rounded border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-800">
          {info}
        </div>
      ) : null}

      <div className="p-6">
        <div className="mb-3 flex items-center gap-3">
          <input
            type="search"
            placeholder="ค้นหา email…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            className="w-72 rounded border border-slate-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <span className="text-xs text-slate-500">
            {formatNumber(total)} ผู้ใช้
          </span>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <div className="max-h-[70vh] overflow-auto">
            <table className="admin-table w-full border-collapse text-sm">
              <thead>
                <tr className="bg-slate-100">
                  <SortableHeader label="ID" field="email" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Email" field="email" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Role" field="role" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Created" field="created_at" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Devices" field="device_count" sort={sort} onSort={toggleSort} align="right" />
                  <SortableHeader label="Videos" field="video_count" sort={sort} onSort={toggleSort} align="right" />
                  <SortableHeader label="Last active" field="last_active_at" sort={sort} onSort={toggleSort} />
                  <th className="px-3 py-2 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {loading && rows.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="px-3 py-6 text-center text-sm text-slate-500">
                      กำลังโหลด…
                    </td>
                  </tr>
                ) : sorted.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="px-3 py-6 text-center text-sm text-slate-500">
                      ไม่พบผู้ใช้
                    </td>
                  </tr>
                ) : (
                  sorted.map((u) => (
                    <tr key={u.id} className="border-t border-slate-100 hover:bg-slate-50">
                      <td className="px-3 py-2 text-slate-500">{u.id}</td>
                      <td className="px-3 py-2 font-medium text-slate-900">{u.email}</td>
                      <td className="px-3 py-2">
                        <StatusBadge
                          status={u.role}
                          tone={u.role === 'admin' ? 'green' : 'gray'}
                        />
                      </td>
                      <td className="px-3 py-2 text-slate-600">{formatDateTime(u.created_at)}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{u.device_count}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{u.video_count}</td>
                      <td className="px-3 py-2 text-slate-600">{formatRelative(u.last_active_at)}</td>
                      <td className="px-3 py-2">
                        <div className="flex justify-end gap-1">
                          <select
                            value={u.role}
                            onChange={(e) =>
                              setPending({
                                kind: 'role',
                                user: u,
                                newRole: e.target.value as Role,
                              })
                            }
                            className="rounded border border-slate-300 bg-white px-2 py-1 text-xs"
                          >
                            <option value="user">user</option>
                            <option value="admin">admin</option>
                          </select>
                          <button
                            type="button"
                            onClick={() => setPending({ kind: 'reset', user: u })}
                            className="rounded border border-slate-300 bg-white px-2 py-1 text-xs hover:bg-slate-100"
                          >
                            Reset PW
                          </button>
                          <button
                            type="button"
                            onClick={() => setPending({ kind: 'delete', user: u })}
                            className="rounded border border-red-300 bg-white px-2 py-1 text-xs text-red-700 hover:bg-red-50"
                          >
                            Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          <Pagination total={total} limit={PAGE_SIZE} offset={offset} onChange={setOffset} />
        </div>
      </div>

      <ConfirmDialog
        open={pending !== null}
        busy={busy}
        danger={pending?.kind === 'delete'}
        title={
          pending?.kind === 'delete'
            ? 'ลบผู้ใช้?'
            : pending?.kind === 'reset'
              ? 'รีเซ็ตรหัสผ่าน?'
              : 'เปลี่ยน role?'
        }
        description={
          pending?.kind === 'delete' ? (
            <>
              จะลบ <b>{pending.user.email}</b> รวมถึง devices, videos, lives
              ทั้งหมดของผู้ใช้นี้ (cascade) — การกระทำนี้ย้อนกลับไม่ได้
            </>
          ) : pending?.kind === 'reset' ? (
            <>
              สร้าง temporary password ใหม่สำหรับ <b>{pending.user.email}</b> —
              รหัสจะแสดง 1 ครั้งเท่านั้น
            </>
          ) : pending ? (
            <>
              เปลี่ยน role ของ <b>{pending.user.email}</b> จาก{' '}
              <b>{pending.user.role}</b> เป็น <b>{pending.newRole}</b>
            </>
          ) : null
        }
        onConfirm={() => void confirmAction()}
        onCancel={() => (busy ? undefined : setPending(null))}
      />

      {tempPassword ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="mx-4 w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
            <h2 className="mb-2 text-lg font-semibold">Temporary password</h2>
            <p className="mb-3 text-sm text-slate-600">
              ส่งให้ <b>{tempPassword.email}</b> ผ่านช่องทางที่ปลอดภัย —
              จะไม่สามารถดูได้อีก
            </p>
            <pre className="rounded bg-slate-100 px-3 py-2 font-mono text-sm">
              {tempPassword.pwd}
            </pre>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => navigator.clipboard?.writeText(tempPassword.pwd)}
                className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100"
              >
                คัดลอก
              </button>
              <button
                type="button"
                onClick={() => setTempPassword(null)}
                className="rounded bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-blue-700"
              >
                ปิด
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  )
}

function pickSort(u: AdminUserRow, field: SortField): string | number | null {
  switch (field) {
    case 'email':
      return u.email
    case 'role':
      return u.role
    case 'created_at':
      return u.created_at
    case 'device_count':
      return u.device_count
    case 'video_count':
      return u.video_count
    case 'last_active_at':
      return u.last_active_at
  }
}
