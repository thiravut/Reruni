import { Link } from 'react-router-dom';
import { Button } from '../components/Button';

export function NotFoundPage() {
  return (
    <div className="min-h-full flex items-center justify-center p-4">
      <div className="text-center max-w-sm">
        <h1 className="text-5xl font-bold text-slate-300">404</h1>
        <p className="mt-3 text-lg text-slate-700">ไม่พบหน้าที่คุณค้นหา</p>
        <p className="text-sm text-slate-500 mt-1">
          ลิงก์อาจเปลี่ยนหรือหน้านี้ถูกย้าย
        </p>
        <Link to="/dashboard" className="inline-block mt-5">
          <Button>กลับหน้าแดชบอร์ด</Button>
        </Link>
      </div>
    </div>
  );
}
