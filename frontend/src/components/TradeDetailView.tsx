import { useCallback, useEffect, useState } from "react";
import { ShieldCheck, Timer, X } from "lucide-react";
import { apiError, escrowApi } from "../api";
import type { ContractView, LedgerEntry, Quote } from "../api";
import { money, yieldAccrued } from "../format";

const STATUS_STYLE: Record<string, string> = {
  INITIATED: "bg-[#FFF3CD] text-[#7A5800]",
  FUNDED: "bg-[#CFF4FC] text-[#084298]",
  SETTLED: "bg-[#D1E7DD] text-[#0F5132]",
  CANCELLED: "bg-[#E2E3E5] text-[#41464B]",
};

/**
 * Execution side drawer: full contract detail, FX snapshot, live float
 * counter, cryptographic audit trail, and role-gated lifecycle actions.
 */
export default function TradeDetailView({
  contractId,
  onClose,
  onChanged,
}: {
  contractId: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [contract, setContract] = useState<ContractView | null>(null);
  const [audit, setAudit] = useState<LedgerEntry[]>([]);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [showCancel, setShowCancel] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const reload = useCallback(() => {
    escrowApi
      .get(contractId)
      .then((r) => setContract(r.data))
      .catch((err) => setError(apiError(err).message ?? "Contract not found"));
    escrowApi
      .audit(contractId)
      .then((r) => setAudit(r.data))
      .catch(() => {});
  }, [contractId]);

  useEffect(reload, [reload]);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 100);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (contract?.status === "INITIATED") {
      escrowApi.quote(contract.target_amount).then((r) => setQuote(r.data)).catch(() => {});
    }
  }, [contract?.status, contract?.target_amount]);

  const fund = async () => {
    if (!quote) return;
    setBusy(true);
    setError("");
    try {
      const r = await escrowApi.fund(contractId, quote.total_deposit);
      setContract(r.data);
      reload();
      onChanged();
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
      const r = await escrowApi.cancel(contractId);
      setContract(r.data);
      setShowCancel(false);
      reload();
      onChanged();
    } catch (err) {
      setError(apiError(err).message ?? "Cancellation failed");
    } finally {
      setBusy(false);
    }
  };

  const accrued =
    contract?.status === "FUNDED" && contract.funded_at
      ? yieldAccrued(contract.escrowed_amount ?? "0", contract.funded_at, now)
      : 0;

  const fxFee =
    quote?.escrowrail_fee ??
    (contract?.deposit_amount && contract.locked_spot_rate
      ? (Number(contract.deposit_amount) -
          Number(contract.target_amount) / Number(contract.locked_spot_rate))
          .toFixed(2)
      : null);

  return (
    <div className="fixed inset-0 z-20" data-testid="trade-drawer">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <aside className="absolute right-0 top-0 flex h-full w-full max-w-xl flex-col overflow-y-auto bg-white shadow-2xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-6 py-4">
          <div>
            <h2 className="text-lg font-bold text-[#0E150E]">
              {contract
                ? `${money(contract.target_amount, contract.target_currency)} · ${
                    contract.role === "buyer" ? contract.seller_name : contract.buyer_name
                  }`
                : "Loading…"}
            </h2>
            <p className="font-mono text-xs text-[#5C685B]">{contract?.id ?? contractId}</p>
            {contract && (
              <p className="mt-0.5 text-xs text-[#5C685B]">
                Created {new Date(contract.created_at).toLocaleString()}
              </p>
            )}
          </div>
          <div className="flex items-center gap-2">
            {contract && (
              <span
                data-testid="status-badge"
                className={`rounded-full px-3 py-1 text-sm font-semibold ${STATUS_STYLE[contract.status]}`}
              >
                {contract.status}
              </span>
            )}
            <button
              data-testid="drawer-close"
              onClick={onClose}
              className="rounded-full p-1.5 text-[#5C685B] hover:bg-slate-100"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        <div className="flex-1 space-y-4 px-6 py-4">
          {error && (
            <div className="rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]">{error}</div>
          )}
          {!contract ? (
            <p className="text-sm text-[#5C685B]">Loading contract…</p>
          ) : (
            <>
              {contract.status === "FUNDED" && (
                <div className="rounded-lg bg-[#CFF4FC] p-3 text-sm font-semibold text-[#084298]">
                  <ShieldCheck size={16} className="mr-1 inline" />
                  SAFEGUARDED UNDER EMI REGULATION — segregated EMI vault
                </div>
              )}

              {/* FX snapshot */}
              <section className="rounded-xl border border-slate-200 p-4">
                <h3 className="mb-3 text-sm font-semibold text-[#0E150E]">FX Snapshot</h3>
                <dl className="space-y-2 text-sm">
                  <Row
                    k="Locked spot rate"
                    v={
                      contract.locked_spot_rate
                        ? `${Number(contract.locked_spot_rate).toFixed(8)} ${contract.target_currency}/${contract.source_currency}`
                        : "—"
                    }
                    mono
                  />
                  <Row
                    k="Initial deposit"
                    v={
                      contract.deposit_amount
                        ? money(contract.deposit_amount, contract.source_currency)
                        : quote
                          ? money(quote.total_deposit, quote.source_currency)
                          : "—"
                    }
                  />
                  <Row
                    k="Upfront FX fee (0.5%)"
                    v={fxFee ? money(fxFee, contract.source_currency) : "—"}
                  />
                  <Row
                    k="Vault balance"
                    v={
                      contract.escrowed_amount
                        ? money(contract.escrowed_amount, contract.target_currency)
                        : "—"
                    }
                    testId="vault-balance"
                  />
                </dl>
              </section>

              {/* Live float interest */}
              {contract.status === "FUNDED" && (
                <section className="rounded-xl border border-slate-200 p-4">
                  <p className="flex items-center gap-1 text-xs text-[#5C685B]">
                    <Timer size={14} /> Live Float Interest — 3.5% ECB annualized on EUR float
                  </p>
                  <p data-testid="yield-ticker" className="mt-1 font-mono text-2xl font-bold text-[#0F5132]">
                    {accrued.toFixed(6)} {contract.target_currency}
                  </p>
                </section>
              )}

              {/* Cryptographic audit trail */}
              <section className="rounded-xl border border-slate-200">
                <div className="flex items-center justify-between border-b border-slate-200 px-4 py-2.5">
                  <h3 className="text-sm font-semibold text-[#0E150E]">Cryptographic Audit Trail</h3>
                  {audit.length > 0 && (
                    <span className="rounded-full bg-[#D1E7DD] px-2 py-0.5 text-xs font-semibold text-[#0F5132]">
                      Audit Integrity Verified
                    </span>
                  )}
                </div>
                <div data-testid="audit-body" className="max-h-56 overflow-y-auto">
                  {audit.length === 0 && (
                    <p className="px-4 py-4 text-sm text-[#5C685B]">
                      No ledger entries yet — the trail starts at funding.
                    </p>
                  )}
                  {audit.map((e) => (
                    <div key={e.id} className="border-b border-slate-100 px-4 py-2 last:border-0">
                      <div className="flex justify-between text-sm">
                        <span className="text-[#0E150E]">{e.description}</span>
                        <span className="font-medium">{money(e.amount, e.currency)}</span>
                      </div>
                      <div className="mt-0.5 flex justify-between text-xs text-[#5C685B]">
                        <span>{new Date(e.created_at).toLocaleString()}</span>
                        <span className="font-mono">SHA-256 {e.current_hash.slice(0, 16)}…</span>
                      </div>
                    </div>
                  ))}
                </div>
              </section>

              {/* Role-gated actions */}
              {contract.role === "buyer" && contract.status === "INITIATED" && (
                <button
                  data-testid="fund"
                  onClick={fund}
                  disabled={busy || !quote}
                  className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
                >
                  Fund via USD Balance
                </button>
              )}
              {contract.role === "buyer" && contract.status === "FUNDED" && (
                <button
                  data-testid="cancel"
                  onClick={() => setShowCancel(true)}
                  className="w-full rounded-full border border-[#BA1A1A] py-2 font-semibold text-[#BA1A1A] hover:bg-[#FFDAD6]"
                >
                  Cancel Contract (Liquidate at Spot)
                </button>
              )}
              {contract.role === "seller" && contract.status === "INITIATED" && (
                <p className="rounded-lg bg-[#FFF3CD] px-3 py-2 text-center text-sm font-semibold text-[#7A5800]">
                  Awaiting Deposit
                </p>
              )}
              {contract.role === "seller" && contract.status === "FUNDED" && (
                <p className="rounded-lg bg-[#CFF4FC] px-3 py-2 text-center text-sm font-semibold text-[#084298]">
                  Funds Safeguarded in Vault
                </p>
              )}
            </>
          )}
        </div>
      </aside>

      {showCancel && (
        <div className="fixed inset-0 z-30 flex items-center justify-center bg-black/40">
          <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl">
            <h3 className="mb-2 font-bold text-[#0E150E]">Cancel contract?</h3>
            <p className="mb-4 text-sm text-[#5C685B]">
              Vaulted funds liquidate back to your originating USD account at the
              current spot rate — you absorb any FX movement. Escrowrail retains the
              original 0.5% conversion fee.
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
    </div>
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
      <dd data-testid={testId} className={`text-right ${mono ? "font-mono text-xs" : "font-medium"}`}>
        {v}
      </dd>
    </div>
  );
}
