import { useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { apiError } from "../../api";
import { useAuth } from "./auth";
import PasswordInput from "../../shared/components/PasswordInput";

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [unverified, setUnverified] = useState(false);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    setUnverified(false);
    try {
      // Trim: copy-pasted credentials often carry a trailing space that
      // fails auth invisibly (Argon2 compares raw bytes).
      await login(email.trim(), password.trim());
      navigate("/dashboard");
    } catch (err) {
      const apiErr = apiError(err);
      if (apiErr.code === "EMAIL_NOT_VERIFIED") {
        setUnverified(true);
      } else {
        setError(apiErr.message ?? "Login failed");
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#163300]">
      <form
        onSubmit={submit}
        className="w-full max-w-sm rounded-xl bg-white p-8 shadow-lg"
      >
        <div className="mb-6 flex items-center gap-2 text-[#163300]">
          <ShieldCheck size={28} className="text-[#9FE870]" />
          <h1 className="text-xl font-bold">Wise Smart Escrow</h1>
        </div>
        <label className="mb-1 block text-sm font-medium text-[#5C685B]">Email</label>
        <input
          data-testid="email"
          type="email"
          required
          autoFocus
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="mb-4 w-full rounded-lg border border-slate-200 px-3 py-2"
        />
        <div className="mb-1 flex items-baseline justify-between">
          <label className="block text-sm font-medium text-[#5C685B]">Password</label>
          <Link
            to="/forgot-password"
            className="text-xs font-medium text-[#00A2DD] underline"
          >
            Forgot password?
          </Link>
        </div>
        <PasswordInput
          data-testid="password"
          required
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="mb-4"
        />
        {unverified && (
          <div
            data-testid="unverified-banner"
            className="mb-4 rounded-lg bg-[#FFF3CD] px-3 py-2 text-sm text-[#7A5800]"
          >
            Your account is not verified yet. Check your inbox or{" "}
            <Link
              to={`/verify-email?email=${encodeURIComponent(email)}`}
              className="font-semibold underline"
            >
              resend verification email
            </Link>
            .
          </div>
        )}
        {error && (
          <div
            data-testid="login-error"
            className="mb-4 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
          >
            {error}
          </div>
        )}
        <button
          data-testid="submit"
          type="submit"
          disabled={busy}
          className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
        >
          {busy ? "Signing in…" : "Sign in"}
        </button>
        <p className="mt-4 text-center text-sm text-[#5C685B]">
          No account?{" "}
          <Link to="/register" className="font-medium text-[#163300] underline">
            Register your organization
          </Link>
        </p>
      </form>
    </div>
  );
}
