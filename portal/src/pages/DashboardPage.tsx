import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Spinner } from '../components/Spinner';
import { ErrorBanner } from '../components/ErrorBanner';
import { listDevices } from '../api/devices';
import { listVideos } from '../api/videos';
import { listActiveLives } from '../api/lives';
import { useAuth } from '../contexts/AuthContext';
import { useRealtime } from '../contexts/RealtimeContext';
import type { Device } from '../types/api';

interface Counts {
  devicesTotal: number;
  devicesOnline: number;
  devicesLive: number;
  videos: number;
  activeLives: number;
}

export function DashboardPage() {
  const { state } = useAuth();
  const realtime = useRealtime();
  const [counts, setCounts] = useState<Counts | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      const [devicesRes, videosRes, activeRes] = await Promise.all([
        listDevices(200, 0),
        listVideos(1, 0),
        listActiveLives(),
      ]);
      const devices = devicesRes.items;
      setCounts({
        devicesTotal: devicesRes.total ?? devices.length,
        devicesOnline: devices.filter((d) => d.status !== 'offline').length,
        devicesLive: devices.filter((d) => d.status === 'live').length,
        videos: videosRes.total ?? videosRes.items.length,
        activeLives: activeRes.items.length,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'โหลดข้อมูลไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  // refresh counts when realtime sees device or live changes
  useEffect(() => {
    const unsubA = realtime.onDeviceStatus(() => {
      load();
    });
    const unsubB = realtime.onLiveStarted(() => load());
    const unsubC = realtime.onLiveEnded(() => load());
    return () => {
      unsubA();
      unsubB();
      unsubC();
    };
  }, [realtime]);

  return (
    <div>
      <PageHeader
        title={`สวัสดี ${state.user?.email ?? ''}`}
        description="ภาพรวม fleet ของคุณ"
      />

      <ErrorBanner message={error} onDismiss={() => setError(null)} />

      {loading ? (
        <Spinner label="กำลังโหลด…" />
      ) : counts ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            label="อุปกรณ์ออนไลน์"
            value={`${counts.devicesOnline}/${counts.devicesTotal}`}
            href="/devices"
            tone="primary"
          />
          <StatCard
            label="กำลัง LIVE"
            value={String(counts.devicesLive)}
            href="/live/active"
            tone="live"
          />
          <StatCard
            label="วิดีโอในคลัง"
            value={String(counts.videos)}
            href="/videos"
          />
          <StatCard
            label="Live ที่กำลังออน"
            value={String(counts.activeLives)}
            href="/live/active"
          />
        </div>
      ) : null}

      <div className="mt-6 grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Card className="p-5">
          <h2 className="text-sm font-semibold text-slate-700 mb-3">
            ขั้นตอนแนะนำ
          </h2>
          <ol className="text-sm text-slate-600 space-y-2 list-decimal list-inside">
            <li>
              <Link to="/devices" className="text-brand-600 hover:underline">
                เชื่อมอุปกรณ์ Android เข้าระบบ
              </Link>
            </li>
            <li>
              <Link to="/videos" className="text-brand-600 hover:underline">
                อัปโหลดวิดีโอ broadcast
              </Link>
            </li>
            <li>
              <Link to="/live" className="text-brand-600 hover:underline">
                เริ่ม live หลายเครื่องพร้อมกัน
              </Link>
            </li>
          </ol>
        </Card>
        <Card className="p-5">
          <h2 className="text-sm font-semibold text-slate-700 mb-3">
            สถานะการเชื่อมต่อ
          </h2>
          <p className="text-sm text-slate-600">
            {realtime.connected
              ? 'เชื่อมต่อกับเซิร์ฟเวอร์แบบเรียลไทม์เรียบร้อย'
              : 'กำลังพยายามเชื่อมต่อกับเซิร์ฟเวอร์ จะ retry ให้อัตโนมัติ'}
          </p>
        </Card>
      </div>
    </div>
  );
}

interface StatCardProps {
  label: string;
  value: string;
  href?: string;
  tone?: 'primary' | 'live' | 'neutral';
}
function StatCard({ label, value, href, tone = 'neutral' }: StatCardProps) {
  const accent =
    tone === 'live'
      ? 'text-emerald-600'
      : tone === 'primary'
        ? 'text-brand-600'
        : 'text-slate-800';
  const body = (
    <Card className="p-5 hover:shadow transition-shadow">
      <p className="text-xs uppercase tracking-wider text-slate-500">{label}</p>
      <p className={`mt-2 text-3xl font-semibold ${accent}`}>{value}</p>
    </Card>
  );
  if (href) return <Link to={href}>{body}</Link>;
  return body;
}

// Re-export so AppLayout doesn't import unused.
export type _DeviceUnused = Device;
