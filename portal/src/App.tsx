import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { ToastProvider } from './contexts/ToastContext';
import { RealtimeProvider } from './contexts/RealtimeContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import { AppLayout } from './layouts/AppLayout';
import { LoginPage } from './pages/LoginPage';
import { SignupPage } from './pages/SignupPage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { DashboardPage } from './pages/DashboardPage';
import { DevicesPage } from './pages/DevicesPage';
import { VideosPage } from './pages/VideosPage';
import { VideoBannersPage } from './pages/VideoBannersPage';
import { LiveConfigPage } from './pages/LiveConfigPage';
import { ActiveLivesPage } from './pages/ActiveLivesPage';
import { HistoryPage } from './pages/HistoryPage';
import { SubscribePage } from './pages/SubscribePage';
import { BillingPage } from './pages/BillingPage';
import { BillingSuccessPage } from './pages/BillingSuccessPage';
import { BillingCancelPage } from './pages/BillingCancelPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { SetupGuidePage } from './pages/SetupGuidePage';
import { OnboardingPage } from './pages/OnboardingPage';

export default function App() {
  return (
    <ToastProvider>
      <AuthProvider>
        <RealtimeProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />

            {/* Auth-required but without AppLayout — usable while
                must_change_password forces redirect away from main app. */}
            <Route
              path="/change-password"
              element={
                <ProtectedRoute>
                  <ChangePasswordPage />
                </ProtectedRoute>
              }
            />

            {/* Billing pages — accessible without an active subscription so
                pending/canceled users can still complete payment. */}
            <Route
              path="/subscribe"
              element={
                <ProtectedRoute>
                  <SubscribePage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/billing/success"
              element={
                <ProtectedRoute>
                  <BillingSuccessPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/billing/cancel"
              element={
                <ProtectedRoute>
                  <BillingCancelPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/onboarding"
              element={
                <ProtectedRoute>
                  <OnboardingPage />
                </ProtectedRoute>
              }
            />

            <Route
              element={
                <ProtectedRoute>
                  <AppLayout />
                </ProtectedRoute>
              }
            >
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/devices" element={<DevicesPage />} />
              <Route path="/videos" element={<VideosPage />} />
              <Route path="/videos/:id/banners" element={<VideoBannersPage />} />
              <Route path="/live" element={<LiveConfigPage />} />
              <Route path="/live/active" element={<ActiveLivesPage />} />
              <Route path="/history" element={<HistoryPage />} />
              <Route path="/setup-guide" element={<SetupGuidePage />} />
              <Route path="/billing" element={<BillingPage />} />
            </Route>

            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </RealtimeProvider>
      </AuthProvider>
    </ToastProvider>
  );
}
