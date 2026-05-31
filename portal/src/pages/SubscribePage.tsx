// /subscribe — quantity picker under flat per-device pricing.
// Active subscribers are bounced to /billing (Stripe Customer Portal is the
// only place to change quantity once subscribed). New / lapsed users pick a
// device count here and continue to Stripe Checkout.

import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { ErrorBanner } from '../components/ErrorBanner';
import { Spinner } from '../components/Spinner';
import { useAuth } from '../contexts/AuthContext';
import { ApiError } from '../api/client';
import * as billingApi from '../api/billing';
import type { Tier } from '../types/api';

const MIN_QTY = 1;
const MAX_QTY = 100;
const DEFAULT_QTY = 10;
const FALLBACK_PRICE_THB = 299;

export function SubscribePage() {
  const { state, logout } = useAuth();
  const nav = useNavigate();
  const [tier, setTier] = useState<Tier | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [qty, setQty] = useState(DEFAULT_QTY);
  const [busy, setBusy] = useState(false);

  // Already subscribed → manage via /billing (Stripe Customer Portal).
  // Without this redirect, clicking the CTA would 409 ALREADY_SUBSCRIBED.
  useEffect(() => {
    if (state.user?.subscription_status === 'active') {
      nav('/billing', { replace: true });
    }
  }, [state.user?.subscription_status, nav]);

  useEffect(() => {
    (async () => {
      try {
        const res = await billingApi.getTiers();
        setTier(res.tiers[0] ?? null);
      } catch (e) {
        setLoadErr(e instanceof ApiError ? e.message : 'โหลดราคาแพ็กเกจไม่สำเร็จ');
      }
    })();
  }, []);

  const pricePerDevice = tier?.price_thb ?? FALLBACK_PRICE_THB;
  const monthly = qty * pricePerDevice;

  async function continueToCheckout() {
    setActionErr(null);
    setBusy(true);
    try {
      const res = await billingApi.createCheckoutSession('device', qty);
      window.location.assign(res.checkout_url);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === 'ALREADY_SUBSCRIBED') {
          // Defensive: subscription_status flipped between mount and click.
          nav('/billing', { replace: true });
          return;
        }
        setActionErr(e.message);
      } else {
        setActionErr('เริ่ม Checkout ไม่สำเร็จ');
      }
      setBusy(false);
    }
  }

  return (
    <div className="min-h-full bg-slate-50 p-6 sm:p-10">
      <div className="mx-auto max-w-3xl">
        <PageHeader
          title="เลือกจำนวนเครื่อง"
          description={`สวัสดี ${state.user?.email ?? ''} — ${pricePerDevice.toLocaleString()} บาท/เครื่อง/เดือน`}
          actions={
            <button
              type="button"
              onClick={() => void logout()}
              className="text-sm text-slate-500 hover:text-slate-700"
            >
              ออกจากระบบ
            </button>
          }
        />

        <ErrorBanner message={loadErr} onDismiss={() => setLoadErr(null)} />
        <ErrorBanner message={actionErr} onDismiss={() => setActionErr(null)} />

        {tier === null && loadErr === null ? (
          <Spinner label="กำลังโหลดราคา…" />
        ) : tier === null ? (
          <Card className="p-6 text-center text-sm text-slate-600">
            ยังไม่ได้กำหนดราคาแพ็กเกจในระบบ — กรุณาติดต่อทีมงาน
          </Card>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mt-4">
            <Card className="p-6 md:col-span-2 space-y-6">
              <div className="space-y-2">
                <label htmlFor="qty" className="text-sm font-medium text-slate-700">
                  จำนวน device ({MIN_QTY}-{MAX_QTY})
                </label>
                <input
                  id="qty"
                  type="number"
                  min={MIN_QTY}
                  max={MAX_QTY}
                  value={qty}
                  onChange={(e) => {
                    const v = Math.max(
                      MIN_QTY,
                      Math.min(MAX_QTY, parseInt(e.target.value, 10) || MIN_QTY),
                    );
                    setQty(v);
                  }}
                  className="w-full rounded-md border border-slate-300 px-3 py-2 text-lg"
                />
              </div>

              <div className="rounded-lg bg-slate-100 p-4">
                <div className="flex items-center justify-between text-slate-700">
                  <span>
                    {qty} × {pricePerDevice.toLocaleString()}
                  </span>
                  <span className="text-2xl font-semibold">
                    {monthly.toLocaleString()} บาท/เดือน
                  </span>
                </div>
                <p className="mt-2 text-xs text-slate-500">
                  ปรับเพิ่ม-ลดได้ทุกเมื่อ — เพิ่ม device กลางรอบจะ prorate, ลด device จะมีผลรอบถัดไป
                </p>
              </div>

              <ul className="space-y-1 text-sm text-slate-600">
                <li>• คุม Devices ได้พร้อมกัน {qty} เครื่อง</li>
                <li>• Smart Overlay broadcast</li>
                <li>• Dynamic Banner real-time</li>
                <li>• Web dashboard + mobile-responsive</li>
              </ul>

              <Button
                block
                size="lg"
                loading={busy}
                onClick={() => void continueToCheckout()}
              >
                ไปชำระเงิน →
              </Button>
            </Card>

            <EnterpriseCard />
          </div>
        )}

        <p className="mt-8 text-center text-xs text-slate-500">
          มีคำถาม?{' '}
          <Link to="/billing" className="text-brand-600 hover:underline">
            ไปที่หน้าการชำระเงิน
          </Link>
        </p>
      </div>
    </div>
  );
}

function EnterpriseCard() {
  return (
    <Card className="p-6 flex flex-col bg-slate-900 text-white">
      <h3 className="text-lg font-semibold">Enterprise</h3>
      <p className="mt-1 text-sm text-slate-300">100+ เครื่อง — ปรับตามต้องการ</p>
      <div className="mt-4 flex items-baseline gap-1">
        <span className="text-3xl font-bold">ติดต่อเรา</span>
      </div>
      <ul className="mt-4 space-y-1 text-sm text-slate-300 flex-1">
        <li>• ราคาเริ่ม 120-140 บาท/เครื่อง</li>
        <li>• ระดับ SLA สูงขึ้น</li>
        <li>• Onboarding รายบุคคล</li>
        <li>• Compliance พิเศษ</li>
      </ul>
      <a
        href="mailto:sales@tiktokrerun.local?subject=Enterprise%20plan"
        className="mt-5 inline-flex justify-center rounded bg-white text-slate-900 px-5 py-2.5 text-base font-medium hover:bg-slate-100"
      >
        ติดต่อทีมขาย
      </a>
    </Card>
  );
}
