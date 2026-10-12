import { useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiError, authApi } from "../../api";

/** Consume a reset token: new password + confirm, then back to sign in. */
export default function ResetPasswordView() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const valid = password.length >= 8 && password === confirm && token !== "";

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    setBusy(true);
    setError("");
    try {
      await authApi.resetPassword(token, password);
      setDone(true);
      setTimeout(() => navigate("/login"), 1500);
    } catch (err) {
      setError(
        apiError(err).message ??
          "Reset link is invalid or expired — request a new one.",
      );
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
          <h1 className="text-xl font-bold">Choose a new password</h1>
        </div>
        {done ? (
          <p data-testid="reset-done" className="text-sm text-[#0F5132]">
            Password updated — taking you to sign in…
          </p>
        ) : (
          <>
            {!token && (
              <div
                data-testid="reset-no-token"
                className="mb-4 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
              >
                This link is missing its reset token — use the link from your
                email.
              </div>
            )}
            <label className="mb-1 block text-sm font-medium text-[#5C685B]">
              New password (8+ chars)
            </label>
            <input
              data-testid="new-password"
              type="password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mb-4 w-full rounded-lg border border-slate-200 px-3 py-2"
            />
            <label className="mb-1 block text-sm font-medium text-[#5C685B]">
              Confirm password
            </label>
            <input
              data-testid="confirm-password"
              type="password"
              required
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              className="mb-4 w-full rounded-lg border border-slate-200 px-3 py-2"
            />
            {confirm && password !== confirm && (
              <p
                data-testid="reset-mismatch"
                className="mb-3 text-xs text-[#BA1A1A]"
              >
                Passwords do not match.
              </p>
            )}
            {error && (
              <div
                data-testid="reset-error"
                className="mb-4 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
              >
                {error}
              </div>
            )}
            <button
              data-testid="reset-submit"
              type="submit"
              disabled={busy || !valid}
              className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
            >
              {busy ? "Updating…" : "Update password"}
            </button>
          </>
        )}
        <p className="mt-4 text-center text-sm text-[#5C685B]">
          <Link to="/login" className="font-medium text-[#163300] underline">
            Back to sign in
          </Link>
        </p>
      </form>
    </div>
  );
}
