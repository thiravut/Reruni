// /billing/success — Stripe redirects here after successful checkout.
// Poll /auth/me every 2s until subscription_status becomes 'active' (webhook
// must have arrived). Then redirect to /dashboard.

import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Card } from '../components/Card';
import { Spinner } from '../components/Spinner';
import { Button } from '../components/Button';
import { useAuth } from '../contexts/AuthContext';

const POLL_INTERVAL_MS = 2_000;
const POLL_TIMEOUT_MS = 60_000;

export function BillingSuccessPage() {
  const { state, refresh } = useAuth();
  const navigate = useNavigate();
  const [phase, setPhase] = useState<'waiting' | 'active' | 'timeout'>('waiting');

  useEffect(() => {
    let cancelled = false;
    const started = Date.now();
    let timer: number | undefined;

    async function tick() {
      if (cancelled) return;
      await refresh();
      // refresh() will update state; loop reads from current snapshot.
      timer = window.setTimeout(check, POLL_INTERVAL_MS);
    }
    function check() {
      if (cancelled) return;
      if (Date.now() - started > POLL_TIMEOUT_MS) {
        setPhase('timeout');
        return;
      }
      void tick();
    }
    void tick();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [refresh]);

  // When state.user becomes active → navigate.
  useEffect(() => {
    if (state.user?.subscription_status === 'active' && phase !== 'active') {
      setPhase('active');
      // Short delay so user sees the confirmation before navigation.
      const t = window.setTimeout(() => navigate('/dashboard', { replace: true }), 1200);
      return () => window.clearTimeout(t);
    }
  }, [state.user?.subscription_status, navigate, phase]);

  return (
    <div className="min-h-full bg-slate-50 flex items-center justify-center p-6">
      <Card className="max-w-md w-full p-8 text-center">
        {phase === 'waiting' && (
          <>
            <div className="mx-auto w-12 h-12 rounded-full bg-brand-50 flex items-center justify-center mb-4">
              <Spinner />
            </div>
            <h1 className="text-xl font-semibold text-slate-900">
              กำลังยืนยันการชำระเงิน…
            </h1>
            <p className="mt-2 text-sm text-slate-600">
              เรากำลังรอการยืนยันจาก Stripe — โดยปกติใช้เวลาไม่ถึง 10 วินาที
            </p>
          </>
        )}
        {phase === 'active' && (
          <>
            <div className="mx-auto w-12 h-12 rounded-full bg-emerald-50 flex items-center justify-center mb-4">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#10b981" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </div>
            <h1 className="text-xl font-semibold text-slate-900">
              ชำระเงินสำเร็จ
            </h1>
            <p className="mt-2 text-sm text-slate-600">
              กำลังพาไปยังหน้าหลัก…
            </p>
          </>
        )}
        {phase === 'timeout' && (
          <>
            <div className="mx-auto w-12 h-12 rounded-full bg-amber-50 flex items-center justify-center mb-4">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="13" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
            </div>
            <h1 className="text-xl font-semibold text-slate-900">
              ใช้เวลานานกว่าปกติ
            </h1>
            <p className="mt-2 text-sm text-slate-600">
              ลองรีเฟรชหน้านี้ หรือดูสถานะที่หน้าการชำระเงิน
            </p>
            <div className="mt-4 flex justify-center gap-2">
              <Link to="/billing">
                <Button>ดูสถานะ</Button>
              </Link>
              <Button
                variant="secondary"
                onClick={() => window.location.reload()}
              >
                รีเฟรช
              </Button>
            </div>
          </>
        )}
      </Card>
    </div>
  );
}
