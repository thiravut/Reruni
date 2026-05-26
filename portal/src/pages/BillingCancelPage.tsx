// /billing/cancel — Stripe redirects here if the user backs out of Checkout.

import { Link } from 'react-router-dom';
import { Card } from '../components/Card';
import { Button } from '../components/Button';

export function BillingCancelPage() {
  return (
    <div className="min-h-full bg-slate-50 flex items-center justify-center p-6">
      <Card className="max-w-md w-full p-8 text-center">
        <div className="mx-auto w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mb-4">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#64748b" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="15" y1="9" x2="9" y2="15" />
            <line x1="9" y1="9" x2="15" y2="15" />
          </svg>
        </div>
        <h1 className="text-xl font-semibold text-slate-900">
          ยกเลิกการชำระเงิน
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          คุณยังไม่ได้สมัครแพ็กเกจ — สามารถกลับมาเลือกได้ทุกเมื่อ
        </p>
        <div className="mt-5 flex justify-center gap-2">
          <Link to="/subscribe">
            <Button>กลับไปเลือกแผน</Button>
          </Link>
          <Link to="/billing">
            <Button variant="secondary">ดูสถานะ</Button>
          </Link>
        </div>
      </Card>
    </div>
  );
}
