import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { motion } from "framer-motion";
import { accountsApi, apiError, demoApi, escrowApi, isDemoMode } from "../../../api";
import type { Account, ContractView } from "../../../api";
import NewEscrowTradeModal from "../create/NewEscrowTradeModal";
import LedgerActivityFeed from "../../ledger-activity/LedgerActivityFeed";
import ContractExecutionDrawer from "../drawer/ContractExecutionDrawer";
import WebhookSimulatorCard from "../drawer/WebhookSimulatorCard";
import ProfileSettingsModal from "../../company-profile/ProfileSettingsModal";
import Shell from "../../../shared/components/Shell";
import AccountCards from "./AccountCards";
import InsightTiles from "./InsightTiles";
import TradesTable, { type TradeFilter } from "./TradesTable";
import { Skeleton } from "../../../components/ui/skeleton";
import { useAuth } from "../../authentication/AuthProvider";
import { money, sumMoney, yieldAccrued } from "../../../shared/utilities/currencyFormatter";

const ACTIVE = new Set(["INVITED", "INITIATED", "FUNDED"]);
const TOPUP_AMOUNT = "5000";

export default function Dashboard() {
  const { user } = useAuth();
  const [contracts, setContracts] = useState<ContractView[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [filter, setFilter] = useState<TradeFilter>("all");
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
  // Live dashboard: poll while the tab is visible — settlement is
  // webhook-driven, so this is how trades flip to SETTLED in the UI.
  useEffect(() => {
    const t = setInterval(() => {
      if (document.visibilityState === "visible") reload();
    }, 5000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    if (!isDemoMode()) return;
    demoApi.config().then((r) => setDemoEnabled(r.data.enabled)).catch(() => {});
  }, []);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
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

  // EUR-only: the tiles quote a single currency, and ECB yield is EUR float.
  const safeguarded = useMemo(
    () =>
      sumMoney(
        contracts
          .filter((c) => c.status === "FUNDED" && c.target_currency === "EUR")
          .map((c) => c.escrowed_amount),
      ),
    [contracts],
  );
  const accruedYield = useMemo(
    () =>
      contracts
        .filter(
          (c) =>
            c.status === "FUNDED" && c.funded_at && c.target_currency === "EUR",
        )
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

  const sourceBalance = (contract?: ContractView | null) =>
    accounts.find(
      (a) => a.currency === (contract?.source_currency ?? "USD"),
    )?.balance;

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
        <div className="mb-4 flex items-center justify-between rounded-lg bg-escrow-blocked-bg px-3 py-2 text-sm text-escrow-blocked-text">
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
      <AccountCards
        accounts={accounts}
        demoEnabled={demoEnabled}
        topupBusy={topupBusy}
        onTopUp={topUp}
      />

      {notice && (
        <div className="mb-4 rounded-lg bg-[#D1E7DD] px-3 py-2 text-sm text-[#0F5132]">{notice}</div>
      )}

      <InsightTiles
        safeguarded={safeguarded}
        accruedYield={accruedYield.toFixed(4)}
      />

      <TradesTable
        rows={rows}
        filter={filter}
        onFilter={setFilter}
        onSelect={(id) => {
          setSelected(id);
          // Re-read accounts so the drawer sees post-topup balances.
          refresh();
        }}
        onNewTrade={() => {
          setPreset(undefined);
          setShowNew(true);
        }}
        onLoadDemo={() => {
          setPreset({ email: "seller@demo.com", amount: "10000.00" });
          setShowNew(true);
        }}
      />

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
