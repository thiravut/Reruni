// /onboarding — first-time wizard for flat per-device pricing.
// See docs/planning-artifacts/prd-v1-launch.md §3.12.
//
// One page renders one of seven sub-step components based on /api/onboarding.
// Steps `welcome`, `pick_quantity`, and `payment` are NOT skippable;
// `install_apk`, `pair_device`, and `first_video` are.

import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { ErrorBanner } from '../components/ErrorBanner';
import { Spinner } from '../components/Spinner';
import { ApiError } from '../api/client';
import * as onboardingApi from '../api/onboarding';
import * as billingApi from '../api/billing';
import type { OnboardingState, OnboardingStep } from '../api/onboarding';

const PER_DEVICE_PRICE_THB = 299;
const MIN_QTY = 1;
const MAX_QTY = 100;
const DEFAULT_QTY = 10;

export function OnboardingPage() {
  const nav = useNavigate();
  const [state, setState] = useState<OnboardingState | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setState(await onboardingApi.getOnboarding());
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลด onboarding ไม่สำเร็จ');
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (state?.step === 'complete') {
      nav('/dashboard', { replace: true });
    }
  }, [state?.step, nav]);

  // Poll while waiting on async signals (Stripe webhook, first device online).
  useEffect(() => {
    if (!state) return;
    if (state.step !== 'payment' && state.step !== 'pair_device') return;
    const t = setInterval(() => void refresh(), 3000);
    return () => clearInterval(t);
  }, [state, refresh]);

  if (state === null) {
    return (
      <div className="min-h-full bg-slate-50 p-6">
        <Spinner label="กำลังโหลด…" />
      </div>
    );
  }

  return (
    <div className="min-h-full bg-slate-50 p-6 sm:p-10">
      <div className="mx-auto max-w-3xl space-y-6">
        <PageHeader title="เริ่มต้นใช้งาน Reruni" description={progressLabel(state.step)} />
        <ErrorBanner message={err} onDismiss={() => setErr(null)} />
        <StepBody state={state} onChange={refresh} onError={setErr} />
      </div>
    </div>
  );
}

function progressLabel(step: OnboardingStep): string {
  const order: OnboardingStep[] = [
    'welcome',
    'pick_quantity',
    'payment',
    'install_apk',
    'pair_device',
    'first_video',
    'complete',
  ];
  const idx = order.indexOf(step);
  return `ขั้นตอน ${idx + 1} จาก ${order.length - 1}`;
}

interface StepProps {
  state: OnboardingState;
  onChange: () => Promise<void>;
  onError: (m: string | null) => void;
}

function StepBody({ state, onChange, onError }: StepProps) {
  switch (state.step) {
    case 'welcome':
      return <WelcomeStep onChange={onChange} onError={onError} />;
    case 'pick_quantity':
      return <PickQuantityStep onChange={onChange} onError={onError} />;
    case 'payment':
      return <PaymentWaitingStep />;
    case 'install_apk':
      return <InstallAPKStep state={state} onChange={onChange} onError={onError} />;
    case 'pair_device':
      return <PairDeviceStep state={state} onChange={onChange} onError={onError} />;
    case 'first_video':
      return <FirstVideoStep onChange={onChange} onError={onError} />;
    case 'complete':
      return <Spinner label="กำลังพาไป Dashboard…" />;
  }
}

// -----------------------------------------------------------------------------
// Step 2: Welcome — quick value prop (per decision 2026-05-23)
// -----------------------------------------------------------------------------

