import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  GitMerge,
  Landmark,
  Loader2,
  PackageCheck,
  ShieldCheck,
  XCircle,
} from "lucide-react";
import axios from "axios";
import {
  cancelContract,
  createContract,
  fundContract,
  getContract,
  getContractLedger,
  getEntities,
  getEntityAccounts,
  getReady,
  githubWebhook,
  logisticsWebhook,
  type Account,
  type Entity,
  type LedgerEntry,
  type EscrowContract,
} from "./api";

// ---- demo constants (mirrors seed_demo_data.py) ----
const EUR_USD_SPOT = 1.08; // static fixture rate: 1 EUR = 1.08 USD
const FX_FEE_RATE = 0.005; // 0.5% upfront conversion fee
const ECB_BASE_RATE = 0.035; // 3.5% float yield

const MILESTONES = [
  { type: "GITHUB_PR", key: "unnobatroo/escrowrail#1", label: "GitHub PR — unnobatroo/escrowrail#1" },
  { type: "SHIPPING_CARRIER", key: "DHL-987654", label: "DHL Delivery — DHL-987654" },
] as const;

interface LogEntry {
  ts: string;
  kind: "info" | "ok" | "err" | "webhook";
  text: string;
}

const money = (n: number | string, ccy = "USD") =>
  `${Number(n).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${ccy}`;

function Panel({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <h2 className="flex items-center gap-2 text-lg font-semibold text-escrowrail-forest">
        {icon}
        {title}
      </h2>
      {children}
    </section>
  );
}

function Badge({ tone, children }: { tone: "settled" | "vault" | "blocked" | "neutral"; children: React.ReactNode }) {
  const cls =
    tone === "settled"
      ? "bg-escrowrail-settled-bg text-escrowrail-settled-text"
      : tone === "vault"
        ? "bg-escrowrail-vault-bg text-escrowrail-vault-text"
        : tone === "blocked"
          ? "bg-escrowrail-blocked-bg text-escrowrail-blocked-text"
          : "bg-slate-200 text-escrowrail-subtext";
  return <span className={`inline-block rounded-full px-3 py-1 text-xs font-semibold ${cls}`}>{children}</span>;
}

