// /admin/metrics — dashboard widgets pulled from GET /api/admin/metrics.
// Auto-refreshes every 30s per agent-task-backoffice.md Phase C bullet
// ("Auto-refresh metrics every 30s").

import { useCallback, useEffect, useRef, useState } from 'react'
import { adminApi } from '../api/admin'
import { ApiError } from '../api/client'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import type { AdminMetrics as Metrics } from '../types/api'
import {
  formatBytes,
  formatDateTime,
  formatHours,
  formatNumber,
  formatPercent,
} from '../utils/format'

const REFRESH_MS = 30_000

export function AdminMetrics() {
  const [data, setData] = useState<Metrics | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [lastFetched, setLastFetched] = useState<string | null>(null)
  const timerRef = useRef<number | null>(null)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const m = await adminApi.getMetrics()
      setData(m)
      setErr(null)
      setLastFetched(new Date().toISOString())
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'โหลด metrics ไม่สำเร็จ'
      setErr(msg)
    } finally {
      if (!silent) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
    timerRef.current = window.setInterval(() => void load(true), REFRESH_MS)
    return () => {
      if (timerRef.current !== null) window.clearInterval(timerRef.current)
    }
  }, [load])

  return (
    <>
      <PageHeader
        title="Metrics"
        subtitle="ภาพรวมระบบ — refresh ทุก 30 วินาที"
        actions={
          <>
            <span className="text-xs text-slate-500">
              {lastFetched ? `อัปเดต ${formatDateTime(lastFetched)}` : ''}
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

      <div className="grid gap-4 p-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <MetricCard
          label="Total users"
          value={formatNumber(data?.users_total)}
          sub={`active 7d: ${formatNumber(data?.users_active_7d)}`}
        />
        <MetricCard
          label="Devices total"
          value={formatNumber(data?.devices_total)}
        />
        <MetricCard
          label="Devices online"
          value={formatNumber(data?.devices_online)}
          tone="green"
        />
        <MetricCard
          label="Devices live"
          value={formatNumber(data?.devices_live)}
          tone="green"
        />
        <MetricCard
          label="Lives last 24h"
          value={formatNumber(data?.lives_24h)}
        />
        <MetricCard
          label="Broadcast hours 24h"
          value={formatHours(data?.broadcast_hours_24h)}
        />
        <MetricCard
          label="Disk used"
          value={formatBytes(data?.disk_used_bytes)}
        />
        <MetricCard
          label="Uptime 30d"
          value={formatPercent(data?.uptime_pct_30d)}
          tone={
            data && data.uptime_pct_30d < 99 ? 'yellow' : 'green'
          }
        />
      </div>
    </>
  )
}

interface CardProps {
  label: string
  value: string
  sub?: string
  tone?: 'green' | 'yellow' | 'red' | 'gray'
}

function MetricCard({ label, value, sub, tone }: CardProps) {
  const accent =
    tone === 'green'
      ? 'border-l-green-500'
      : tone === 'yellow'
        ? 'border-l-yellow-500'
        : tone === 'red'
          ? 'border-l-red-500'
          : 'border-l-blue-500'
  return (
    <div
      className={`rounded-lg border border-slate-200 border-l-4 bg-white p-4 shadow-sm ${accent}`}
    >
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">
        {label}
      </div>
      <div className="mt-1 text-2xl font-semibold text-slate-900">{value}</div>
      {sub ? <div className="mt-1 text-xs text-slate-500">{sub}</div> : null}
    </div>
  )
}
