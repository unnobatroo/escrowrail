import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Landmark, ShieldCheck, Timer } from "lucide-react";
import { accountsApi, apiError, demoApi, escrowApi, isDemoMode } from "../../api";
import FundingConfirmSheet from "./FundingConfirmSheet";
import type { ContractView, LedgerEntry, Quote } from "../../api";
import Shell from "../../shared/Shell";
import { money, yieldAccrued } from "../../shared/currencyFormatter";

type Tab = "overview" | "audit";

export default function ContractDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [contract, setContract] = useState<ContractView | null>(null);
  const [audit, setAudit] = useState<LedgerEntry[]>([]);
  const [tab, setTab] = useState<Tab>("overview");
  const [now, setNow] = useState(() => Date.now());
  const [quote, setQuote] = useState<Quote | null>(null);
  const [showCancel, setShowCancel] = useState(false);
  const [showFundSheet, setShowFundSheet] = useState(false);
  const [sourceBalance, setSourceBalance] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");

  const reload = useCallback(() => {
    escrowApi
      .get(id)
      .then((r) => {
        setContract(r.data);
        setLoadError("");
      })
      .catch((err) => {
        setContract(null);
        setLoadError(apiError(err).message ?? "Contract not found");
      });
  }, [id]);

  useEffect(reload, [reload]);
  useEffect(() => {
    escrowApi
      .audit(id)
      .then((r) => setAudit(r.data))
      .catch(() => {});
  }, [id, contract?.status]);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 100);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (contract?.status === "INITIATED") {
      escrowApi
        .quote(
          contract.target_amount,
          contract.target_currency,
          contract.source_currency,
        )
        .then((r) => setQuote(r.data))
        .catch(() => {});
      accountsApi
        .list()
        .then((r) =>
          setSourceBalance(
            r.data.find((a) => a.currency === contract.source_currency)
              ?.balance,
          ),
        )
        .catch(() => {});
    }
  }, [contract?.status, contract?.target_amount, contract?.target_currency, contract?.source_currency]);

  const fund = async () => {
    if (!quote) return;
    setBusy(true);
    setError("");
    try {
      const r = await escrowApi.fund(id, quote.total_deposit);
      setContract(r.data);
      setShowFundSheet(false);
    } catch (err) {
      setError(apiError(err).message ?? "Funding failed");
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setBusy(true);
    setError("");
    try {
      const r = await escrowApi.cancel(id);
      setContract(r.data);
      setShowCancel(false);
    } catch (err) {
      setError(apiError(err).message ?? "Cancellation failed");
    } finally {
      setBusy(false);
    }
  };

  const simulate = async () => {
    if (!contract) return;
    setBusy(true);
    setNotice("");
    setError("");
    try {
      // The demo gateway signs the milestone server-side and dispatches it
      // through the real verification path — unsigned calls to /webhooks/*
      // are correctly rejected.
      const res = await demoApi.simulateWebhook(contract.id);
      setNotice(
        `Webhook: ${res.data.status} (signature ${res.data.signature})`,
      );
      reload();
    } catch (err) {
      setError(apiError(err).message ?? "Simulation failed");
    } finally {
      setBusy(false);
    }
  };

  const accrued =
    contract?.status === "FUNDED" && contract.funded_at
      ? yieldAccrued(contract.escrowed_amount ?? "0", contract.funded_at, now)
      : 0;

  const badge = (text: string, cls: string, testId?: string) => (
    <span
      data-testid={testId}
      className={`rounded-full px-3 py-1 text-sm font-semibold ${cls}`}
    >
      {text}
    </span>
  );

  return (
    <Shell>
      {!contract ? (
        <p className="text-[#5C685B]">{loadError || "Loading…"}</p>
      ) : (
        <>
          <div className="mb-4 flex items-center justify-between">
            <div>
              <button
                onClick={() => navigate("/dashboard")}
                className="mb-1 text-sm text-[#5C685B] underline"
              >
                ← All trades
              </button>
              <h1 className="text-xl font-bold text-[#1B1C1D]">
                {money(contract.target_amount, contract.target_currency)} escrow
                ·{" "}
                {contract.role === "buyer"
                  ? contract.seller_name
                  : contract.buyer_name}
              </h1>
              <p className="font-mono text-xs text-[#5C685B]">{contract.id}</p>
            </div>
            {badge(
              contract.status,
              {
                INITIATED: "bg-[#FFF3CD] text-[#7A5800]",
                FUNDED: "bg-[#CFF4FC] text-[#084298]",
                SETTLED: "bg-[#D1E7DD] text-[#0F5132]",
                CANCELLED: "bg-[#E2E3E5] text-[#41464B]",
              }[contract.status],
              "status-badge",
            )}
          </div>

          <div className="mb-4 flex gap-2">
            {(["overview", "audit"] as Tab[]).map((t) => (
              <button
                key={t}
                data-testid={`tab-${t}`}
                onClick={() => setTab(t)}
                className={`rounded-full px-4 py-1.5 text-sm capitalize ${
                  tab === t
                    ? "bg-[#163300] text-white"
                    : "bg-white text-[#5C685B] hover:bg-slate-100"
                }`}
              >
                {t === "audit" ? "Historical Audit" : "Overview"}
              </button>
            ))}
          </div>

          {error && (
            <div className="mb-4 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]">
              {error}
            </div>
          )}
          {notice && (
            <div className="mb-4 rounded-lg bg-[#D1E7DD] px-3 py-2 text-sm text-[#0F5132]">
              {notice}
            </div>
          )}

          {tab === "overview" ? (
            <div className="grid grid-cols-2 gap-4">
              <div className="rounded-xl border border-slate-200 bg-white p-5">
                <h2 className="mb-3 font-semibold text-[#1B1C1D]">Contract</h2>
                <dl className="space-y-2 text-sm">
                  <Row k="Milestone" v={contract.tracking_type} />
                  <Row k="Tracking key" v={contract.tracking_key} mono />
                  <Row k="Your role" v={contract.role} />
                  <Row
                    k="Locked spot rate"
                    v={
                      contract.locked_spot_rate
                        ? `${contract.locked_spot_rate} ${contract.target_currency}/${contract.source_currency}`
                        : "—"
                    }
                  />
                  <Row
                    k="Vault balance"
                    testId="vault-balance"
                    v={
                      contract.escrowed_amount
                        ? money(
                            contract.escrowed_amount,
                            contract.target_currency,
                          )
                        : "—"
                    }
                  />
                </dl>

                {contract.role === "buyer" &&
                  contract.status === "INITIATED" && (
                    <div className="mt-4">
                      {quote && (
                        <p className="mb-2 text-sm text-[#5C685B]">
                          Deposit{" "}
                          {money(quote.total_deposit, quote.source_currency)}{" "}
                          (incl. {money(quote.wise_fee, quote.source_currency)}{" "}
                          FX fee)
                        </p>
                      )}
                      <button
                        data-testid="fund"
                        onClick={() => setShowFundSheet(true)}
                        disabled={busy || !quote}
                        className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
                      >
                        Fund via {contract.source_currency} Balance
                      </button>
                    </div>
                  )}
                {contract.role === "seller" &&
                  contract.status === "INITIATED" &&
                  badge(
                    "Awaiting Buyer Deposit",
                    "bg-[#FFF3CD] text-[#7A5800]",
                  )}
                {contract.status === "FUNDED" &&
                  contract.role === "seller" &&
                  badge(
                    "Funds Safeguarded in EMI Vault",
                    "bg-[#CFF4FC] text-[#084298]",
                  )}
                {contract.role === "buyer" && contract.status === "FUNDED" && (
                  <button
                    data-testid="cancel"
                    onClick={() => setShowCancel(true)}
                    className="mt-4 w-full rounded-full border border-[#BA1A1A] py-2 font-semibold text-[#BA1A1A] hover:bg-[#FFDAD6]"
                  >
                    Cancel Contract
                  </button>
                )}
              </div>

              <div className="rounded-xl border border-slate-200 bg-white p-5">
                <h2 className="mb-3 font-semibold text-[#1B1C1D]">Milestone</h2>
                {contract.status === "FUNDED" && (
                  <div className="mb-4 rounded-lg bg-[#CFF4FC] p-3 text-sm text-[#084298]">
                    <ShieldCheck size={16} className="mr-1 inline" />
                    SAFEGUARDED UNDER EMI REGULATION
                  </div>
                )}
                {contract.status === "FUNDED" && (
                  <div className="mb-4">
                    <p className="flex items-center gap-1 text-xs text-[#5C685B]">
                      <Timer size={14} /> Accrued Float Yield (3.5% ECB)
                    </p>
                    <p
                      data-testid="yield-ticker"
                      className="text-xl font-bold text-[#0F5132]"
                    >
                      {accrued.toFixed(4)} {contract.target_currency}
                    </p>
                  </div>
                )}
                <p className="mb-3 text-sm text-[#5C685B]">
                  Settlement releases automatically when the milestone verifies.
                </p>
                {isDemoMode() && (
                  <>
                    <p className="mb-3 text-sm text-[#5C685B]">
                      Demo mode — fire the milestone via the signed gateway:
                    </p>
                    <button
                      data-testid="simulate"
                      onClick={simulate}
                      disabled={busy || contract.status !== "FUNDED"}
                      className="w-full rounded-full border border-[#163300] py-2 text-sm font-semibold text-[#163300] hover:bg-[#F4F5F0] disabled:opacity-40"
                    >
                      {contract.tracking_type === "GITHUB_PR"
                        ? "Simulate GitHub PR Merge"
                        : "Simulate DHL Delivered"}
                    </button>
                  </>
                )}
              </div>
            </div>
          ) : (
            <div className="rounded-xl border border-slate-200 bg-white">
              <h2 className="border-b border-slate-200 px-5 py-3 font-semibold text-[#1B1C1D]">
                <Landmark size={16} className="mr-1 inline" /> Double-entry
                trail
              </h2>
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-left text-[#5C685B]">
                    <th className="px-5 py-2 font-medium">Time</th>
                    <th className="px-5 py-2 font-medium">Description</th>
                    <th className="px-5 py-2 font-medium">Amount</th>
                  </tr>
                </thead>
                <tbody data-testid="audit-body">
                  {audit.map((e) => (
                    <tr
                      key={e.id}
                      className="border-b border-slate-100 last:border-0"
                    >
                      <td className="px-5 py-2 font-mono text-xs text-[#5C685B]">
                        {new Date(e.created_at).toLocaleString()}
                      </td>
                      <td className="px-5 py-2">{e.description}</td>
                      <td className="px-5 py-2 font-medium">
                        {money(e.amount, e.currency)}
                      </td>
                    </tr>
                  ))}
                  {audit.length === 0 && (
                    <tr>
                      <td
                        colSpan={3}
                        className="px-5 py-6 text-center text-[#5C685B]"
                      >
                        No ledger entries yet — the trail starts at funding.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {showCancel && (
            <div className="fixed inset-0 z-10 flex items-center justify-center bg-black/40">
              <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl">
                <h3 className="mb-2 font-bold text-[#1B1C1D]">
                  Cancel contract?
                </h3>
                <p className="mb-4 text-sm text-[#5C685B]">
                  Vaulted funds liquidate back to your originating USD account
                  at the current spot rate — you absorb any FX movement. Wise
                  retains the original 0.5% conversion fee.
                </p>
                <div className="flex gap-2">
                  <button
                    data-testid="confirm-cancel"
                    onClick={cancel}
                    disabled={busy}
                    className="flex-1 rounded-full bg-[#BA1A1A] py-2 font-semibold text-white"
                  >
                    Confirm cancellation
                  </button>
                  <button
                    onClick={() => setShowCancel(false)}
                    className="flex-1 rounded-full border border-slate-300 py-2 font-semibold text-[#5C685B]"
                  >
                    Keep escrow
                  </button>
                </div>
              </div>
            </div>
          )}

          {showFundSheet && quote && contract && (
            <FundingConfirmSheet
              contract={contract}
              quote={quote}
              sourceBalance={sourceBalance}
              busy={busy}
              error={error}
              onConfirm={fund}
              onClose={() => setShowFundSheet(false)}
            />
          )}
        </>
      )}
    </Shell>
  );
}

function Row({
  k,
  v,
  mono,
  testId,
}: {
  k: string;
  v: string;
  mono?: boolean;
  testId?: string;
}) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-[#5C685B]">{k}</dt>
      <dd
        data-testid={testId}
        className={`text-right ${mono ? "font-mono text-xs" : "font-medium"}`}
      >
        {v}
      </dd>
    </div>
  );
}
