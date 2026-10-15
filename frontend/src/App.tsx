import { lazy, Suspense } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
import { useAuth } from "./features/authentication/AuthProvider";
import ErrorBoundary from "./shared/components/ErrorBoundary";

const ContractDetail = lazy(
  () => import("./features/escrow-trades/drawer/ContractDetail"),
);
const Dashboard = lazy(
  () => import("./features/escrow-trades/dashboard/Dashboard"),
);
const Login = lazy(() => import("./features/authentication/Login"));
const Register = lazy(() => import("./features/authentication/Register"));
const VerifyEmailView = lazy(
  () => import("./features/authentication/VerifyEmailView"),
);
const ForgotPasswordView = lazy(
  () => import("./features/authentication/ForgotPasswordView"),
);
const ResetPasswordView = lazy(
  () => import("./features/authentication/ResetPasswordView"),
);

function Guard({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  if (loading)
    return (
      <div
        role="status"
        className="flex min-h-screen items-center justify-center bg-brand-sand"
      >
        <div
          aria-label="Loading"
          className="h-8 w-8 animate-spin rounded-full border-2 border-brand-forest border-t-transparent"
        />
        <span className="sr-only">Checking your session…</span>
      </div>
    );
  if (!user) return <Navigate to="/login" replace />;
  if (!user.email_verified)
    return (
      <Navigate
        to={
          user.email
            ? `/verify-email?email=${encodeURIComponent(user.email)}`
            : "/verify-email"
        }
        replace
      />
    );
  return children;
}

export default function App() {
  const { user } = useAuth();
  const location = useLocation();
  return (
    <ErrorBoundary>
      <AnimatePresence mode="wait">
        <motion.div
          key={location.pathname}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -6 }}
          transition={{ duration: 0.18, ease: "easeOut" }}
        >
          <Suspense
            fallback={
              <div className="flex min-h-screen items-center justify-center bg-brand-sand">
                <div className="h-8 w-8 animate-spin rounded-full border-2 border-brand-forest border-t-transparent" />
              </div>
            }
          >
          <Routes location={location}>
            <Route
              path="/login"
              element={
                user?.email_verified ? (
                  <Navigate to="/dashboard" replace />
                ) : (
                  <Login />
                )
              }
            />
            <Route
              path="/register"
              element={
                user?.email_verified ? (
                  <Navigate to="/dashboard" replace />
                ) : (
                  <Register />
                )
              }
            />
            <Route path="/verify-email" element={<VerifyEmailView />} />
            <Route path="/forgot-password" element={<ForgotPasswordView />} />
            <Route path="/reset-password" element={<ResetPasswordView />} />
            <Route
              path="/dashboard"
              element={
                <Guard>
                  <Dashboard />
                </Guard>
              }
            />
            <Route
              path="/contracts/:id"
              element={
                <Guard>
                  <ContractDetail />
                </Guard>
              }
            />
            <Route
              path="*"
              element={
                <Navigate to={user ? "/dashboard" : "/login"} replace />
              }
            />
          </Routes>
          </Suspense>
        </motion.div>
      </AnimatePresence>
    </ErrorBoundary>
  );
}
