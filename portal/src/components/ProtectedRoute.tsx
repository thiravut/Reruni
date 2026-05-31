import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { LoadingPage } from './Spinner';

// Pages reachable even without an active subscription. New users land on
// /onboarding; existing subscribers can manage billing or settle a forced
// password change without being kicked back.
const BILLING_ALLOWED_PATHS = [
  '/onboarding',
  '/subscribe',
  '/billing',
  '/billing/success',
  '/billing/cancel',
  '/change-password',
];

function isBillingAllowed(path: string): boolean {
  return BILLING_ALLOWED_PATHS.some(
    (p) => path === p || path.startsWith(p + '/'),
  );
}

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const location = useLocation();
  if (state.loading) return <LoadingPage />;
  if (!state.user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  // Forced password change after admin reset — block normal app navigation.
  // This stays the highest-priority redirect (per portal-backoffice-spec).
  if (
    state.user.must_change_password &&
    location.pathname !== '/change-password'
  ) {
    return <Navigate to="/change-password" replace />;
  }
  // Onboarding wizard — new users with an incomplete wizard get redirected
  // to /onboarding (skips for admins and for users already on the wizard or
  // a billing page where Stripe Checkout returns).
  if (
    state.user.role !== 'admin' &&
    state.user.onboarding_step &&
    state.user.onboarding_step !== 'complete' &&
    !isBillingAllowed(location.pathname)
  ) {
    return <Navigate to="/onboarding" replace />;
  }
  // Subscription gating — admins bypass; otherwise require status='active'
  // to reach any non-billing page.
  if (
    state.user.role !== 'admin' &&
    state.user.subscription_status !== 'active' &&
    !isBillingAllowed(location.pathname)
  ) {
    return <Navigate to="/subscribe" replace />;
  }
  return <>{children}</>;
}
