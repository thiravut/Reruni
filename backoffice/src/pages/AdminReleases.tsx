// /admin/releases — pick which APK file in downloadsDir is exposed at
// /api/downloads/manifest (and therefore at /setup-guide on the portal).
// Operators upload a new APK to the server's downloads dir, refresh this
// page, and select it as active. No redeploy needed.

import { useCallback, useEffect, useState } from 'react'
import { adminApi } from '../api/admin'
import { ApiError } from '../api/client'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import type { ReleaseInfo } from '../types/api'
import { formatBytes, formatDateTime } from '../utils/format'

export function AdminReleases() {
  const [items, setItems] = useState<ReleaseInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminApi.listReleases()
      setItems(res.items)
      setErr(null)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลดรายการ release ไม่สำเร็จ')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function pick(key: string, filename: string) {
    setSavingKey(key)
    setNotice(null)
    try {
      await adminApi.setActiveRelease(key, filename)
      setNotice(
        filename === ''
          ? 'ตั้งเป็นใช้ไฟล์ใหม่สุดอัตโนมัติแล้ว'
          : `ตั้งเป็น ${filename} แล้ว`,
      )
      await load()
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'บันทึกไม่สำเร็จ')
    } finally {
      setSavingKey(null)
    }
  }

  return (
    <>
      <PageHeader
        title="Releases"
        subtitle="เลือกเวอร์ชั่น APK ที่จะแสดงในหน้า /setup-guide"
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

      <div className="p-6 space-y-6">
        {notice && (
          <div className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800">
            {notice}
          </div>
        )}

        <p className="text-xs text-slate-500">
          อัปโหลดไฟล์ APK ใหม่ไปที่ <code className="rounded bg-slate-100 px-1">downloadsDir</code>{' '}
          ของเซิร์ฟเวอร์ (ดูใน systemd unit) แล้วรีเฟรช หน้านี้จะมีไฟล์ใหม่ขึ้นมาให้เลือก
        </p>

        {items.map((it) => (
          <ReleaseSection
            key={it.key}
            info={it}
            saving={savingKey === it.key}
            onPick={(filename) => pick(it.key, filename)}
          />
        ))}

        {!loading && items.length === 0 && (
          <div className="rounded border border-slate-200 bg-white p-6 text-center text-sm text-slate-500">
            ไม่มี release ที่ตั้งค่าได้
          </div>
        )}
      </div>
    </>
  )
}

function ReleaseSection({
  info,
  saving,
  onPick,
}: {
  info: ReleaseInfo
  saving: boolean
  onPick: (filename: string) => void
}) {
  const autoMode = !info.pinned
  return (
    <section className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      <div className="border-b border-slate-200 bg-slate-50 px-4 py-3">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-sm font-semibold text-slate-900">{info.label}</h2>
            <p className="text-xs text-slate-500">
              prefix: <code className="rounded bg-slate-100 px-1">{info.prefix}</code>
            </p>
          </div>
          <div className="text-right text-xs">
            <div className="text-slate-500">ใช้อยู่:</div>
            <div className="font-mono text-slate-800">{info.active || '—'}</div>
            {autoMode && info.active && (
              <div className="text-[10px] text-emerald-700">auto (ใหม่สุด)</div>
            )}
          </div>
        </div>
      </div>

      {info.files.length === 0 ? (
        <div className="px-4 py-6 text-center text-sm text-slate-500">
          ยังไม่มีไฟล์ตรง prefix นี้ในเซิร์ฟเวอร์
        </div>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-white text-left text-xs uppercase text-slate-500">
              <th className="w-10 px-3 py-2"></th>
              <th className="px-3 py-2">Filename</th>
              <th className="px-3 py-2">Version</th>
              <th className="px-3 py-2 text-right">Size</th>
              <th className="px-3 py-2">Uploaded</th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-t border-slate-100 hover:bg-slate-50">
              <td className="px-3 py-2">
                <input
                  type="radio"
                  name={`pick-${info.key}`}
                  checked={autoMode}
                  disabled={saving}
                  onChange={() => onPick('')}
                />
              </td>
              <td className="px-3 py-2 italic text-slate-600" colSpan={4}>
                ใช้ไฟล์ใหม่สุดอัตโนมัติ (highest semver)
              </td>
            </tr>
            {info.files.map((f) => {
              const active = f.filename === info.pinned
              return (
                <tr key={f.filename} className="border-t border-slate-100 hover:bg-slate-50">
                  <td className="px-3 py-2">
                    <input
                      type="radio"
                      name={`pick-${info.key}`}
                      checked={active}
                      disabled={saving}
                      onChange={() => onPick(f.filename)}
                    />
                  </td>
                  <td className="px-3 py-2 font-mono text-slate-800">{f.filename}</td>
                  <td className="px-3 py-2 tabular-nums">v{f.version}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{formatBytes(f.size_bytes)}</td>
                  <td className="px-3 py-2 text-slate-600">{formatDateTime(f.mod_time)}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}
