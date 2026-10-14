import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { motion } from "framer-motion";
import { accountsApi, apiError, demoApi, escrowApi, isDemoMode } from "../../api";
import type { Account, ContractView } from "../../api";
import NewEscrowTradeModal from "./NewEscrowTradeModal";
import LedgerActivityFeed from "../ledger-activity/LedgerActivityFeed";
import ContractExecutionDrawer from "./ContractExecutionDrawer";
import WebhookSimulatorCard from "./WebhookSimulatorCard";
import ProfileSettingsModal from "../../features/company-profile/ProfileSettingsModal";
import Shell from "../../shared/components/Shell";
import Tooltip from "../../shared/components/Tooltip";
import EmptyTradesCard from "./EmptyTradesCard";
import AnimatedMoney from "../../shared/components/AnimatedMoney";
import { Badge, STATUS_VARIANT } from "../../components/ui/badge";
import { Skeleton } from "../../components/ui/skeleton";
import { useAuth } from "../authentication/auth";
import { money, yieldAccrued } from "../../shared/utilities/currencyFormatter";

type Filter = "all" | "active" | "completed";

const ACTIVE = new Set(["INVITED", "INITIATED", "FUNDED"]);
const TOPUP_AMOUNT = "5000";

export default function Dashboard() {
  const { user } = useAuth();
  const [contracts, setContracts] = useState<ContractView[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [filter, setFilter] = useState<Filter>("all");
  const [showNew, setShowNew] = useState(false);
  const [preset, setPreset] = useState<
    { email?: string; amount?: string } | undefined
  >(undefined);
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [demoEnabled, setDemoEnabled] = useState(false);
  const [topupBusy, setTopupBusy] = useState("");
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
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
        setLoadError("");
      })
      .catch(() => setLoadError("Couldn't load your dashboard"))
      .finally(() => setLoading(false));
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
    // Deep link: /contracts/:id redirects here as ?trade=<id>.
    const trade = params.get("trade");
    if (trade) {
      setSelected(trade);
      params.delete("trade");
      setParams(params, { replace: true });
    }
  }, [params, setParams]);

  const refresh = () => setRefreshKey((k) => k + 1);

  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(""), 6000);
    return () => clearTimeout(t);
  }, [notice]);

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
      {loadError && (
        <div className="mb-4 flex items-center justify-between rounded-lg bg-escrowrail-blocked-bg px-3 py-2 text-sm text-escrowrail-blocked-text">
          {loadError}
          <button onClick={refresh} className="font-semibold underline">
            Retry
          </button>
        </div>
      )}
      {loading ? (
        <>
          <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            {[0, 1].map((i) => (
              <Skeleton key={i} className="h-24 rounded-xl" />
            ))}
          </div>
          <Skeleton className="h-64 rounded-xl" />
        </>
      ) : (
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.25, ease: "easeOut" }}
      >
      {/* Account cards — per-currency balances with sandbox top-up */}
      <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
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
            <AnimatedMoney
              testId={`metric-cash-${cur.toLowerCase()}`}
              value={cash(cur)}
              currency={cur}
              className="mt-1 block text-2xl font-bold text-[#1B1C1D]"
            />
            <p className="text-xs text-[#5C685B]">
              {accounts.find((a) => a.currency === cur)?.account_type ?? "USER"} · available
            </p>
          </div>
        ))}
      </div>

      {notice && (
        <div className="mb-4 rounded-lg bg-[#D1E7DD] px-3 py-2 text-sm text-[#0F5132]">{notice}</div>
      )}

      <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="flex items-center gap-1 text-sm text-[#5C685B]">
            Safeguarded in Escrow
            <Tooltip
              label="About safeguarded funds"
              text="Under Electronic Money Regulations, Escrowrail isolates 100% of escrowed balances from operational assets. Funds can never be lent or leveraged."
            />
          </p>
          <p data-testid="metric-vault" className="num mt-1 text-2xl font-bold text-[#084298]">
            {money(safeguarded.toFixed(2), "EUR")}
          </p>
          <p className="text-xs text-[#5C685B]">Segregated EMI vault</p>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="flex items-center gap-1 text-sm text-[#5C685B]">
            Accrued Float Yield
            <Tooltip
              label="About float yield"
              text="Interest generated on safeguarded EUR balances at the 3.5% ECB annualized base rate, accruing per millisecond while the vault holds funds."
            />
          </p>
          <p data-testid="metric-yield" className="num mt-1 text-2xl font-bold text-[#0F5132]">
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
        <div className="overflow-x-auto">
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
            {rows.map((c, i) => (
              <motion.tr
                key={c.id}
                data-testid={`trade-row-${c.id}`}
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: Math.min(i * 0.04, 0.3), duration: 0.2 }}
                onClick={() => {
                  setSelected(c.id);
                  // Re-read accounts so the drawer sees post-topup balances.
                  refresh();
                }}
                className="cursor-pointer border-b border-brand-border/60 last:border-0 transition-colors hover:bg-brand-sand"
              >
                <td className="px-5 py-3 font-medium text-[#1B1C1D]">
                  {c.role === "buyer" ? c.seller_name : c.buyer_name}
                </td>
                <td className="px-5 py-3">
                  {money(c.target_amount, c.target_currency)}
                </td>
                <td className="px-5 py-3 capitalize text-[#5C685B]">{c.role}</td>
                <td className="px-5 py-3">
                  <Badge variant={STATUS_VARIANT[c.status] ?? "default"}>
                    {c.status}
                  </Badge>
                </td>
              </motion.tr>
            ))}
          </tbody>
        </table>
        </div>
        {rows.length === 0 && (
          <div className="border-t border-brand-border px-5 py-6">
            <EmptyTradesCard
              onNewTrade={() => {
                setPreset(undefined);
                setShowNew(true);
              }}
              onLoadDemo={() => {
                setPreset({ email: "seller@demo.com", amount: "10000.00" });
                setShowNew(true);
              }}
            />
          </div>
        )}
      </div>

      <div className="mt-4 space-y-4">
        {isDemoMode() && demoEnabled && (
          <WebhookSimulatorCard contracts={contracts} onSettled={refresh} />
        )}
        <LedgerActivityFeed accounts={accounts} refreshKey={refreshKey} />
      </div>
      </motion.div>
      )}

      {selected && (
        <ContractExecutionDrawer
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
        <NewEscrowTradeModal
          preset={preset}
          onClose={() => setShowNew(false)}
          onCreated={(c) => {
            setShowNew(false);
            reload();
            // INVITED responses carry no contract id — nothing to deep-link.
            if (c.id) navigate(`/dashboard?trade=${c.id}`);
          }}
        />
      )}
    </Shell>
  );
}
