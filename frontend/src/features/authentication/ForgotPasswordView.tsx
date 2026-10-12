import { useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Link } from "react-router-dom";
import { authApi } from "../../api";

/** Request a password reset link — always shows success (anti-enumeration). */
export default function ForgotPasswordView() {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await authApi.forgotPassword(email);
    } catch {
      // Response shape is identical either way — never reveal membership.
    } finally {
      setSent(true);
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
          <h1 className="text-xl font-bold">Reset your password</h1>
        </div>
        {sent ? (
          <p data-testid="reset-sent" className="mb-4 text-sm text-[#5C685B]">
            If <strong>{email}</strong> is registered, a reset link is on its
            way. The link is valid for one hour.
          </p>
        ) : (
          <>
            <label className="mb-1 block text-sm font-medium text-[#5C685B]">
              Email
            </label>
            <input
              data-testid="forgot-email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mb-4 w-full rounded-lg border border-slate-200 px-3 py-2"
            />
            <button
              data-testid="forgot-submit"
              type="submit"
              disabled={busy}
              className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
            >
              {busy ? "Sending…" : "Send reset link"}
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