function WelcomeStep({ onChange, onError }: Omit<StepProps, 'state'>) {
  const [busy, setBusy] = useState(false);
  async function next() {
    onError(null);
    setBusy(true);
    try {
      await onboardingApi.advanceOnboarding('pick_quantity');
      await onChange();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'ทำต่อไม่สำเร็จ');
      setBusy(false);
    }
  }
  return (
    <Card className="p-8 space-y-6">
      <h2 className="text-2xl font-semibold text-slate-900">ยินดีต้อนรับสู่ Reruni 👋</h2>
      <ul className="space-y-3 text-slate-700">
        <li>✓ Live ขายของแบบ autopilot 24/7 จาก Android phones</li>
        <li>✓ ปักตะกร้าสินค้า + เปลี่ยน banner ระหว่าง live ได้จากเว็บ</li>
        <li>✓ คุมหลายเครื่องพร้อมกัน — operator คนเดียวจัดการได้</li>
      </ul>
      <Button type="button" disabled={busy} onClick={() => void next()}>
        เริ่มต้น →
      </Button>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// Step 3: Pick device count — flat 299 บาท/device
// -----------------------------------------------------------------------------

function PickQuantityStep({ onChange, onError }: Omit<StepProps, 'state'>) {
  const [qty, setQty] = useState(DEFAULT_QTY);
  const [busy, setBusy] = useState(false);

  const monthly = qty * PER_DEVICE_PRICE_THB;

  async function continueToCheckout() {
    onError(null);
    setBusy(true);
    try {
      const { checkout_url } = await billingApi.createCheckoutSession('device', qty);
      // Stripe will redirect back; until then our row sits in pick_quantity.
      window.location.assign(checkout_url);
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'เริ่ม checkout ไม่สำเร็จ');
      setBusy(false);
    } finally {
      void onChange();
    }
  }

  return (
    <Card className="p-8 space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-slate-900">เลือกจำนวนเครื่อง</h2>
        <p className="mt-2 text-sm text-slate-600">
          ราคาเท่ากันทุกเครื่อง — {PER_DEVICE_PRICE_THB.toLocaleString()} บาท/เครื่อง/เดือน
        </p>
      </div>

      <div className="space-y-3">
        <label htmlFor="qty" className="text-sm font-medium text-slate-700">
          จำนวน device ({MIN_QTY}-{MAX_QTY})
        </label>
        <input
          id="qty"
          type="number"
          min={MIN_QTY}
          max={MAX_QTY}
          value={qty}
          onChange={e => {
            const v = Math.max(MIN_QTY, Math.min(MAX_QTY, parseInt(e.target.value, 10) || MIN_QTY));
            setQty(v);
          }}
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-lg"
        />
      </div>

      <div className="rounded-lg bg-slate-100 p-4">
        <div className="flex items-center justify-between text-slate-700">
          <span>{qty} × {PER_DEVICE_PRICE_THB.toLocaleString()}</span>
          <span className="text-2xl font-semibold">{monthly.toLocaleString()} บาท/เดือน</span>
        </div>
        <p className="mt-2 text-xs text-slate-500">
          ปรับเพิ่ม-ลดได้ทุกเมื่อ — เพิ่ม device กลางรอบจะ prorate, ลด device จะมีผลรอบถัดไป
        </p>
      </div>

      <Button type="button" disabled={busy} onClick={() => void continueToCheckout()}>
        ไปชำระเงิน →
      </Button>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// Step 4-5: Payment in progress (polling for Stripe webhook to flip status)
// -----------------------------------------------------------------------------

function PaymentWaitingStep() {
  return (
    <Card className="p-8 space-y-4 text-center">
      <Spinner label="กำลังรอผลการชำระเงินจาก Stripe…" />
      <p className="text-sm text-slate-600">
        ถ้าจ่ายเงินสำเร็จแล้วแต่หน้านี้ยังไม่อัพเดท ลอง refresh หรือไปที่หน้า{' '}
        <a href="/billing" className="text-indigo-600 underline">การชำระเงิน</a>{' '}
        แล้วกด "ตรวจสอบสถานะ"
      </p>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// Step 6: Install APK — token-gated download
// -----------------------------------------------------------------------------

function InstallAPKStep({ state, onChange, onError }: StepProps) {
  const [busy, setBusy] = useState(false);

  async function next() {
    onError(null);
    setBusy(true);
    try {
      await onboardingApi.advanceOnboarding('pair_device');
      await onChange();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'ทำต่อไม่สำเร็จ');
      setBusy(false);
    }
  }

  async function skip() {
    onError(null);
    setBusy(true);
    try {
      await onboardingApi.skipOnboarding();
      await onChange();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'ข้ามขั้นตอนไม่สำเร็จ');
      setBusy(false);
    }
  }

  return (
    <Card className="p-8 space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-slate-900">ติดตั้ง Companion App บน Android</h2>
        <p className="mt-2 text-sm text-slate-600">
          quota ของคุณ: <strong>{state.device_quota}</strong> devices — ติดตั้ง APK บน Android phones ที่ login TikTok seller account ไว้แล้ว
        </p>
      </div>

      <ol className="list-decimal space-y-2 pl-5 text-slate-700">
        <li>กดดาวน์โหลด APK ข้างล่าง (ต้องเข้าจาก Android phone)</li>
        <li>เปิด APK ที่ดาวน์โหลดมา → กด ติดตั้ง (อนุญาต install จาก unknown source ถ้าระบบถาม)</li>
        <li>เปิดแอป Reruni Companion → จะเห็นปุ่ม "Scan Pair QR"</li>
      </ol>

      <a
        href={onboardingApi.COMPANION_APK_URL}
        className="inline-flex items-center justify-center rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
      >
        ดาวน์โหลด Reruni Companion APK
      </a>

      <div className="flex gap-3 border-t pt-4">
        <Button type="button" disabled={busy} onClick={() => void next()}>
          ติดตั้งแล้ว — ต่อไป
        </Button>
        <button
          type="button"
          disabled={busy}
          onClick={() => void skip()}
          className="text-sm text-slate-500 hover:text-slate-700"
        >
          ข้ามไปก่อน
        </button>
      </div>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// Step 7: Pair first device
// -----------------------------------------------------------------------------

function PairDeviceStep({ state, onChange, onError }: StepProps) {
  const [busy, setBusy] = useState(false);

  async function skip() {
    onError(null);
    setBusy(true);
    try {
      await onboardingApi.skipOnboarding();
      await onChange();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'ข้ามขั้นตอนไม่สำเร็จ');
      setBusy(false);
    }
  }

  return (
    <Card className="p-8 space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-slate-900">เชื่อมเครื่องแรก</h2>
        <p className="mt-2 text-sm text-slate-600">
          เชื่อมต่อ {state.devices_paired} / {state.device_quota} devices —
          ไปหน้า <a href="/devices" className="text-indigo-600 underline">Devices</a> เพื่อ generate QR แล้วสแกนจากแอป Companion
        </p>
      </div>

      {state.devices_paired > 0 ? (
        <div className="rounded-lg bg-emerald-50 p-4 text-emerald-800">
          ✓ ตรวจพบเครื่องออนไลน์แล้ว — กำลังพาไปขั้นต่อไป…
        </div>
      ) : (
        <Spinner label="กำลังรอเครื่องแรก online…" />
      )}

      <div className="flex gap-3 border-t pt-4">
        <a
          href="/devices"
          className="inline-flex items-center rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
        >
          ไปหน้า Devices →
        </a>
        <button
          type="button"
          disabled={busy}
          onClick={() => void skip()}
          className="text-sm text-slate-500 hover:text-slate-700"
        >
          ข้ามไปก่อน
        </button>
      </div>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// Step 8: First video upload
// -----------------------------------------------------------------------------

function FirstVideoStep({ onChange, onError }: Omit<StepProps, 'state'>) {
  const [busy, setBusy] = useState(false);

  async function skip() {
    onError(null);
    setBusy(true);
    try {
      await onboardingApi.skipOnboarding();
      await onChange();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'ข้ามขั้นตอนไม่สำเร็จ');
      setBusy(false);
    }
  }

  return (
    <Card className="p-8 space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-slate-900">อัพโหลดวิดีโอแรก</h2>
        <p className="mt-2 text-sm text-slate-600">
          อัพโหลดวิดีโอที่จะใช้ broadcast เป็น live — ระบบจะ loop วิดีโอนี้ตอน Start Live
        </p>
      </div>

      <div className="flex gap-3 border-t pt-4">
        <a
          href="/videos"
          className="inline-flex items-center rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
        >
          ไปหน้า Videos →
        </a>
        <button
          type="button"
          disabled={busy}
          onClick={() => void skip()}
          className="text-sm text-slate-500 hover:text-slate-700"
        >
          ข้ามไปก่อน
        </button>
      </div>
    </Card>
  );
}
