import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
import { useAuth } from "./features/authentication/AuthProvider";
import ContractDetail from "./features/escrow-trades/drawer/ContractDetail";
import Dashboard from "./features/escrow-trades/dashboard/Dashboard";
import Login from "./features/authentication/Login";
import Register from "./features/authentication/Register";
import VerifyEmailView from "./features/authentication/VerifyEmailView";
import ForgotPasswordView from "./features/authentication/ForgotPasswordView";
import ResetPasswordView from "./features/authentication/ResetPasswordView";
import ErrorBoundary from "./shared/components/ErrorBoundary";

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
        to={`/verify-email?email=${encodeURIComponent(user.email)}`}
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
          <Routes location={location}>
            <Route
              path="/login"
              element={user ? <Navigate to="/dashboard" replace /> : <Login />}
            />
            <Route
              path="/register"
              element={
                user ? <Navigate to="/dashboard" replace /> : <Register />
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
        </motion.div>
      </AnimatePresence>
    </ErrorBoundary>
  );
}
