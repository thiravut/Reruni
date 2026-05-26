// /subscribe — Tier picker. Loads tiers from /api/billing/tiers and lets the
// user start a Stripe Checkout session. On success → Stripe redirects to
// PORTAL_SUCCESS_URL (=/billing/success). On cancel → /billing/cancel.

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { ErrorBanner } from '../components/ErrorBanner';
import { Spinner } from '../components/Spinner';
import { useAuth } from '../contexts/AuthContext';
import { ApiError } from '../api/client';
import * as billingApi from '../api/billing';
import type { Tier, TierKey } from '../types/api';

export function SubscribePage() {
  const { state, logout } = useAuth();
  const [tiers, setTiers] = useState<Tier[] | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [busyTier, setBusyTier] = useState<TierKey | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const res = await billingApi.getTiers();
        setTiers(res.tiers);
      } catch (e) {
        setLoadErr(e instanceof ApiError ? e.message : 'โหลดราคาแพ็กเกจไม่สำเร็จ');
      }
    })();
  }, []);

  async function handleChoose(tier: TierKey) {
    setActionErr(null);
    setBusyTier(tier);
    try {
      const res = await billingApi.createCheckoutSession(tier);
      window.location.assign(res.checkout_url);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === 'ALREADY_SUBSCRIBED') {
          setActionErr('คุณมีแพ็กเกจอยู่แล้ว — ไปที่หน้า "การชำระเงิน" เพื่อจัดการ');
        } else {
          setActionErr(e.message);
        }
      } else {
        setActionErr('เริ่ม Checkout ไม่สำเร็จ');
      }
      setBusyTier(null);
    }
  }

  return (
    <div className="min-h-full bg-slate-50 p-6 sm:p-10">
      <div className="mx-auto max-w-5xl">
        <PageHeader
          title="เลือกแพ็กเกจของคุณ"
          description={`สวัสดี ${state.user?.email ?? ''} — เลือกแพ็กเกจเพื่อเริ่มใช้งาน`}
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

        {tiers === null ? (
          <Spinner label="กำลังโหลดราคา…" />
        ) : tiers.length === 0 ? (
          <Card className="p-6 text-center text-sm text-slate-600">
            ยังไม่ได้กำหนดราคาแพ็กเกจในระบบ — กรุณาติดต่อทีมงาน
          </Card>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mt-4">
            {tiers.map((t) => (
              <TierCard
                key={t.key}
                tier={t}
                busy={busyTier === t.key}
                disabled={busyTier !== null}
                onChoose={() => void handleChoose(t.key)}
              />
            ))}
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

interface TierCardProps {
  tier: Tier;
  busy: boolean;
  disabled: boolean;
  onChoose: () => void;
}

function TierCard({ tier, busy, disabled, onChoose }: TierCardProps) {
  return (
    <Card className="p-6 flex flex-col">
      <h3 className="text-lg font-semibold text-slate-900">{tier.name}</h3>
      <p className="mt-1 text-sm text-slate-500">สูงสุด {tier.devices} เครื่อง</p>
      <div className="mt-4 flex items-baseline gap-1">
        <span className="text-3xl font-bold text-slate-900">
          {tier.price_thb.toLocaleString()}
        </span>
        <span className="text-sm text-slate-500">บาท/เดือน</span>
      </div>
      <ul className="mt-4 space-y-1 text-sm text-slate-600 flex-1">
        <li>• คุม Devices ได้พร้อมกัน {tier.devices} เครื่อง</li>
        <li>• Smart Overlay broadcast</li>
        <li>• Dynamic Banner real-time</li>
        <li>• Web dashboard + mobile-responsive</li>
      </ul>
      <Button
        block
        size="lg"
        className="mt-5"
        loading={busy}
        disabled={disabled}
        onClick={onChoose}
      >
        เลือกแผน {tier.name}
      </Button>
    </Card>
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
