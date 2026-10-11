import { useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { apiError } from "../api";
import { useAuth } from "../auth";

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await login(email, password);
      navigate("/dashboard");
    } catch (err) {
      setError(apiError(err).message ?? "Login failed");
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
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="mb-4 w-full rounded-lg border border-slate-200 px-3 py-2"
        />
        <label className="mb-1 block text-sm font-medium text-[#5C685B]">Password</label>
        <input
          data-testid="password"
          type="password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="mb-4 w-full rounded-lg border border-slate-200 px-3 py-2"
        />
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
