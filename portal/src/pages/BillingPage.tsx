// /billing — current subscription summary + Stripe Customer Portal access
// + payment history (pulled live from Stripe via /api/billing/invoices).

import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { Confirm } from '../components/Confirm';
import { ErrorBanner } from '../components/ErrorBanner';
import { Spinner } from '../components/Spinner';
import { useToast } from '../contexts/ToastContext';
import { ApiError } from '../api/client';
import * as billingApi from '../api/billing';
import { formatDateTime } from '../utils/format';
import type { Invoice, Subscription } from '../types/api';
import { statusLabel, statusTone } from '../utils/billing';

const PRICE_PER_DEVICE_THB = 299;

export function BillingPage() {
  const toast = useToast();
  const [sub, setSub] = useState<Subscription | null | undefined>(undefined);
  const [invoices, setInvoices] = useState<Invoice[] | undefined>(undefined);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState<'portal' | 'cancel' | null>(null);
  const [showCancelConfirm, setShowCancelConfirm] = useState(false);

  const load = useCallback(async () => {
    try {
      const [subRes, invRes] = await Promise.all([
        billingApi.getSubscription(),
        billingApi.listInvoices().catch(() => ({ invoices: [] as Invoice[] })),
      ]);
      setSub(subRes.subscription);
      setInvoices(invRes.invoices ?? []);
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

  function handleCancel() {
    setShowCancelConfirm(true);
  }

  async function doCancel() {
    setShowCancelConfirm(false);
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
        description="ดูแพ็กเกจปัจจุบัน ประวัติการจ่ายเงิน และจัดการบัตร"
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
        <div className="space-y-4">
          <SummaryCard
            sub={sub}
            onPortal={handlePortal}
            onCancel={handleCancel}
            busy={busy}
          />
          <InvoiceHistory invoices={invoices} />
        </div>
      )}

      <Confirm
        open={showCancelConfirm}
        title="ยืนยันยกเลิกแพ็กเกจ"
        message="ระบบจะหยุดต่ออายุอัตโนมัติเมื่อสิ้นรอบบิลปัจจุบัน คุณยังใช้งานได้ตามปกติจนถึงวันที่หมดอายุ"
        confirmLabel="ยกเลิกการต่ออายุ"
        danger
        loading={busy === 'cancel'}
        onConfirm={doCancel}
        onCancel={() => setShowCancelConfirm(false)}
      />
    </div>
  );
}

interface SummaryCardProps {
  sub: Subscription;
  onPortal: () => void;
  onCancel: () => void;
  busy: 'portal' | 'cancel' | null;
}

function SummaryCard({ sub, onPortal, onCancel, busy }: SummaryCardProps) {
  const quota = sub.device_quota ?? 0;
  const monthly = quota * PRICE_PER_DEVICE_THB;

  return (
    <Card className="p-6">
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div>
          <h2 className="text-sm uppercase tracking-wider text-slate-500">แพ็กเกจปัจจุบัน</h2>
          <p className="mt-1 text-3xl font-bold text-slate-900">
            {monthly.toLocaleString()} <span className="text-base font-normal text-slate-500">บาท/เดือน</span>
          </p>
          <p className="mt-1 text-sm text-slate-600">
            {quota} เครื่อง × {PRICE_PER_DEVICE_THB.toLocaleString()} บาท
          </p>
        </div>
        <StatusPill tone={statusTone(sub.status)}>
          {statusLabel(sub.status)}
        </StatusPill>
      </div>

      <dl className="mt-6 grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
        <Stat label="รอบบิลปัจจุบัน">
          {sub.current_period_start && sub.current_period_end ? (
            <>
              <div>{formatDateTime(sub.current_period_start)}</div>
              <div className="text-xs text-slate-500">
                ถึง {formatDateTime(sub.current_period_end)}
              </div>
            </>
          ) : (
            '—'
          )}
        </Stat>
        <Stat label={sub.cancel_at_period_end ? 'จะหมดอายุ' : 'รอบบิลถัดไป'}>
          {sub.current_period_end ? (
            <>
              <div>{formatDateTime(sub.current_period_end)}</div>
              <div className="text-xs text-slate-500">
                {daysFromNowLabel(sub.current_period_end)}
              </div>
            </>
          ) : (
            '—'
          )}
        </Stat>
        <Stat label="ต่ออายุอัตโนมัติ">
          {sub.cancel_at_period_end ? (
            <span className="text-rose-600">ปิดอยู่ — จะยกเลิกตอนสิ้นรอบ</span>
          ) : (
            <span className="text-emerald-700">เปิดอยู่</span>
          )}
        </Stat>
        <Stat label="Stripe Subscription ID">
          <code className="font-mono text-xs text-slate-500">
            {sub.stripe_subscription_id ?? '—'}
          </code>
        </Stat>
      </dl>

      <div className="mt-6 flex flex-wrap gap-2 border-t pt-4">
        <Button onClick={onPortal} loading={busy === 'portal'}>
          จัดการการชำระเงิน / เปลี่ยนจำนวน device
        </Button>
        {!sub.cancel_at_period_end && sub.status === 'active' ? (
          <Button
            variant="danger"
            loading={busy === 'cancel'}
            onClick={onCancel}
          >
            ยกเลิกแพ็กเกจ
          </Button>
        ) : null}
      </div>

      <p className="mt-4 text-xs text-slate-500">
        💡 ปุ่ม "จัดการการชำระเงิน" จะพาไปที่ Stripe — ใช้เปลี่ยนบัตร เพิ่ม/ลดจำนวน device โหลด invoice และเปลี่ยน billing email ได้ทั้งหมดในที่เดียว
      </p>
    </Card>
  );
}

function InvoiceHistory({ invoices }: { invoices: Invoice[] | undefined }) {
  return (
    <Card className="p-6">
      <h2 className="text-base font-semibold text-slate-900 mb-1">
        ประวัติการจ่ายเงิน
      </h2>
      <p className="text-xs text-slate-500 mb-4">
        ดึงจาก Stripe โดยตรง — รายการล่าสุด 20 รายการ
      </p>

      {invoices === undefined ? (
        <Spinner label="กำลังโหลดประวัติ…" />
      ) : invoices.length === 0 ? (
        <p className="text-sm text-slate-500">ยังไม่มีประวัติการจ่ายเงิน</p>
      ) : (
        <div className="overflow-x-auto -mx-6">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wider text-slate-500">
              <tr>
                <th className="px-6 py-2 text-left font-medium">วันที่</th>
                <th className="px-3 py-2 text-left font-medium">เลขที่</th>
                <th className="px-3 py-2 text-right font-medium">ยอด</th>
                <th className="px-3 py-2 text-left font-medium">สถานะ</th>
                <th className="px-6 py-2 text-right font-medium">ใบเสร็จ</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {invoices.map((inv) => (
                <InvoiceRow key={inv.id} inv={inv} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function InvoiceRow({ inv }: { inv: Invoice }) {
  const dateUnix = inv.paid_at ?? inv.created;
  const date = new Date(dateUnix * 1000);
  const amount = (inv.amount_paid > 0 ? inv.amount_paid : inv.amount_due) / 100;
  const tone = invoiceStatusTone(inv.status);
  return (
    <tr>
      <td className="px-6 py-3 whitespace-nowrap">
        <div>{date.toLocaleDateString('th-TH', { year: 'numeric', month: 'short', day: 'numeric' })}</div>
        <div className="text-xs text-slate-500">
          {date.toLocaleTimeString('th-TH', { hour: '2-digit', minute: '2-digit' })}
        </div>
      </td>
      <td className="px-3 py-3 font-mono text-xs text-slate-600">
        {inv.number || inv.id.slice(0, 12)}
      </td>
      <td className="px-3 py-3 text-right whitespace-nowrap">
        <span className="font-semibold">{amount.toLocaleString()}</span>{' '}
        <span className="text-xs text-slate-500 uppercase">{inv.currency}</span>
      </td>
      <td className="px-3 py-3">
        <StatusPill tone={tone}>{invoiceStatusLabel(inv.status)}</StatusPill>
      </td>
      <td className="px-6 py-3 text-right whitespace-nowrap">
        {inv.pdf ? (
          <a
            href={inv.pdf}
            target="_blank"
            rel="noreferrer"
            className="text-brand-600 hover:underline text-sm"
          >
            PDF ↗
          </a>
        ) : inv.hosted_url ? (
          <a
            href={inv.hosted_url}
            target="_blank"
            rel="noreferrer"
            className="text-brand-600 hover:underline text-sm"
          >
            ดู ↗
          </a>
        ) : (
          <span className="text-xs text-slate-400">—</span>
        )}
      </td>
    </tr>
  );
}

function invoiceStatusLabel(status: string): string {
  switch (status) {
    case 'paid':
      return 'จ่ายแล้ว';
    case 'open':
      return 'รอจ่าย';
    case 'draft':
      return 'ร่าง';
    case 'uncollectible':
      return 'เก็บเงินไม่ได้';
    case 'void':
      return 'ยกเลิก';
    default:
      return status;
  }
}

function invoiceStatusTone(status: string): 'green' | 'yellow' | 'red' | 'gray' {
  switch (status) {
    case 'paid':
      return 'green';
    case 'open':
    case 'draft':
      return 'yellow';
    case 'uncollectible':
      return 'red';
    case 'void':
      return 'gray';
    default:
      return 'gray';
  }
}

function daysFromNowLabel(iso: string): string {
  const target = new Date(iso).getTime();
  const now = Date.now();
  const days = Math.round((target - now) / (1000 * 60 * 60 * 24));
  if (days === 0) return 'วันนี้';
  if (days > 0) return `อีก ${days} วัน`;
  return `${Math.abs(days)} วันที่แล้ว`;
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
