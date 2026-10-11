import { useEffect, useState } from "react";
import { LogOut, Plus, ShieldCheck } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { accountsApi } from "../api";
import type { Account } from "../api";
import { useAuth } from "../auth";
import { money } from "../format";

export default function Shell({ children }: { children: React.ReactNode }) {
  const { user, logout } = useAuth();
  const [accounts, setAccounts] = useState<Account[]>([]);
  const navigate = useNavigate();

  useEffect(() => {
    accountsApi
      .list()
      .then((r) => setAccounts(r.data))
      .catch(() => {});
  }, []);

  const balance = (cur: string) =>
    accounts.find((a) => a.currency === cur)?.balance ?? "0.00";

  return (
    <div className="min-h-screen bg-[#F4F5F7]">
      <nav className="bg-[#163300] text-white">
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-6 py-3">
          <Link to="/dashboard" className="flex items-center gap-2 font-semibold">
            <ShieldCheck size={20} className="text-[#9FE870]" />
            Escrowrail
          </Link>
          <span className="text-sm text-white/80">{user?.entity.legal_name}</span>
          <span className="rounded-full bg-white/10 px-2 py-0.5 font-mono text-xs text-[#9FE870]">
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
            <button
              data-testid="new-trade"
              onClick={() => navigate("/dashboard?new=1")}
              className="flex items-center gap-1 rounded-full bg-[#9FE870] px-3 py-1.5 text-sm font-semibold text-[#163300] hover:bg-[#8fd960]"
            >
              <Plus size={16} /> New Escrow Trade
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
    </div>
  );
}
