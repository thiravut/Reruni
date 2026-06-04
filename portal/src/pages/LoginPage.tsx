import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { ApiError } from '../api/client';
import { Button } from '../components/Button';
import { TextField } from '../components/Field';
import { ErrorBanner } from '../components/ErrorBanner';
import { validateEmail } from '../utils/validation';

export function LoginPage() {
  const { state, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation() as { state?: { from?: string } };

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [emailErr, setEmailErr] = useState<string | null>(null);
  const [pwErr, setPwErr] = useState<string | null>(null);
  const [formErr, setFormErr] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!state.loading && state.user) {
    return <Navigate to="/dashboard" replace />;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormErr(null);
    const eErr = validateEmail(email.trim());
    const pErr = password ? null : 'กรุณากรอกรหัสผ่าน';
    setEmailErr(eErr);
    setPwErr(pErr);
    if (eErr || pErr) return;

    setSubmitting(true);
    try {
      await login(email.trim().toLowerCase(), password);
      const dest = location.state?.from ?? '/dashboard';
      navigate(dest, { replace: true });
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
            เข้าสู่ระบบ
          </h1>
          <p className="mt-1 text-sm text-slate-500">Reruni</p>
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
            autoComplete="current-password"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
              setPwErr(null);
            }}
            error={pwErr}
            required
          />

          <Button type="submit" loading={submitting} block size="lg">
            เข้าสู่ระบบ
          </Button>

          <p className="text-center text-sm text-slate-500">
            ยังไม่มีบัญชี?{' '}
            <Link to="/signup" className="text-brand-600 hover:underline">
              สมัครใช้งาน
            </Link>
          </p>
        </form>
      </div>
    </div>
  );
}
