import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { ApiError } from '../api/client';
import { Button } from '../components/Button';
import { TextField } from '../components/Field';
import { ErrorBanner } from '../components/ErrorBanner';
import { validateEmail, validatePassword } from '../utils/validation';

export function SignupPage() {
  const { state, signup } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [agree, setAgree] = useState(false);
  const [emailErr, setEmailErr] = useState<string | null>(null);
  const [pwErr, setPwErr] = useState<string | null>(null);
  const [confirmErr, setConfirmErr] = useState<string | null>(null);
  const [agreeErr, setAgreeErr] = useState<string | null>(null);
  const [formErr, setFormErr] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!state.loading && state.user) {
    return <Navigate to="/dashboard" replace />;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormErr(null);
    const eErr = validateEmail(email.trim());
    const pErr = validatePassword(password);
    const cErr = confirm === password ? null : 'รหัสผ่านไม่ตรงกัน';
    const aErr = agree ? null : 'กรุณายอมรับข้อตกลงการใช้งาน';
    setEmailErr(eErr);
    setPwErr(pErr);
    setConfirmErr(cErr);
    setAgreeErr(aErr);
    if (eErr || pErr || cErr || aErr) return;

    setSubmitting(true);
    try {
      await signup(email.trim().toLowerCase(), password);
      navigate('/dashboard', { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setFormErr(err.message);
      } else {
        setFormErr('เกิดข้อผิดพลาด กรุณาลองใหม่');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-full flex items-center justify-center p-4 bg-slate-50">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="inline-flex w-12 h-12 rounded-lg bg-brand-600 text-white items-center justify-center font-bold text-xl">
            R
          </div>
          <h1 className="mt-3 text-2xl font-semibold text-slate-800">
            สมัครใช้งาน
          </h1>
          <p className="mt-1 text-sm text-slate-500">เริ่มใช้ Reruni ฟรี</p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="bg-white rounded-lg shadow-sm border border-slate-200 p-5 flex flex-col gap-4"
        >
          <ErrorBanner message={formErr} onDismiss={() => setFormErr(null)} />

          <TextField
            label="อีเมล"
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
              setEmailErr(null);
            }}
            error={emailErr}
            required
          />

          <TextField
            label="รหัสผ่าน"
            name="password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
              setPwErr(null);
            }}
            error={pwErr}
            hint="อย่างน้อย 8 ตัวอักษร พร้อมตัวอักษรและตัวเลข"
            required
          />

          <TextField
            label="ยืนยันรหัสผ่าน"
            name="confirm"
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

          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={agree}
              onChange={(e) => {
                setAgree(e.target.checked);
                setAgreeErr(null);
              }}
              className="mt-1"
            />
            <span className="text-slate-600">
              ฉันยอมรับ
              <a
                href="#"
                onClick={(e) => e.preventDefault()}
                className="text-brand-600 hover:underline mx-1"
              >
                ข้อตกลงการใช้งาน
              </a>
              และ
              <a
                href="#"
                onClick={(e) => e.preventDefault()}
                className="text-brand-600 hover:underline ml-1"
              >
                นโยบายความเป็นส่วนตัว
              </a>
            </span>
          </label>
          {agreeErr && <p className="text-xs text-rose-600 -mt-2">{agreeErr}</p>}

          <Button type="submit" loading={submitting} block size="lg">
            สมัครใช้งาน
          </Button>

          <p className="text-center text-sm text-slate-500">
            มีบัญชีแล้ว?{' '}
            <Link to="/login" className="text-brand-600 hover:underline">
              เข้าสู่ระบบ
            </Link>
          </p>
        </form>
      </div>
    </div>
  );
}
