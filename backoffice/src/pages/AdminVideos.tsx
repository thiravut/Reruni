// /admin/videos — disk-usage view. Defaults to sort-by-size desc so admins
// can immediately see what's eating storage.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { adminApi } from '../api/admin'
import { ApiError } from '../api/client'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { SortableHeader, type SortState } from '../components/SortableHeader'
import type { AdminVideoRow } from '../types/api'
import {
  formatBytes,
  formatDateTime,
  formatNumber,
} from '../utils/format'

type SortField = 'id' | 'filename' | 'owner_email' | 'size_bytes' | 'uploaded_at'

const PAGE_SIZE = 50

export function AdminVideos() {
  const [rows, setRows] = useState<AdminVideoRow[]>([])
  const [total, setTotal] = useState(0)
  const [totalDisk, setTotalDisk] = useState<number>(0)
  const [offset, setOffset] = useState(0)
  const [sort, setSort] = useState<SortState<SortField>>({
    field: 'size_bytes',
    direction: 'desc',
  })
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminApi.listVideos({ limit: PAGE_SIZE, offset })
      setRows(res.items)
      setTotal(res.total)
      setTotalDisk(res.total_disk_bytes ?? 0)
      setErr(null)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลด videos ไม่สำเร็จ')
    } finally {
      setLoading(false)
    }
  }, [offset])

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

  return (
    <>
      <PageHeader
        title="Videos"
        subtitle="พื้นที่ดิสก์ทั้งระบบ"
        actions={
          <>
            <span className="text-xs text-slate-500">
              รวมทั้งหมด: <b className="text-slate-700">{formatBytes(totalDisk)}</b>
            </span>
            <button
              type="button"
              onClick={() => void load()}
              disabled={loading}
              className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-100 disabled:opacity-50"
            >
              {loading ? 'กำลังโหลด…' : 'รีเฟรช'}
            </button>
          </>
        }
      />
      <ErrorBanner message={err} onDismiss={() => setErr(null)} />

      <div className="p-6">
        <div className="mb-3 flex items-center gap-3">
          <span className="text-xs text-slate-500">
            {formatNumber(total)} videos
          </span>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <div className="max-h-[70vh] overflow-auto">
            <table className="admin-table w-full border-collapse text-sm">
              <thead>
                <tr className="bg-slate-100">
                  <SortableHeader label="ID" field="id" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Filename" field="filename" sort={sort} onSort={toggleSort} />
                  <SortableHeader label="Owner" field="owner_email" sort={sort} onSort={toggleSort} />
                  <SortableHeader
                    label="Size"
                    field="size_bytes"
                    sort={sort}
                    onSort={toggleSort}
                    align="right"
                  />
                  <SortableHeader label="Uploaded" field="uploaded_at" sort={sort} onSort={toggleSort} />
                </tr>
              </thead>
              <tbody>
                {loading && rows.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="px-3 py-6 text-center text-sm text-slate-500">
                      กำลังโหลด…
                    </td>
                  </tr>
                ) : sorted.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="px-3 py-6 text-center text-sm text-slate-500">
                      ไม่พบไฟล์วิดีโอ
                    </td>
                  </tr>
                ) : (
                  sorted.map((v) => (
                    <tr key={v.id} className="border-t border-slate-100 hover:bg-slate-50">
                      <td className="px-3 py-2 text-slate-500">{v.id}</td>
                      <td className="px-3 py-2 font-medium text-slate-900">{v.filename}</td>
                      <td className="px-3 py-2 text-slate-700">{v.owner_email}</td>
                      <td className="px-3 py-2 text-right tabular-nums">
                        {formatBytes(v.size_bytes)}
                      </td>
                      <td className="px-3 py-2 text-slate-600">{formatDateTime(v.uploaded_at)}</td>
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

function pickSort(v: AdminVideoRow, field: SortField): string | number | null {
  switch (field) {
    case 'id':
      return v.id
    case 'filename':
      return v.filename
    case 'owner_email':
      return v.owner_email
    case 'size_bytes':
      return v.size_bytes
    case 'uploaded_at':
      return v.uploaded_at
  }
}
