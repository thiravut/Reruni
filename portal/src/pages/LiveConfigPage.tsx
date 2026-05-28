import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { TextField, TextArea, FieldWrapper } from '../components/Field';
import { ErrorBanner } from '../components/ErrorBanner';
import { Spinner } from '../components/Spinner';
import { StatusBadge } from '../components/StatusBadge';
import { ApiError } from '../api/client';
import { listDevices } from '../api/devices';
import { listVideos } from '../api/videos';
import { startLive } from '../api/lives';
import { useToast } from '../contexts/ToastContext';
import { useRealtime } from '../contexts/RealtimeContext';
import type { Device, Video } from '../types/api';
import {
  validateHashtags,
  validateLiveCaption,
  validateLiveTitle,
} from '../utils/validation';

export function LiveConfigPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const realtime = useRealtime();

  const [devices, setDevices] = useState<Device[]>([]);
  const [videos, setVideos] = useState<Video[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [selectedDeviceIds, setSelectedDeviceIds] = useState<Set<number>>(new Set());
  // Ordered list so the server's round-robin assignment is predictable from
  // the operator's click sequence — first clicked → device 0, etc.
  const [selectedVideoIds, setSelectedVideoIds] = useState<number[]>([]);
  const [title, setTitle] = useState('');
  const [caption, setCaption] = useState('');
  const [hashtagInput, setHashtagInput] = useState('');
  const [pinnedSku, setPinnedSku] = useState('');

  const [titleErr, setTitleErr] = useState<string | null>(null);
  const [captionErr, setCaptionErr] = useState<string | null>(null);
  const [hashtagErr, setHashtagErr] = useState<string | null>(null);
  const [videoErr, setVideoErr] = useState<string | null>(null);
  const [devicesErr, setDevicesErr] = useState<string | null>(null);

  const [submitting, setSubmitting] = useState(false);
  const [formErr, setFormErr] = useState<string | null>(null);

  useEffect(() => {
    async function load() {
      try {
        const [d, v] = await Promise.all([listDevices(200), listVideos(200)]);
        setDevices(d.items);
        setVideos(v.items);
      } catch (e) {
        setLoadError(e instanceof Error ? e.message : 'โหลดข้อมูลไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, []);

  // realtime status patch
  const mergedDevices = useMemo(() => {
    return devices.map((d) => {
      const patch = realtime.deviceStatuses.get(d.id);
      if (!patch) return d;
      return { ...d, status: patch.status, last_seen_at: patch.last_seen_at };
    });
  }, [devices, realtime.deviceStatuses]);

  const availableDevices = useMemo(
    () => mergedDevices.filter((d) => d.status !== 'offline'),
    [mergedDevices],
  );

  function toggleDevice(id: number) {
    setSelectedDeviceIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setDevicesErr(null);
  }

  function selectAll() {
    setSelectedDeviceIds(new Set(availableDevices.filter((d) => d.status === 'idle').map((d) => d.id)));
  }

  function clearAll() {
    setSelectedDeviceIds(new Set());
  }

  function parseHashtags(input: string): string[] {
    return input
      .split(/[,\s]+/)
      .map((t) => t.trim().replace(/^#/, ''))
      .filter(Boolean);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormErr(null);

    const tErr = validateLiveTitle(title.trim());
    const cErr = validateLiveCaption(caption);
    const hashtags = parseHashtags(hashtagInput);
    const hErr = validateHashtags(hashtags);
    const vErr = selectedVideoIds.length === 0 ? 'กรุณาเลือกวิดีโออย่างน้อย 1 รายการ' : null;
    const dErr = selectedDeviceIds.size === 0 ? 'กรุณาเลือกอุปกรณ์อย่างน้อย 1 เครื่อง' : null;

    setTitleErr(tErr);
    setCaptionErr(cErr);
    setHashtagErr(hErr);
    setVideoErr(vErr);
    setDevicesErr(dErr);
    if (tErr || cErr || hErr || vErr || dErr) return;

    setSubmitting(true);
    try {
      const res = await startLive({
        device_ids: [...selectedDeviceIds],
        video_ids: selectedVideoIds,
        title: title.trim(),
        caption: caption.trim() || undefined,
        hashtags: hashtags.length ? hashtags : undefined,
        pinned_sku: pinnedSku.trim() || undefined,
      });
      toast.success(
        `ส่งคำสั่งเริ่ม live แล้ว ${res.commands.length} เครื่อง`,
      );
      navigate('/live/active');
    } catch (err) {
      setFormErr(err instanceof ApiError ? err.message : 'เริ่ม live ไม่สำเร็จ');
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <Spinner label="กำลังโหลด…" />;

  return (
    <div>
      <PageHeader
        title="เริ่ม Live"
        description="เลือกอุปกรณ์และวิดีโอเพื่อเริ่ม broadcast พร้อมกัน"
      />

      <ErrorBanner message={loadError} onDismiss={() => setLoadError(null)} />

      <form onSubmit={handleSubmit} className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Devices selector */}
        <Card className="lg:col-span-2 p-5">
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-semibold text-slate-800">เลือกอุปกรณ์</h2>
            <div className="flex gap-2 text-xs">
              <button
                type="button"
                onClick={selectAll}
                className="text-brand-600 hover:underline"
              >
                เลือกทั้งหมดที่พร้อม
              </button>
              <button
                type="button"
                onClick={clearAll}
                className="text-slate-500 hover:underline"
              >
                ล้างการเลือก
              </button>
            </div>
          </div>
          {devicesErr && (
            <p className="text-xs text-rose-600 mb-2">{devicesErr}</p>
          )}
          {mergedDevices.length === 0 ? (
            <p className="text-sm text-slate-500">
              ยังไม่มีอุปกรณ์ — กรุณาเชื่อม device ก่อน
            </p>
          ) : (
            <ul className="divide-y divide-slate-100">
              {mergedDevices.map((d) => {
                const offline = d.status === 'offline';
                const live = d.status === 'live';
                const checked = selectedDeviceIds.has(d.id);
                return (
                  <li
                    key={d.id}
                    className={`py-2 flex items-center gap-3 ${
                      offline ? 'opacity-60' : ''
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleDevice(d.id)}
                      disabled={offline}
                      className="w-4 h-4"
                    />
                    <div className="flex-1">
                      <div className="font-medium text-slate-800">
                        {d.name || `อุปกรณ์ #${d.id}`}
                      </div>
                      <div className="text-xs text-slate-500">ID: {d.id}</div>
                    </div>
                    <StatusBadge status={d.status} />
                    {live && (
                      <span className="text-xs text-amber-600">
                        กำลัง live อยู่
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          <p className="text-xs text-slate-500 mt-3">
            เลือกแล้ว {selectedDeviceIds.size} เครื่อง
          </p>
        </Card>

        {/* Config form */}
        <Card className="p-5 flex flex-col gap-4">
          <h2 className="font-semibold text-slate-800">ตั้งค่า Live</h2>
          <ErrorBanner message={formErr} onDismiss={() => setFormErr(null)} />

          <FieldWrapper
            label="วิดีโอ broadcast"
            error={videoErr}
            required
            hint="เลือกได้มากกว่า 1 — server จะแจกจ่ายให้แต่ละ device แบบ round-robin"
          >
            {videos.length === 0 ? (
              <p className="text-sm text-slate-500 py-2">
                ยังไม่มีวิดีโอ — กรุณาอัพโหลดที่หน้าคลังวิดีโอก่อน
              </p>
            ) : (
              <ul className="divide-y divide-slate-100 border border-slate-200 rounded max-h-56 overflow-y-auto">
                {videos.map((v) => {
                  const idx = selectedVideoIds.indexOf(v.id);
                  const checked = idx !== -1;
                  return (
                    <li
                      key={v.id}
                      className="py-2 px-3 flex items-center gap-3"
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => {
                          setSelectedVideoIds((prev) =>
                            checked
                              ? prev.filter((id) => id !== v.id)
                              : [...prev, v.id],
                          );
                          setVideoErr(null);
                        }}
                        className="w-4 h-4"
                      />
                      <div className="flex-1 min-w-0">
                        <div className="font-medium text-slate-800 truncate">
                          {v.name || v.filename}
                        </div>
                        <div className="text-xs text-slate-500 truncate">
                          {v.filename}
                        </div>
                      </div>
                      {checked && (
                        <span className="text-xs text-brand-600 font-mono">
                          #{idx + 1}
                        </span>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
            <p className="text-xs text-slate-500 mt-2">
              เลือกแล้ว {selectedVideoIds.length} รายการ
            </p>
          </FieldWrapper>

          <TextField
            label="ชื่อ Live"
            value={title}
            onChange={(e) => {
              setTitle(e.target.value);
              setTitleErr(null);
            }}
            maxLength={100}
            error={titleErr}
            hint="แสดงบนสุดของหน้า live"
            required
          />

          <TextArea
            label="คำบรรยาย"
            value={caption}
            onChange={(e) => {
              setCaption(e.target.value);
              setCaptionErr(null);
            }}
            maxLength={500}
            error={captionErr}
            hint={`${caption.length}/500`}
          />

          <TextField
            label="แฮชแท็ก"
            value={hashtagInput}
            onChange={(e) => {
              setHashtagInput(e.target.value);
              setHashtagErr(null);
            }}
            error={hashtagErr}
            placeholder="flashsale, ส่งฟรี"
            hint="คั่นด้วย comma หรือเว้นวรรค ไม่เกิน 10 แท็ก"
          />

          <TextField
            label="SKU ที่ปัก (ตัวเลือก)"
            value={pinnedSku}
            onChange={(e) => setPinnedSku(e.target.value)}
            placeholder="SKU-2024"
          />

          <Button type="submit" loading={submitting} size="lg" block>
            เริ่ม Live ทันที
          </Button>
          <p className="text-xs text-slate-500">
            ระบบจะส่งคำสั่งให้ทุกเครื่องที่เลือกพร้อมกัน
          </p>
        </Card>
      </form>
    </div>
  );
}
