// /admin/devices — every device across every user. Status filter +
// force-disconnect action (confirmed via dialog).

import { useCallback, useEffect, useMemo, useState } from 'react'
import { adminApi } from '../api/admin'
import { ApiError } from '../api/client'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { SortableHeader, type SortState } from '../components/SortableHeader'
import { StatusBadge } from '../components/StatusBadge'
import type { AdminDeviceRow } from '../types/api'
import { formatNumber, formatRelative } from '../utils/format'

type SortField = 'id' | 'name' | 'owner_email' | 'status' | 'last_seen_at'

const PAGE_SIZE = 50

const STATUS_OPTIONS = [
  { value: '', label: 'ทุกสถานะ' },
  { value: 'online', label: 'online' },
  { value: 'offline', label: 'offline' },
  { value: 'live', label: 'live' },
  { value: 'idle', label: 'idle' },
]

export function AdminDevices() {
  const [rows, setRows] = useState<AdminDeviceRow[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(0)
  const [status, setStatus] = useState('')
  const [sort, setSort] = useState<SortState<SortField>>({
    field: 'last_seen_at',
    direction: 'desc',
  })
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [pending, setPending] = useState<AdminDeviceRow | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setOffset(0)
  }, [status])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminApi.listDevices({
        limit: PAGE_SIZE,
        offset,
        status: status || undefined,
      })
      setRows(res.items)
      setTotal(res.total)
      setErr(null)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลด devices ไม่สำเร็จ')
    } finally {
      setLoading(false)
    }
  }, [offset, status])

  useEffect(() => {
    void load()
  }, [load])

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

  async function confirmDisconnect() {
    if (!pending) return
    setBusy(true)
    try {
      await adminApi.disconnectDevice(pending.id)
      setInfo(`สั่ง disconnect device #${pending.id} แล้ว`)
      setPending(null)
      await load()
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'Force-disconnect ล้มเหลว')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        title="Devices"
        subtitle="อุปกรณ์ทั้งหมดในระบบ (ทุกผู้ใช้)"
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
          <label className="text-xs text-slate-600">Status:</label>
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            className="rounded border border-slate-300 bg-white px-2 py-1 text-sm"
          >
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <span className="text-xs text-slate-500">
            {formatNumber(total)} devices
          </span>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <div className="max-h-[70vh] overflow-auto">
            <table className="admin-table w-full border-collapse text-sm">
              <thead>
                <tr className="bg-slate-100">
                  <SortableHeader label="ID" field="id" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Name" field="name" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Owner" field="owner_email" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Status" field="status" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Last seen" field="last_seen_at" sort={sort} onSort={toggleSort} />
                  <th className="px-3 py-2 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {loading && rows.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-3 py-6 text-center text-sm text-slate-500">
                      กำลังโหลด…
                    </td>
                  </tr>
                ) : sorted.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-3 py-6 text-center text-sm text-slate-500">
                      ไม่พบ device
                    </td>
                  </tr>
                ) : (
                  sorted.map((d) => (
                    <tr key={d.id} className="border-t border-slate-100 hover:bg-slate-50">
                      <td className="px-3 py-2 text-slate-500">{d.id}</td>
                      <td className="px-3 py-2 font-medium text-slate-900">{d.name || '(unnamed)'}</td>
                      <td className="px-3 py-2 text-slate-700">{d.owner_email}</td>
                      <td className="px-3 py-2">
                        <StatusBadge status={d.status} />
                      </td>
                      <td className="px-3 py-2 text-slate-600">{formatRelative(d.last_seen_at)}</td>
                      <td className="px-3 py-2 text-right">
                        <button
                          type="button"
                          onClick={() => setPending(d)}
                          className="rounded border border-red-300 bg-white px-2 py-1 text-xs text-red-700 hover:bg-red-50"
                        >
                          Force disconnect
                        </button>
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
        danger
        title="Force-disconnect device?"
        description={
          pending ? (
            <>
              จะตัด WebSocket ของ device <b>{pending.name || `#${pending.id}`}</b> (owner{' '}
              <b>{pending.owner_email}</b>) — ถ้ากำลัง live อยู่ stream อาจหยุด
            </>
          ) : null
        }
        confirmLabel="Disconnect"
        onConfirm={() => void confirmDisconnect()}
        onCancel={() => (busy ? undefined : setPending(null))}
      />
    </>
  )
}

function pickSort(d: AdminDeviceRow, field: SortField): string | number | null {
  switch (field) {
    case 'id':
      return d.id
    case 'name':
      return d.name ?? ''
    case 'owner_email':
      return d.owner_email
    case 'status':
      return d.status
    case 'last_seen_at':
      return d.last_seen_at
  }
}
