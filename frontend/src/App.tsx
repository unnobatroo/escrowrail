import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./features/authentication/auth";
import ContractDetail from "./features/escrow-trades/ContractDetail";
import Dashboard from "./features/escrow-trades/Dashboard";
import Login from "./features/authentication/Login";
import Register from "./features/authentication/Register";
import VerifyEmailView from "./features/authentication/VerifyEmailView";
import ForgotPasswordView from "./features/authentication/ForgotPasswordView";
import ResetPasswordView from "./features/authentication/ResetPasswordView";

function Guard({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return null;
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
  return (
    <Routes>
      <Route
        path="/login"
        element={user ? <Navigate to="/dashboard" replace /> : <Login />}
      />
      <Route
        path="/register"
        element={user ? <Navigate to="/dashboard" replace /> : <Register />}
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
        element={<Navigate to={user ? "/dashboard" : "/login"} replace />}
      />
    </Routes>
  );
}