export default function App() {
  const [ready, setReady] = useState<boolean | null>(null);
  const [entities, setEntities] = useState<Entity[]>([]);
  const [buyerId, setBuyerId] = useState("");
  const [sellerId, setSellerId] = useState("");
  const [amount, setAmount] = useState("10000.00");
  const [milestone, setMilestone] = useState(0);
  const [contract, setContract] = useState<EscrowContract | null>(null);
  const [buyerAccount, setBuyerAccount] = useState<Account | null>(null);
  const [sellerAccount, setSellerAccount] = useState<Account | null>(null);
  const [busy, setBusy] = useState(false);
  const [showCancelModal, setShowCancelModal] = useState(false);
  const [fundedAt, setFundedAt] = useState<number | null>(null);
  const [ledger, setLedger] = useState<LedgerEntry[] | null>(null);
  const [yieldAccrued, setYieldAccrued] = useState(0);
  const [log, setLog] = useState<LogEntry[]>([]);
  const logEndRef = useRef<HTMLDivElement>(null);

  const addLog = useCallback((kind: LogEntry["kind"], text: string) => {
    setLog((prev) => [...prev, { ts: new Date().toLocaleTimeString(), kind, text }]);
  }, []);

  // ---- walking skeleton: health badge + entity bootstrap ----
  useEffect(() => {
    // Restore an in-flight contract after a reload — the backend holds it.
    const savedId = localStorage.getItem("escrowContractId");
    if (savedId) {
      getContract(savedId)
        .then(async (r) => {
          setContract(r.data);
          addLog("info", `restored contract ${savedId.slice(0, 8)} (${r.data.status})`);
          if (r.data.status === "FUNDED") {
            const legs = await getContractLedger(savedId).catch(() => null);
            const vaultLeg = legs?.data.find((l) =>
              l.description.includes("safeguarded vault"),
            );
            // Funded timestamp is not on the contract — recover elapsed
            // time from the vault ledger leg so the ticker stays honest.
            setFundedAt(
              vaultLeg ? new Date(vaultLeg.created_at).getTime() : Date.now(),
            );
          } else if (r.data.status === "SETTLED") {
            const legs = await getContractLedger(savedId).catch(() => null);
            if (legs) setLedger(legs.data);
          }
        })
        .catch(() => localStorage.removeItem("escrowContractId"));
    }
    getReady()
      .then((r) => {
        setReady(r.data.ledger_balanced);
        addLog("ok", `backend ready — ledger_balanced=${r.data.ledger_balanced}`);
      })
      .catch(() => {
        setReady(false);
        addLog("err", "backend unreachable — is uvicorn running on :8000?");
      });
    getEntities()
      .then((r) => {
        setEntities(r.data);
        const buyer = r.data.find((e) => e.legal_name === "Acme Hardware US");
        const seller = r.data.find((e) => e.legal_name === "Berlin Robotics GmbH");
        if (buyer) setBuyerId(buyer.id);
        if (seller) setSellerId(seller.id);
        addLog("info", `loaded ${r.data.length} counterparties`);
      })
      .catch(() => addLog("err", "failed to load entities — run `make seed`"));
  }, [addLog]);

  // ---- derived state ----
  const buyer = entities.find((e) => e.id === buyerId);
  const seller = entities.find((e) => e.id === sellerId);
  const uboCollision = !!buyer && !!seller && buyer.ubo_tax_id === seller.ubo_tax_id;
  const amountNum = parseFloat(amount) || 0;
  // backend fee semantics: fee = deposit * 0.5%, net must cover amount*rate
  const grossDeposit = Math.round(((amountNum * EUR_USD_SPOT) / (1 - FX_FEE_RATE)) * 100) / 100;
  const fxFee = Math.round(grossDeposit * FX_FEE_RATE * 100) / 100;
  const principal = amountNum * EUR_USD_SPOT;
  const milestoneConf = MILESTONES[milestone];

  const escrowed = contract?.escrowed_amount ? Number(contract.escrowed_amount) : null;
  const settleFee = ledger
    ? Number(ledger.find((l) => l.description.includes("escrow software fee"))?.amount ?? "0")
    : null;
  const sellerNet = ledger
    ? Number(ledger.find((l) => l.description.includes("release to seller"))?.amount ?? "0")
    : null;

  // ---- account lookups when parties change ----
  const refreshAccounts = useCallback(async () => {
    if (buyerId) {
      const accs = await getEntityAccounts(buyerId).then((r) => r.data).catch(() => []);
      // Funding submits source_currency: "USD" — never substitute another
      // currency's account or the deposit would draw from the wrong pot.
      setBuyerAccount(accs.find((a) => a.currency === "USD") ?? null);
    }
    if (sellerId) {
      const accs = await getEntityAccounts(sellerId).then((r) => r.data).catch(() => []);
      setSellerAccount(accs.find((a) => a.currency === "EUR") ?? null);
    }
  }, [buyerId, sellerId]);

  useEffect(() => {
    void refreshAccounts();
  }, [refreshAccounts]);

  // ---- float yield ticker (display-only counter, no state mutation) ----
  useEffect(() => {
    if (!fundedAt || !escrowed || contract?.status !== "FUNDED") return;
    const tick = () => {
      const elapsed = (Date.now() - fundedAt) / 1000;
      setYieldAccrued((escrowed * ECB_BASE_RATE * elapsed) / (365 * 86400));
    };
    tick();
    const id = setInterval(tick, 100);
    return () => clearInterval(id);
  }, [fundedAt, escrowed, contract?.status]);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [log]);

  // ---- actions ----
  const resetForNew = () => {
    localStorage.removeItem("escrowContractId");
    setContract(null);
    setFundedAt(null);
    setYieldAccrued(0);
    setLedger(null);
  };

  const handleCreateAndFund = async () => {
    if (!buyer || !seller || uboCollision || !buyerAccount) return;
    setBusy(true);
    try {
      const c = await createContract({
        buyer_id: buyer.id,
        seller_id: seller.id,
        target_currency: "EUR",
        target_amount: amountNum.toFixed(2),
        source_currency: "USD",
        tracking_type: milestoneConf.type,
        tracking_key: milestoneConf.key,
      });
      setContract(c.data);
      localStorage.setItem("escrowContractId", c.data.id);
      addLog("ok", `contract ${c.data.id.slice(0, 8)} created — ${milestoneConf.type}:${milestoneConf.key}`);

      const funded = await fundContract(c.data.id, {
        account_id: buyerAccount.id,
        deposit_amount: grossDeposit.toFixed(2),
      });
      setContract(funded.data);
      setFundedAt(Date.now());
      addLog(
        "ok",
        `funded ${money(grossDeposit)} → escrowed ${money(funded.data.escrowed_amount ?? "0", "EUR")} @ ${funded.data.locked_spot_rate} USD/EUR`,
      );
      void refreshAccounts();
    } catch (e) {
      const msg = axios.isAxiosError(e) ? JSON.stringify(e.response?.data ?? e.message) : String(e);
      addLog("err", `create/fund failed: ${msg}`);
    } finally {
      setBusy(false);
    }
  };

  const handleCancel = async () => {
    if (!contract) return;
    setShowCancelModal(false);
    setBusy(true);
    try {
      const c = await cancelContract(contract.id);
      setContract(c.data);
      addLog("err", `contract cancelled — funds liquidated to originating USD account`);
      void refreshAccounts();
    } catch (e) {
      addLog("err", `cancel failed: ${axios.isAxiosError(e) ? JSON.stringify(e.response?.data) : e}`);
    } finally {
      setBusy(false);
    }
  };

  const refreshAfterSettle = async (contractId: string) => {
    const fresh = await getContract(contractId);
    setContract(fresh.data);
    if (fresh.data.status === "SETTLED") {
      const legs = await getContractLedger(contractId);
      setLedger(legs.data);
    }
    void refreshAccounts();
  };

  const fireGithub = async () => {
    if (!contract) return;
    const payload = { action: "closed", pull_request: { merged: true, html_url: contract.tracking_key } };
    addLog("webhook", `→ POST /webhooks/github ${JSON.stringify(payload)}`);
    try {
      const r = await githubWebhook(payload);
      addLog("webhook", `← ${JSON.stringify(r.data)} (dedup key sha256(payload))`);
      await refreshAfterSettle(contract.id);
    } catch (e) {
      addLog("err", `github webhook failed: ${axios.isAxiosError(e) ? JSON.stringify(e.response?.data) : e}`);
    }
  };

  const fireDhl = async () => {
    if (!contract) return;
    const payload = { carrier: "DHL", status: "DELIVERED", tracking_key: contract.tracking_key };
    addLog("webhook", `→ POST /webhooks/logistics ${JSON.stringify(payload)}`);
    try {
      const r = await logisticsWebhook(payload);
      addLog("webhook", `← ${JSON.stringify(r.data)}`);
      await refreshAfterSettle(contract.id);
    } catch (e) {
      addLog("err", `logistics webhook failed: ${axios.isAxiosError(e) ? JSON.stringify(e.response?.data) : e}`);
    }
  };

  const statusBadge = useMemo(() => {
    if (!contract) return <Badge tone="neutral">NO CONTRACT</Badge>;
    const map = {
      INITIATED: <Badge tone="neutral">INITIATED</Badge>,
      FUNDED: <Badge tone="vault">FUNDED — FUNDS SAFEGUARDED</Badge>,
      SETTLED: <Badge tone="settled">SETTLED</Badge>,
      CANCELLED: <Badge tone="blocked">CANCELLED</Badge>,
    };
    return map[contract.status];
  }, [contract]);

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* header */}
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-escrowrail-forest">Escrowrail</h1>
          <p className="text-sm text-escrowrail-subtext">Conditional B2B settlement on EMI rails</p>
        </div>
        <div className="flex items-center gap-3">
          {statusBadge}
          <Badge tone={ready === null ? "neutral" : ready ? "settled" : "blocked"}>
            {ready === null ? "CONNECTING…" : ready ? "BACKEND HEALTHY" : "BACKEND DOWN"}
          </Badge>
          {buyerAccount && (
            <span className="text-xs text-escrowrail-subtext">
              Buyer: <strong>{money(buyerAccount.balance)}</strong>
            </span>
          )}
          {sellerAccount && (
            <span className="text-xs text-escrowrail-subtext">
              Seller: <strong>{money(sellerAccount.balance, "EUR")}</strong>
            </span>
          )}
        </div>
      </header>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        {/* ============ PANEL 1: Initiation + UBO guard ============ */}
        <Panel title="Escrow Initiation & AML Guard" icon={<Landmark className="h-5 w-5" />}>
          <label className="flex flex-col gap-1 text-sm font-medium">
            Buyer
            <select
              data-testid="buyer-select"
              className="rounded-md border border-slate-300 p-2"
              value={buyerId}
              onChange={(e) => setBuyerId(e.target.value)}
            >
              {entities.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.legal_name} (UBO: {e.ubo_tax_id})
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm font-medium">
            Seller
            <select
              data-testid="seller-select"
              className="rounded-md border border-slate-300 p-2"
              value={sellerId}
              onChange={(e) => setSellerId(e.target.value)}
            >
              {entities.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.legal_name} (UBO: {e.ubo_tax_id})
                </option>
              ))}
            </select>
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="flex flex-col gap-1 text-sm font-medium">
              Invoice (EUR)
              <input
                data-testid="amount-input"
                type="number"
                step="0.01"
                min="0"
                className="rounded-md border border-slate-300 p-2"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium">
              Funding Currency
              <input className="rounded-md border border-slate-200 bg-slate-50 p-2 text-escrowrail-subtext" value="USD" readOnly />
            </label>
          </div>
          <label className="flex flex-col gap-1 text-sm font-medium">
            Milestone
            <select
              data-testid="milestone-select"
              className="rounded-md border border-slate-300 p-2"
              value={milestone}
              onChange={(e) => setMilestone(Number(e.target.value))}
            >
              {MILESTONES.map((m, i) => (
                <option key={m.type} value={i}>
                  {m.label}
                </option>
              ))}
            </select>
          </label>

          {/* spot quote */}
          <div className="rounded-md border border-slate-200 bg-escrowrail-canvas p-3 text-sm">
            <p className="mb-1 font-semibold text-escrowrail-subtext">Spot Quote — 1 EUR = {EUR_USD_SPOT.toFixed(2)} USD</p>
            <div className="flex justify-between"><span>Principal</span><span>{money(principal)}</span></div>
            <div className="flex justify-between"><span>Escrowrail FX Fee (0.5%)</span><span>{money(fxFee)}</span></div>
            <div className="mt-1 flex justify-between border-t border-slate-300 pt-1 font-semibold">
              <span>Gross Deposit Required</span><span data-testid="gross-deposit">{money(grossDeposit)}</span>
            </div>
          </div>

          {buyer && !buyerAccount && (
            <p className="rounded-md bg-slate-100 p-2 text-xs text-escrowrail-subtext">
              Buyer has no USD account — funding unavailable.
            </p>
          )}
          {uboCollision && (
            <div
              data-testid="ubo-error"
              className="flex items-start gap-2 rounded-md bg-escrowrail-blocked-bg p-3 text-sm font-medium text-escrowrail-blocked-text"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              403 Forbidden: Circular ownership detected (Tax ID: {buyer?.ubo_tax_id}). Escrow
              disabled under anti-tax-evasion rules.
            </div>
          )}

          <button
            data-testid="create-fund-btn"
            disabled={busy || uboCollision || !buyerAccount || amountNum <= 0 || !!contract}
            onClick={handleCreateAndFund}
            className="mt-1 flex items-center justify-center gap-2 rounded-md bg-escrowrail-neon px-4 py-2.5 font-bold text-escrowrail-forest transition hover:brightness-95 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <ShieldCheck className="h-4 w-4" />}
            Create & Fund Escrow
          </button>
          {contract && (
            <button onClick={resetForNew} className="text-xs text-escrowrail-subtext underline">
              reset — start a new contract
            </button>
          )}
        </Panel>

        {/* ============ PANEL 2: Vault + yield ============ */}
        <Panel title="Safeguarded EMI Vault" icon={<Landmark className="h-5 w-5" />}>
          {contract && contract.status !== "INITIATED" ? (
            <>
              <div className="rounded-md border border-slate-200 p-3">
                {contract.status === "FUNDED" ? (
                  <Badge tone="vault">SAFEGUARDED UNDER EMI REGULATION (Segregated Vault)</Badge>
                ) : contract.status === "SETTLED" ? (
                  <Badge tone="settled">SETTLED — VAULT RELEASED (historical)</Badge>
                ) : (
                  <Badge tone="blocked">CANCELLED — VAULT LIQUIDATED (historical)</Badge>
                )}
                <dl className="mt-3 space-y-1 text-sm">
                  <div className="flex justify-between">
                    <dt className="text-escrowrail-subtext">Contract</dt>
                    <dd data-testid="contract-id" className="font-mono text-xs">{contract.id}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-escrowrail-subtext">Snapshot spot rate</dt>
                    <dd>{contract.locked_spot_rate} USD/EUR</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-escrowrail-subtext">
                      {contract.status === "FUNDED" ? "Locked balance" : "Vaulted amount (historical)"}
                    </dt>
                    <dd data-testid="vault-balance" className="font-bold">
                      {money(contract.escrowed_amount ?? "0", "EUR")}
                    </dd>
                  </div>
                </dl>
              </div>

              {contract.status === "FUNDED" && (
              <div className="rounded-md bg-escrowrail-forest p-3 text-escrowrail-neon">
                <p className="text-xs font-semibold uppercase tracking-wide opacity-80">
                  Accrued Float Yield (3.5% ECB base rate, captured by Escrowrail)
                </p>
                <p data-testid="yield-counter" className="font-mono text-2xl font-bold">
                  €{yieldAccrued.toFixed(6)}
                </p>
              </div>
              )}

              {contract.status === "FUNDED" && (
                <button
                  data-testid="cancel-btn"
                  onClick={() => setShowCancelModal(true)}
                  className="flex items-center justify-center gap-2 rounded-md border border-escrowrail-blocked-text px-4 py-2 font-semibold text-escrowrail-blocked-text transition hover:bg-escrowrail-blocked-bg"
                >
                  <XCircle className="h-4 w-4" /> Cancel Contract
                </button>
              )}
            </>
          ) : (
            <p className="text-sm text-escrowrail-subtext">
              Vault activates once the contract is funded. Funds convert to EUR at spot before
              freezing — Escrowrail never holds open FX exposure.
            </p>
          )}
          {contract?.status === "CANCELLED" && (
            <p data-testid="cancelled-note" className="rounded-md bg-escrowrail-blocked-bg p-2 text-sm text-escrowrail-blocked-text">
              Liquidated at latest spot back to the originating USD account. Escrowrail retains the 0.5%
              conversion fee; the buyer absorbed FX movement.
            </p>
          )}
        </Panel>

        {/* ============ PANEL 3: Settlement + audit feed ============ */}
        <Panel title="Automated Settlement & Audit" icon={<PackageCheck className="h-5 w-5" />}>
          <div className="flex flex-col gap-2">
            <button
              data-testid="webhook-github-btn"
              disabled={!contract || contract.status !== "FUNDED" || contract.tracking_type !== "GITHUB_PR"}
              onClick={fireGithub}
              className="flex items-center justify-center gap-2 rounded-md bg-escrowrail-forest px-4 py-2 font-semibold text-escrowrail-neon transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <GitMerge className="h-4 w-4" /> Simulate GitHub PR Merge
            </button>
            <button
              data-testid="webhook-dhl-btn"
              disabled={!contract || contract.status !== "FUNDED" || contract.tracking_type !== "SHIPPING_CARRIER"}
              onClick={fireDhl}
              className="flex items-center justify-center gap-2 rounded-md bg-escrowrail-forest px-4 py-2 font-semibold text-escrowrail-neon transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <PackageCheck className="h-4 w-4" /> Simulate DHL Delivered
            </button>
          </div>

          {contract?.status === "SETTLED" && settleFee !== null && sellerNet !== null && (
            <div data-testid="settlement-breakdown" className="rounded-md border border-slate-200 p-3 text-sm">
              <p className="mb-2 flex items-center gap-1 font-semibold text-escrowrail-settled-text">
                <CheckCircle2 className="h-4 w-4" /> Settlement Breakdown
              </p>
              <div className="flex justify-between">
                <span>Gross Vaulted</span><span>{money(escrowed!, "EUR")}</span>
              </div>
              <div className="flex justify-between text-escrowrail-subtext">
                <span>Escrowrail take-rate (0.8%) → ESCROWRAIL_FEE_ACCOUNT</span><span>-{money(settleFee, "EUR")}</span>
              </div>
              <div className="mt-1 flex justify-between border-t border-slate-200 pt-1 font-bold">
                <span>Net to {seller?.legal_name ?? "Seller"}</span>
                <span data-testid="seller-net">{money(sellerNet, "EUR")}</span>
              </div>
            </div>
          )}

          <div
            data-testid="console-log"
            className="h-64 overflow-y-auto rounded-md bg-escrowrail-ink p-3 font-mono text-xs text-escrowrail-neon"
          >
            {log.length === 0 && <p className="text-escrowrail-subtext">// execution console — events appear here</p>}
            {log.map((e, i) => (
              <p key={i} className={e.kind === "err" ? "text-escrowrail-blocked-text" : e.kind === "webhook" ? "text-sky-300" : ""}>
                [{e.ts}] {e.text}
              </p>
            ))}
            <div ref={logEndRef} />
          </div>
        </Panel>
      </div>

      {/* cancel modal */}
      {showCancelModal && (
        <div className="fixed inset-0 z-10 flex items-center justify-center bg-black/40 p-4">
          <div className="max-w-md rounded-xl bg-white p-6 shadow-xl">
            <h3 className="mb-2 flex items-center gap-2 text-lg font-bold text-escrowrail-blocked-text">
              <AlertTriangle className="h-5 w-5" /> Cancel escrow contract?
            </h3>
            <p className="text-sm text-escrowrail-subtext">
              Escrowed EUR liquidates back to the originating USD account at the latest spot rate —
              the buyer absorbs any negative FX movement. Escrowrail retains the original 0.5% conversion
              fee.
            </p>
            <div className="mt-4 flex justify-end gap-2">
              <button
                onClick={() => setShowCancelModal(false)}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm font-semibold"
              >
                Keep escrow
              </button>
              <button
                data-testid="confirm-cancel-btn"
                onClick={handleCancel}
                className="rounded-md bg-escrowrail-blocked-text px-4 py-2 text-sm font-bold text-white"
              >
                Cancel & liquidate
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
