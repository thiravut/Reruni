import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useToast } from '../contexts/ToastContext';
import * as authApi from '../api/auth';
import { ApiError } from '../api/client';
import { Button } from '../components/Button';
import { TextField } from '../components/Field';
import { ErrorBanner } from '../components/ErrorBanner';
import { validatePassword } from '../utils/validation';

// Forced password change after admin reset (must_change_password === true),
// also usable voluntarily anytime via /change-password.
export function ChangePasswordPage() {
  const { state, setUser, logout } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();

  const forced = state.user?.must_change_password ?? false;

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');

  const [currentErr, setCurrentErr] = useState<string | null>(null);
  const [nextErr, setNextErr] = useState<string | null>(null);
  const [confirmErr, setConfirmErr] = useState<string | null>(null);
  const [formErr, setFormErr] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormErr(null);

    const cErr = current ? null : 'กรุณากรอกรหัสผ่านปัจจุบัน';
    const nErr = validatePassword(next);
    const sameAsOld =
      !nErr && current && next === current
        ? 'รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม'
        : null;
    const cfErr = confirm === next ? null : 'รหัสผ่านไม่ตรงกัน';

    setCurrentErr(cErr);
    setNextErr(nErr ?? sameAsOld);
    setConfirmErr(cfErr);
    if (cErr || nErr || sameAsOld || cfErr) return;

    setSubmitting(true);
    try {
      const res = await authApi.changePassword(current, next);
      setUser(res.user);
      toast.success('เปลี่ยนรหัสผ่านสำเร็จ');
      navigate('/dashboard', { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'INVALID_CREDENTIALS') {
          setCurrentErr('รหัสผ่านปัจจุบันไม่ถูกต้อง');
        } else if (err.code === 'WEAK_PASSWORD') {
          setNextErr(err.message);
        } else {
          setFormErr(err.message);
        }
      } else {
        setFormErr('เกิดข้อผิดพลาด กรุณาลองใหม่');
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSignOut() {
    await logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="min-h-full flex items-center justify-center p-4 bg-slate-50">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="inline-flex w-12 h-12 rounded-lg bg-brand-600 text-white items-center justify-center font-bold text-xl">
            T
          </div>
          <h1 className="mt-3 text-2xl font-semibold text-slate-800">
            ตั้งรหัสผ่านใหม่
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            {forced
              ? 'ผู้ดูแลรีเซ็ตรหัสผ่านให้คุณ กรุณาตั้งรหัสผ่านใหม่ก่อนใช้งาน'
              : 'อัปเดตรหัสผ่านบัญชีของคุณ'}
          </p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="bg-white rounded-lg shadow-sm border border-slate-200 p-5 flex flex-col gap-4"
        >
          <ErrorBanner message={formErr} onDismiss={() => setFormErr(null)} />

          <TextField
            label={forced ? 'รหัสผ่านชั่วคราว' : 'รหัสผ่านปัจจุบัน'}
            name="current_password"
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => {
              setCurrent(e.target.value);
              setCurrentErr(null);
            }}
            error={currentErr}
            required
          />

          <TextField
            label="รหัสผ่านใหม่"
            name="new_password"
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(e) => {
              setNext(e.target.value);
              setNextErr(null);
            }}
            error={nextErr}
            hint="อย่างน้อย 8 ตัวอักษร พร้อมตัวอักษรและตัวเลข"
            required
          />

          <TextField
            label="ยืนยันรหัสผ่านใหม่"
            name="confirm_new_password"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => {
              setConfirm(e.target.value);
              setConfirmErr(null);
            }}
            error={confirmErr}
            required
          />

          <Button type="submit" loading={submitting} block size="lg">
            บันทึกรหัสผ่านใหม่
          </Button>

          {forced && (
            <button
              type="button"
              onClick={handleSignOut}
              className="text-center text-sm text-slate-500 hover:text-slate-700"
            >
              ออกจากระบบ
            </button>
          )}
        </form>
      </div>
    </div>
  );
}
