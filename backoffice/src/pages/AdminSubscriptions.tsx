// /admin/subscriptions — read-only list of all Stripe subscriptions.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { adminApi } from '../api/admin'
import { ApiError } from '../api/client'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { SortableHeader, type SortState } from '../components/SortableHeader'
import { StatusBadge, toneForStatus } from '../components/StatusBadge'
import type { AdminSubscriptionRow, SubscriptionStatus } from '../types/api'
import { formatDateTime, formatNumber } from '../utils/format'

type SortField =
  | 'id'
  | 'owner_email'
  | 'tier'
  | 'status'
  | 'current_period_end'
  | 'updated_at'
type StatusFilter = '' | SubscriptionStatus

const PAGE_SIZE = 50

export function AdminSubscriptions() {
  const [rows, setRows] = useState<AdminSubscriptionRow[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(0)
  const [status, setStatus] = useState<StatusFilter>('')
  const [sort, setSort] = useState<SortState<SortField>>({
    field: 'updated_at',
    direction: 'desc',
  })
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [recheckingId, setRecheckingId] = useState<number | null>(null)

  useEffect(() => {
    setOffset(0)
  }, [status])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminApi.listSubscriptions({
        limit: PAGE_SIZE,
        offset,
        status: status || undefined,
      })
      setRows(res.items)
      setTotal(res.total)
      setErr(null)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลด subscriptions ไม่สำเร็จ')
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

  async function recheck(userID: number) {
    setRecheckingId(userID)
    setErr(null)
    try {
      const res = await adminApi.recheckSubscription(userID)
      // Patch the row in place
      setRows((current) =>
        current.map((r) => (r.user_id === userID ? { ...r, ...res.subscription } : r)),
      )
      if (!res.synced) {
        setErr('Stripe ไม่พบ subscription ของ user รายนี้')
      }
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'recheck ไม่สำเร็จ')
    } finally {
      setRecheckingId(null)
    }
  }

  return (
    <>
      <PageHeader
        title="Subscriptions"
        subtitle="Stripe-backed billing across all users"
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

      <div className="p-6">
        <div className="mb-3 flex items-center gap-3">
          <label className="text-xs text-slate-600">Status:</label>
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value as StatusFilter)}
            className="rounded border border-slate-300 bg-white px-2 py-1 text-sm"
          >
            <option value="">ทั้งหมด</option>
            <option value="active">active</option>
            <option value="pending">pending</option>
            <option value="past_due">past_due</option>
            <option value="canceled">canceled</option>
            <option value="incomplete">incomplete</option>
          </select>
          <span className="text-xs text-slate-500">{formatNumber(total)} subscriptions</span>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <div className="max-h-[70vh] overflow-auto">
            <table className="admin-table w-full border-collapse text-sm">
              <thead>
                <tr className="bg-slate-100">
                  <SortableHeader label="ID" field="id" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Owner" field="owner_email" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Tier" field="tier" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Status" field="status" sort={sort} onSort={toggleSort} />
                  <SortableHeader
                    label="Period end"
                    field="current_period_end"
                    sort={sort}
                    onSort={toggleSort}
                  />
                  <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                    Cancel @ end?
                  </th>
                  <SortableHeader label="Updated" field="updated_at" sort={sort} onSort={toggleSort} />
                  <th className="px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                    Stripe Sub ID
                  </th>
                  <th className="px-3 py-2 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {loading && rows.length === 0 ? (
                  <tr>
                    <td colSpan={9} className="px-3 py-6 text-center text-sm text-slate-500">
                      กำลังโหลด…
                    </td>
                  </tr>
                ) : sorted.length === 0 ? (
                  <tr>
                    <td colSpan={9} className="px-3 py-6 text-center text-sm text-slate-500">
                      ไม่พบ subscription
                    </td>
                  </tr>
                ) : (
                  sorted.map((s) => (
                    <tr key={s.id} className="border-t border-slate-100 hover:bg-slate-50">
                      <td className="px-3 py-2 text-slate-500">{s.id}</td>
                      <td className="px-3 py-2 font-medium text-slate-900">{s.owner_email}</td>
                      <td className="px-3 py-2 text-slate-700">{s.tier}</td>
                      <td className="px-3 py-2">
                        <StatusBadge status={s.status} tone={toneForStatus(s.status)} />
                      </td>
                      <td className="px-3 py-2 text-slate-600">
                        {formatDateTime(s.current_period_end)}
                      </td>
                      <td className="px-3 py-2 text-slate-600">
                        {s.cancel_at_period_end ? 'yes' : 'no'}
                      </td>
                      <td className="px-3 py-2 text-slate-600">{formatDateTime(s.updated_at)}</td>
                      <td className="px-3 py-2 text-slate-500">
                        <code className="font-mono text-xs">
                          {s.stripe_subscription_id ?? '—'}
                        </code>
                      </td>
                      <td className="px-3 py-2 text-right">
                        <div className="flex justify-end gap-2">
                          {s.status !== 'active' && s.status !== 'canceled' && (
                            <button
                              type="button"
                              onClick={() => void recheck(s.user_id)}
                              disabled={recheckingId === s.user_id}
                              className="rounded border border-blue-600 px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50 disabled:opacity-50"
                              title="ดึงสถานะล่าสุดจาก Stripe API"
                            >
                              {recheckingId === s.user_id ? 'กำลังตรวจ…' : 'Recheck'}
                            </button>
                          )}
                          <a
                            href={`/admin/users?q=${encodeURIComponent(s.owner_email)}`}
                            className="text-xs text-blue-600 hover:underline"
                          >
                            View user →
                          </a>
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
    </>
  )
}

function pickSort(s: AdminSubscriptionRow, field: SortField): string | number | null {
  switch (field) {
    case 'id':
      return s.id
    case 'owner_email':
      return s.owner_email
    case 'tier':
      return s.tier
    case 'status':
      return s.status
    case 'current_period_end':
      return s.current_period_end ?? null
    case 'updated_at':
      return s.updated_at
  }
}
