// /billing — current subscription view + Stripe Customer Portal access.

import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { ErrorBanner } from '../components/ErrorBanner';
import { Spinner } from '../components/Spinner';
import { useToast } from '../contexts/ToastContext';
import { ApiError } from '../api/client';
import * as billingApi from '../api/billing';
import { formatDateTime } from '../utils/format';
import type { Subscription } from '../types/api';
import { tierLabel, statusLabel, statusTone } from '../utils/billing';

export function BillingPage() {
  const toast = useToast();
  const [sub, setSub] = useState<Subscription | null | undefined>(undefined);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState<'portal' | 'cancel' | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await billingApi.getSubscription();
      setSub(res.subscription);
      setErr(null);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'โหลดข้อมูลแพ็กเกจไม่สำเร็จ');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function handlePortal() {
    setBusy('portal');
    try {
      const res = await billingApi.createPortalSession();
      window.location.assign(res.portal_url);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'เปิด Customer Portal ไม่สำเร็จ');
      setBusy(null);
    }
  }

  async function handleCancel() {
    if (!confirm('ยกเลิกการต่ออายุเมื่อสิ้นสุดรอบบิลปัจจุบัน?')) return;
    setBusy('cancel');
    try {
      const res = await billingApi.cancelSubscription();
      setSub(res.subscription);
      toast.success('ยกเลิกอัตโนมัติเมื่อสิ้นรอบบิลแล้ว');
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'ยกเลิกไม่สำเร็จ');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div>
      <PageHeader
        title="การชำระเงิน"
        description="จัดการแพ็กเกจและประวัติการเรียกเก็บ"
      />

      <ErrorBanner message={err} onDismiss={() => setErr(null)} />

      {sub === undefined ? (
        <Spinner label="กำลังโหลด…" />
      ) : sub === null ? (
        <Card className="p-6">
          <p className="text-sm text-slate-600 mb-3">
            คุณยังไม่มีแพ็กเกจ — กรุณาเลือกแผนเพื่อเริ่มใช้งาน
          </p>
          <Link to="/subscribe">
            <Button>เลือกแพ็กเกจ</Button>
          </Link>
        </Card>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <Card className="p-6">
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">
                  แพ็กเกจปัจจุบัน
                </h2>
                <p className="mt-1 text-2xl font-bold text-brand-600">
                  {tierLabel(sub.tier)}
                </p>
              </div>
              <StatusPill tone={statusTone(sub.status)}>
                {statusLabel(sub.status)}
              </StatusPill>
            </div>

            <dl className="mt-5 grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
              <Stat label="จำนวน device">
                {sub.device_quota ?? '—'}
              </Stat>
              <Stat label="ต่ออายุอัตโนมัติ">
                {sub.cancel_at_period_end ? 'ปิดอยู่ — จะยกเลิกตอนสิ้นรอบ' : 'เปิดอยู่'}
              </Stat>
              <Stat label="รอบบิลปัจจุบันเริ่ม">
                {formatDateTime(sub.current_period_start)}
              </Stat>
              <Stat label="รอบบิลปัจจุบันสิ้นสุด">
                {formatDateTime(sub.current_period_end)}
              </Stat>
              <Stat label="Stripe Subscription ID">
                <code className="font-mono text-xs">
                  {sub.stripe_subscription_id ?? '—'}
                </code>
              </Stat>
            </dl>

            <div className="mt-6 flex flex-wrap gap-2">
              <Button onClick={handlePortal} loading={busy === 'portal'}>
                จัดการการชำระเงิน / เปลี่ยนจำนวน device
              </Button>
              {!sub.cancel_at_period_end && sub.status === 'active' ? (
                <Button
                  variant="danger"
                  loading={busy === 'cancel'}
                  onClick={handleCancel}
                >
                  ยกเลิกแพ็กเกจ
                </Button>
              ) : null}
            </div>
          </Card>

          <Card className="p-6">
            <h2 className="text-sm font-semibold text-slate-700 mb-3">
              ข้อมูลเพิ่มเติม
            </h2>
            <ul className="text-sm text-slate-600 space-y-2">
              <li>
                การชำระเงินผ่าน Stripe (ปลอดภัย, รองรับบัตรเครดิต/PromptPay)
              </li>
              <li>
                สามารถยกเลิกได้ตลอด — จะมีผลเมื่อสิ้นสุดรอบบิลปัจจุบัน
              </li>
              <li>
                ใบเสร็จ/Invoice เข้าถึงผ่าน "จัดการการชำระเงิน" → Stripe Portal
              </li>
            </ul>
          </Card>
        </div>
      )}
    </div>
  );
}

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wider text-slate-500">{label}</dt>
      <dd className="mt-0.5 text-slate-800">{children}</dd>
    </div>
  );
}

interface PillProps {
  tone: 'green' | 'yellow' | 'red' | 'gray';
  children: React.ReactNode;
}

function StatusPill({ tone, children }: PillProps) {
  const cls = {
    green: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    yellow: 'bg-amber-50 text-amber-700 border-amber-200',
    red: 'bg-rose-50 text-rose-700 border-rose-200',
    gray: 'bg-slate-50 text-slate-700 border-slate-200',
  }[tone];
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-medium ${cls}`}
    >
      {children}
    </span>
  );
}
