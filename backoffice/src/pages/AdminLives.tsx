// /admin/lives — active + recent live sessions with force-stop action.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { adminApi } from '../api/admin'
import { ApiError } from '../api/client'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { SortableHeader, type SortState } from '../components/SortableHeader'
import { StatusBadge } from '../components/StatusBadge'
import type { AdminLiveRow } from '../types/api'
import { formatDateTime, formatNumber } from '../utils/format'

type SortField = 'id' | 'started_at' | 'ended_at' | 'owner_email' | 'device_id'
type StatusFilter = 'active' | 'ended' | ''

const PAGE_SIZE = 50

export function AdminLives() {
  const [rows, setRows] = useState<AdminLiveRow[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(0)
  const [status, setStatus] = useState<StatusFilter>('active')
  const [sort, setSort] = useState<SortState<SortField>>({
    field: 'started_at',
    direction: 'desc',
  })
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [pending, setPending] = useState<AdminLiveRow | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setOffset(0)
  }, [status])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminApi.listLives({
        limit: PAGE_SIZE,
        offset,
        status: (status || undefined) as 'active' | 'ended' | undefined,
      })
      setRows(res.items)
      setTotal(res.total)
      setErr(null)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลด lives ไม่สำเร็จ')
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

  async function confirmForceStop() {
    if (!pending) return
    setBusy(true)
    try {
      await adminApi.forceStopLive(pending.id)
      setInfo(`Force-stop live #${pending.id} เรียบร้อย`)
      setPending(null)
      await load()
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'Force-stop ล้มเหลว')
    } finally {
      setBusy(false)
    }
  }

  function liveStatusOf(row: AdminLiveRow): string {
    if (row.status) return row.status
    return row.ended_at ? 'ended' : 'active'
  }

  return (
    <>
      <PageHeader
        title="Live sessions"
        subtitle="Live ที่กำลังออกอากาศ + ประวัติ"
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
            onChange={(e) => setStatus(e.target.value as StatusFilter)}
            className="rounded border border-slate-300 bg-white px-2 py-1 text-sm"
          >
            <option value="active">active</option>
            <option value="ended">ended (recent)</option>
            <option value="">ทั้งหมด</option>
          </select>
          <span className="text-xs text-slate-500">{formatNumber(total)} sessions</span>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <div className="max-h-[70vh] overflow-auto">
            <table className="admin-table w-full border-collapse text-sm">
              <thead>
                <tr className="bg-slate-100">
                  <SortableHeader label="ID" field="id" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Device" field="device_id" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Owner" field="owner_email" sort={sort} onSort={toggleSort} />
                  <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                    Title
                  </th>
                  <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                    Status
                  </th>
                  <SortableHeader label="Started" field="started_at" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Ended" field="ended_at" sort={sort} onSort={toggleSort} />
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
                      ไม่พบ live session
                    </td>
                  </tr>
                ) : (
                  sorted.map((l) => {
                    const st = liveStatusOf(l)
                    return (
                      <tr key={l.id} className="border-t border-slate-100 hover:bg-slate-50">
                        <td className="px-3 py-2 text-slate-500">{l.id}</td>
                        <td className="px-3 py-2 text-slate-700">
                          {l.device_name ? `${l.device_name} (#${l.device_id})` : `#${l.device_id}`}
                        </td>
                        <td className="px-3 py-2 text-slate-700">{l.owner_email}</td>
                        <td className="px-3 py-2 text-slate-700">{l.title || '—'}</td>
                        <td className="px-3 py-2">
                          <StatusBadge status={st} />
                        </td>
                        <td className="px-3 py-2 text-slate-600">{formatDateTime(l.started_at)}</td>
                        <td className="px-3 py-2 text-slate-600">{formatDateTime(l.ended_at)}</td>
                        <td className="px-3 py-2 text-right">
                          {st === 'active' ? (
                            <button
                              type="button"
                              onClick={() => setPending(l)}
                              className="rounded border border-red-300 bg-white px-2 py-1 text-xs text-red-700 hover:bg-red-50"
                            >
                              Force stop
                            </button>
                          ) : (
                            <span className="text-xs text-slate-400">—</span>
                          )}
                        </td>
                      </tr>
                    )
                  })
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
        title="Force-stop live session?"
        description={
          pending ? (
            <>
              จะหยุด live session <b>#{pending.id}</b> ของ <b>{pending.owner_email}</b>{' '}
              (device #{pending.device_id}) ทันที
            </>
          ) : null
        }
        confirmLabel="Force stop"
        onConfirm={() => void confirmForceStop()}
        onCancel={() => (busy ? undefined : setPending(null))}
      />
    </>
  )
}

function pickSort(l: AdminLiveRow, field: SortField): string | number | null {
  switch (field) {
    case 'id':
      return l.id
    case 'started_at':
      return l.started_at
    case 'ended_at':
      return l.ended_at
    case 'owner_email':
      return l.owner_email
    case 'device_id':
      return l.device_id
  }
}
