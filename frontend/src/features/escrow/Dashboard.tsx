import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { accountsApi, apiError, demoApi, escrowApi, isDemoMode } from "../../api";
import type { Account, ContractView } from "../../api";
import EscrowCreationModal from "./EscrowCreationModal";
import LedgerActivityFeed from "../ledger/LedgerActivityFeed";
import ContractLifecycleDrawer from "./ContractLifecycleDrawer";
import WebhookSimulatorCard from "./WebhookSimulatorCard";
import ProfileSettingsModal from "../../shared/ProfileSettingsModal";
import Shell from "../../shared/Shell";
import { useAuth } from "../auth/auth";
import { money, yieldAccrued } from "../../shared/currencyFormatter";

type Filter = "all" | "active" | "completed";

const STATUS_STYLE: Record<string, string> = {
  INITIATED: "bg-[#FFF3CD] text-[#7A5800]",
  FUNDED: "bg-[#CFF4FC] text-[#084298]",
  SETTLED: "bg-[#D1E7DD] text-[#0F5132]",
  CANCELLED: "bg-[#E2E3E5] text-[#41464B]",
};

const ACTIVE = new Set(["INITIATED", "FUNDED"]);
const TOPUP_AMOUNT = "5000";

export default function Dashboard() {
  const { user } = useAuth();
  const [contracts, setContracts] = useState<ContractView[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [filter, setFilter] = useState<Filter>("all");
  const [showNew, setShowNew] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [demoEnabled, setDemoEnabled] = useState(false);
  const [topupBusy, setTopupBusy] = useState("");
  const [notice, setNotice] = useState("");
  // First visit after activation: the onboarding setup window is open
  // until the profile is saved once.
  const [onboardingOpen, setOnboardingOpen] = useState(false);
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();

  const reload = () => {
    Promise.all([escrowApi.list(), accountsApi.list()])
      .then(([c, a]) => {
        setContracts(c.data);
        setAccounts(a.data);
      })
      .catch(() => {});
  };

  useEffect(reload, [refreshKey]);
  useEffect(() => {
    if (!isDemoMode()) return;
    demoApi.config().then((r) => setDemoEnabled(r.data.enabled)).catch(() => {});
  }, []);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 100);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (params.get("new")) {
      setShowNew(true);
      params.delete("new");
      setParams(params, { replace: true });
    }
  }, [params, setParams]);

  const refresh = () => setRefreshKey((k) => k + 1);

  const topUp = async (currency: string) => {
    setTopupBusy(currency);
    setNotice("");
    try {
      await demoApi.topup(currency, TOPUP_AMOUNT);
      setNotice(`Sandbox credit applied: +${money(TOPUP_AMOUNT, currency)}`);
      refresh();
    } catch (err) {
      setNotice(apiError(err).message ?? "Top-up failed");
    } finally {
      setTopupBusy("");
    }
  };

  useEffect(() => {
    if (user && !user.onboarding_completed) setOnboardingOpen(true);
  }, [user]);

  const cash = (cur: string) =>
    accounts.find((a) => a.currency === cur)?.balance ?? "0.00";

  const sourceBalance = (contract?: ContractView | null) =>
    accounts.find(
      (a) => a.currency === (contract?.source_currency ?? "USD"),
    )?.balance;

  const safeguarded = useMemo(
    () =>
      contracts
        .filter((c) => c.status === "FUNDED")
        .reduce((s, c) => s + Number(c.escrowed_amount ?? 0), 0),
    [contracts],
  );
  const accruedYield = useMemo(
    () =>
      contracts
        .filter((c) => c.status === "FUNDED" && c.funded_at)
        .reduce((s, c) => s + yieldAccrued(c.escrowed_amount!, c.funded_at!, now), 0),
    [contracts, now],
  );

  const rows = contracts.filter((c) =>
    filter === "all"
      ? true
      : filter === "active"
        ? ACTIVE.has(c.status)
        : !ACTIVE.has(c.status),
  );

  return (
    <Shell>
      <p
        data-testid="greeting"
        className="mb-4 text-lg font-semibold text-[#1B1C1D]"
      >
        Welcome back, {user?.entity.legal_name ?? "there"}. Here is where
        your money is working today.
      </p>
      {/* Account cards — per-currency balances with sandbox top-up */}
      <div className="mb-4 grid grid-cols-2 gap-4">
        {(["USD", "EUR"] as const).map((cur) => (
          <div key={cur} className="rounded-xl border border-slate-200 bg-white p-5">
            <div className="flex items-center justify-between">
              <p className="text-sm text-[#5C685B]">{cur} Account</p>
              {demoEnabled && (
                <button
                  data-testid={`topup-${cur.toLowerCase()}`}
                  onClick={() => topUp(cur)}
                  disabled={topupBusy === cur}
                  className="rounded-full border border-[#163300] px-3 py-1 text-xs font-semibold text-[#163300] hover:bg-[#F4F5F0] disabled:opacity-40"
                >
                  + Add Funds
                </button>
              )}
            </div>
            <p
              data-testid={`metric-cash-${cur.toLowerCase()}`}
              className="mt-1 text-2xl font-bold text-[#1B1C1D]"
            >
              {money(cash(cur), cur)}
            </p>
            <p className="text-xs text-[#5C685B]">
              {accounts.find((a) => a.currency === cur)?.account_type ?? "USER"} · available
            </p>
          </div>
        ))}
      </div>

      {notice && (
        <div className="mb-4 rounded-lg bg-[#D1E7DD] px-3 py-2 text-sm text-[#0F5132]">{notice}</div>
      )}

      <div className="mb-4 grid grid-cols-2 gap-4">
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="text-sm text-[#5C685B]">Safeguarded in Escrow</p>
          <p data-testid="metric-vault" className="mt-1 text-2xl font-bold text-[#084298]">
            {money(safeguarded.toFixed(2), "EUR")}
          </p>
          <p className="text-xs text-[#5C685B]">Segregated EMI vault</p>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="text-sm text-[#5C685B]">Accrued Float Yield</p>
          <p data-testid="metric-yield" className="mt-1 text-2xl font-bold text-[#0F5132]">
            {money(accruedYield.toFixed(4), "EUR")}
          </p>
          <p className="text-xs text-[#5C685B]">3.5% ECB base rate</p>
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white">
        <div className="flex items-center gap-2 border-b border-slate-200 px-5 py-3">
          <h2 className="mr-4 font-semibold text-[#1B1C1D]">My Trades</h2>
          {(["all", "active", "completed"] as Filter[]).map((f) => (
            <button
              key={f}
              data-testid={`tab-${f}`}
              onClick={() => setFilter(f)}
              className={`rounded-full px-3 py-1 text-sm capitalize ${
                filter === f
                  ? "bg-[#163300] text-white"
                  : "text-[#5C685B] hover:bg-slate-100"
              }`}
            >
              {f}
            </button>
          ))}
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-[#5C685B]">
              <th className="px-5 py-2 font-medium">Counterparty</th>
              <th className="px-5 py-2 font-medium">Invoice</th>
              <th className="px-5 py-2 font-medium">Role</th>
              <th className="px-5 py-2 font-medium">Status</th>
            </tr>
          </thead>
          <tbody data-testid="trades-body">
            {rows.map((c) => (
              <tr
                key={c.id}
                data-testid={`trade-row-${c.id}`}
                onClick={() => {
                  setSelected(c.id);
                  // Re-read accounts so the drawer sees post-topup balances.
                  refresh();
                }}
                className="cursor-pointer border-b border-slate-100 last:border-0 hover:bg-slate-50"
              >
                <td className="px-5 py-3 font-medium text-[#1B1C1D]">
                  {c.role === "buyer" ? c.seller_name : c.buyer_name}
                </td>
                <td className="px-5 py-3">
                  {money(c.target_amount, c.target_currency)}
                </td>
                <td className="px-5 py-3 capitalize text-[#5C685B]">{c.role}</td>
                <td className="px-5 py-3">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[c.status]}`}
                  >
                    {c.status}
                  </span>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={4} className="px-5 py-8 text-center text-[#5C685B]">
                  No trades yet — start one with “New Escrow Trade”.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="mt-4 space-y-4">
        {isDemoMode() && demoEnabled && (
          <WebhookSimulatorCard contracts={contracts} onSettled={refresh} />
        )}
        <LedgerActivityFeed accounts={accounts} refreshKey={refreshKey} />
      </div>

      {selected && (
        <ContractLifecycleDrawer
          contractId={selected}
          sourceBalance={sourceBalance(
            contracts.find((c) => c.id === selected),
          )}
          onClose={() => setSelected(null)}
          onChanged={refresh}
        />
      )}

      {onboardingOpen && (
        <ProfileSettingsModal
          onClose={() => {
            setOnboardingOpen(false);
            // The onboarding deposit lands immediately — refresh balances.
            refresh();
          }}
        />
      )}

      {showNew && (
        <EscrowCreationModal
          onClose={() => setShowNew(false)}
          onCreated={(c) => {
            setShowNew(false);
            reload();
            navigate(`/contracts/${c.id}`);
          }}
        />
      )}
    </Shell>
  );
}
