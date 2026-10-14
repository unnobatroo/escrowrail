import { useEffect, useState } from "react";
import { LogOut, Plus, Settings, ShieldCheck, ArrowLeftRight, Sparkles } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { accountsApi, demoApi, isDemoMode } from "../../api";
import type { Account, DemoConfig } from "../../api";
import { useAuth } from "../../features/authentication/auth";
import { money } from "../utilities/currencyFormatter";
import ProfileSettingsModal from "../../features/company-profile/ProfileSettingsModal";
import HowItWorksGuideModal from "./HowItWorksGuideModal";

export default function Shell({ children }: { children: React.ReactNode }) {
  const { user, login, logout } = useAuth();
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [demo, setDemo] = useState<DemoConfig | null>(null);
  const [switching, setSwitching] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const navigate = useNavigate();

  const loadAccounts = () =>
    accountsApi
      .list()
      .then((r) => setAccounts(r.data))
      .catch(() => {});

  useEffect(() => {
    loadAccounts();
    // Re-fetch when the session user changes (login, profile save) so the
    // balance pills reflect onboarding deposits and settings top-ups.
  }, [user]);
  useEffect(() => {
    if (isDemoMode()) {
      demoApi
        .config()
        .then((r) => setDemo(r.data))
        .catch(() => {});
    }
  }, []);

  const otherDemoEmail =
    demo && user
      ? user.email === demo.buyer_email
        ? demo.seller_email
        : user.email === demo.seller_email
          ? demo.buyer_email
          : null
      : null;

  const switchAccount = async () => {
    if (!demo || !otherDemoEmail) return;
    setSwitching(true);
    try {
      await login(
        otherDemoEmail,
        otherDemoEmail === demo.buyer_email
          ? demo.buyer_password
          : demo.seller_password,
      );
      navigate("/dashboard");
      window.location.reload();
    } finally {
      setSwitching(false);
    }
  };

  const balance = (cur: string) =>
    accounts.find((a) => a.currency === cur)?.balance ?? "0.00";

  return (
    <div className="min-h-screen bg-[#F4F5F0]">
      <nav className="bg-[#163300] text-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-6 py-3">
          <Link to="/dashboard" className="flex items-center gap-2 font-semibold">
            <ShieldCheck size={20} className="text-[#9FE870]" />
            Wise Smart Escrow
          </Link>
          <span className="hidden text-sm text-white/80 md:inline">
            {user?.entity.legal_name}
          </span>
          <span className="hidden rounded-full bg-white/10 px-2 py-0.5 font-mono text-xs text-[#9FE870] sm:inline">
            {user?.entity.ubo_tax_id}
          </span>
          <div className="ml-auto flex items-center gap-3">
            <span
              data-testid="balance-usd"
              className="rounded-full bg-white/10 px-3 py-1 text-sm font-medium"
            >
              {money(balance("USD"), "USD")}
            </span>
            <span
              data-testid="balance-eur"
              className="rounded-full bg-white/10 px-3 py-1 text-sm font-medium"
            >
              {money(balance("EUR"), "EUR")}
            </span>
            {otherDemoEmail && (
              <button
                data-testid="demo-switch"
                onClick={switchAccount}
                disabled={switching}
                className="flex items-center gap-1 rounded-full border border-[#9FE870] px-3 py-1.5 text-sm font-semibold text-[#9FE870] hover:bg-white/10 disabled:opacity-50"
                title={`Switch to ${otherDemoEmail}`}
              >
                <ArrowLeftRight size={14} />
                {otherDemoEmail === demo?.seller_email ? "Switch to Seller" : "Switch to Buyer"}
              </button>
            )}
            <button
              data-testid="how-it-works"
              onClick={() => setGuideOpen(true)}
              className="flex items-center gap-1 rounded-full border border-white/30 px-3 py-1.5 text-sm font-semibold text-white/90 hover:bg-white/10"
            >
              <Sparkles size={14} /> How it works
            </button>
            <button
              data-testid="new-trade"
              onClick={() => navigate("/dashboard?new=1")}
              className="flex items-center gap-1 rounded-full bg-[#9FE870] px-3 py-1.5 text-sm font-semibold text-[#163300] hover:bg-[#8fd960]"
            >
              <Plus size={16} /> New Escrow Trade
            </button>
            <button
              data-testid="profile-settings"
              onClick={() => setSettingsOpen(true)}
              className="flex items-center justify-center rounded-full bg-white/10 p-1.5 text-white/80 hover:bg-white/20 hover:text-white"
              title="Profile settings"
              aria-label="Profile settings"
            >
              <Settings size={18} />
            </button>
            <button
              data-testid="logout"
              onClick={() => {
                logout();
                navigate("/login");
              }}
              className="rounded-full p-1.5 text-white/70 hover:bg-white/10 hover:text-white"
              title="Log out"
            >
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </nav>
      <main className="mx-auto max-w-6xl px-6 py-6">{children}</main>
      {guideOpen && <HowItWorksGuideModal onClose={() => setGuideOpen(false)} />}
      {settingsOpen && (
        <ProfileSettingsModal
          onClose={() => {
            setSettingsOpen(false);
            loadAccounts();
          }}
        />
      )}
    </div>
  );
}
