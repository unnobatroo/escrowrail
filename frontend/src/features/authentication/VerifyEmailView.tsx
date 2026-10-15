import { useCallback, useEffect, useRef, useState } from "react";
import { MailCheck, ShieldCheck } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiError, authApi } from "../../api";
import { useAuth } from "./AuthProvider";

const RESEND_SECONDS = 60;

/** Activation screen: manual token entry, auto-verify on ?token=, 60s resend. */
export default function VerifyEmailView() {
  const { verifyEmail, user } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const email = params.get("email") ?? "";
  const urlToken = params.get("token") ?? "";

  const [token, setToken] = useState(urlToken);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [cooldown, setCooldown] = useState(0);
  const autoDispatched = useRef(false);

  useEffect(() => {
    if (user?.email_verified) navigate("/dashboard", { replace: true });
  }, [user, navigate]);

  const submit = useCallback(
    async (value: string) => {
      if (!value.trim()) return;
      setBusy(true);
      setError("");
      try {
        await verifyEmail(value.trim());
        navigate("/dashboard", { replace: true });
      } catch (err) {
        setError(
          apiError(err).message ??
            "Verification link is invalid or expired — request a new one below.",
        );
      } finally {
        setBusy(false);
      }
    },
    [verifyEmail, navigate],
  );

  // Auto-dispatch when the email link lands with ?token=...
  useEffect(() => {
    if (urlToken && !autoDispatched.current) {
      autoDispatched.current = true;
      submit(urlToken);
    }
  }, [urlToken, submit]);

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);

  const resend = async () => {
    setNotice("");
    setError("");
    try {
      await authApi.resendVerification(email);
      setNotice("If that address is registered, a new link is on its way.");
      setCooldown(RESEND_SECONDS);
    } catch {
      setNotice("If that address is registered, a new link is on its way.");
      setCooldown(RESEND_SECONDS);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#163300]">
      <div className="w-full max-w-md rounded-xl bg-white p-8 shadow-lg">
        <div className="mb-6 flex items-center gap-2 text-[#163300]">
          <ShieldCheck size={28} className="text-[#9FE870]" />
          <h1 className="text-xl font-bold">Verify your email address</h1>
        </div>
        <p className="mb-4 text-sm text-[#5C685B]">
          We sent a secure activation link to{" "}
          <strong data-testid="verify-email">{email || "your inbox"}</strong>.
          Enter the verification code below or click the link in your email to
          activate your account.
        </p>

        {busy && urlToken ? (
          <p
            data-testid="verify-loading"
            className="py-4 text-center text-sm text-[#5C685B]"
          >
            Verifying your account…
          </p>
        ) : (
          <>
            <input
              data-testid="verify-token"
              type="text"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="Paste verification token"
              className="mb-3 w-full rounded-lg border border-slate-200 px-3 py-2 font-mono text-sm"
            />
            <button
              data-testid="verify-submit"
              onClick={() => submit(token)}
              disabled={busy || !token.trim()}
              className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
            >
              <MailCheck size={16} className="mr-1 inline" /> Verify & Enter
              Platform
            </button>
          </>
        )}

        {error && (
          <div
            data-testid="verify-error"
            className="mt-4 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
          >
            {error}
          </div>
        )}
        {notice && (
          <div
            data-testid="verify-notice"
            className="mt-4 rounded-lg bg-[#D1E7DD] px-3 py-2 text-sm text-[#0F5132]"
          >
            {notice}
          </div>
        )}

        <div className="mt-4 flex items-center justify-between text-sm">
          <button
            data-testid="resend"
            onClick={resend}
            disabled={cooldown > 0 || !email}
            className="font-medium text-[#163300] underline disabled:text-[#5C685B] disabled:no-underline"
          >
            {cooldown > 0 ? `Resend email (${cooldown}s)` : "Resend email"}
          </button>
          <Link to="/login" className="text-[#5C685B] underline">
            Back to sign in
          </Link>
        </div>
      </div>
    </div>
  );
}
