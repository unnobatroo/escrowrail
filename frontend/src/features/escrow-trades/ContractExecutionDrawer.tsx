import { useCallback, useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { ShieldCheck, Timer, X } from "lucide-react";
import { accountsApi, apiError, escrowApi } from "../../api";
import type { ContractView, LedgerEntry, Quote } from "../../api";
import FundingConfirmSheet from "./FundingConfirmSheet";
import EscrowMilestoneStepper from "./EscrowMilestoneStepper";
import FxSnapshotSection from "./FxSnapshotSection";
import AuditTrailSection from "./AuditTrailSection";
import CancelConfirmDialog from "./CancelConfirmDialog";
import { useDialogA11y } from "../../shared/hooks/useDialogA11y";
import CopyButton from "../../shared/components/CopyButton";
import { Skeleton } from "../../components/ui/skeleton";
import { Badge, STATUS_VARIANT } from "../../components/ui/badge";
import { money, yieldAccrued } from "../../shared/utilities/currencyFormatter";

/** Contract detail drawer: FX snapshot, live yield, audit trail, actions. */
export default function ContractExecutionDrawer({
  contractId,
  sourceBalance,
  onClose,
  onChanged,
}: {
  contractId: string;
  /** Buyer's available balance in the contract's source currency. */
  sourceBalance?: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [contract, setContract] = useState<ContractView | null>(null);
  const [audit, setAudit] = useState<LedgerEntry[]>([]);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [showCancel, setShowCancel] = useState(false);
  const [showFundSheet, setShowFundSheet] = useState(false);
  const [liveBalance, setLiveBalance] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const dialogRef = useRef<HTMLElement>(null);
  useDialogA11y(dialogRef, onClose);

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
    if (!showFundSheet || !contract) return;
    // Re-read the source balance when the sheet opens — a prop snapshot can
    // be stale after the user tops up elsewhere in the app.
    accountsApi
      .list()
      .then((r) =>
        setLiveBalance(
          r.data.find((a) => a.currency === contract.source_currency)?.balance,
        ),
      )
      .catch(() => {});
  }, [showFundSheet, contract]);
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
    }
  }, [
    contract?.status,
    contract?.target_amount,
    contract?.target_currency,
    contract?.source_currency,
  ]);

  const fund = async () => {
    if (!quote) return;
    setBusy(true);
    setError("");
    try {
      const r = await escrowApi.fund(contractId, quote.total_deposit);
      setContract(r.data);
      setShowFundSheet(false);
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

  return (
    <div className="fixed inset-0 z-50" data-testid="trade-drawer">
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="absolute inset-0 bg-brand-forest/30 backdrop-blur-md"
        onClick={onClose}
      />
      <motion.aside
        initial={{ x: "100%" }}
        animate={{ x: 0 }}
        exit={{ x: "100%" }}
        transition={{ damping: 25, stiffness: 200 }}
        className="absolute right-0 top-0 flex h-full w-full max-w-xl flex-col overflow-y-auto bg-brand-surface shadow-modal"
        ref={dialogRef as React.RefObject<HTMLElement>}
        tabIndex={-1}
      >
        <div className="flex items-start justify-between border-b border-slate-200 px-6 py-4">
          <div>
            <h2 className="text-lg font-bold text-[#1B1C1D]">
              {contract
                ? `${money(contract.target_amount, contract.target_currency)} · ${
                    contract.role === "buyer"
                      ? contract.seller_name
                      : contract.buyer_name
                  }`
                : "Loading…"}
            </h2>
            <p className="flex items-center gap-1 font-mono text-xs text-[#5C685B]">
              {contract?.id ?? contractId}
              <CopyButton
                text={contract?.id ?? contractId}
                label="contract ID"
              />
            </p>
            {contract && (
              <p className="mt-0.5 text-xs text-[#5C685B]">
                Created {new Date(contract.created_at).toLocaleString()}
              </p>
            )}
          </div>
          <div className="flex items-center gap-2">
            {contract && (
              <Badge
                data-testid="status-badge"
                variant={STATUS_VARIANT[contract.status] ?? "default"}
                className="px-3 py-1 text-sm"
              >
                {contract.status}
              </Badge>
            )}
            <button
              data-testid="drawer-close"
              onClick={onClose}
              aria-label="Close trade details"
              className="rounded-full p-1.5 text-brand-subtle transition-colors hover:bg-brand-sand"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {contract && (
          <div className="border-b border-brand-border px-6 py-4">
            <EscrowMilestoneStepper status={contract.status} />
          </div>
        )}

        <div className="flex-1 space-y-4 px-6 py-4">
          {error && (
            <div className="rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]">
              {error}
            </div>
          )}
          {!contract ? (
            <div className="space-y-3">
              <Skeleton className="h-20" />
              <Skeleton className="h-32" />
              <Skeleton className="h-24" />
            </div>
          ) : (
            <>
              {contract.status === "FUNDED" && (
                <div className="rounded-lg bg-[#CFF4FC] p-3 text-sm font-semibold text-[#084298]">
                  <ShieldCheck size={16} className="mr-1 inline" />
                  SAFEGUARDED UNDER EMI REGULATION — segregated EMI vault
                </div>
              )}

              <FxSnapshotSection contract={contract} quote={quote} />

              {/* Live float interest */}
              {contract.status === "FUNDED" && (
                <section className="rounded-xl border border-slate-200 p-4">
                  <p className="flex items-center gap-1 text-xs text-[#5C685B]">
                    <span className="relative mr-1 flex h-2 w-2">
                      <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand-neon" />
                      <span className="relative inline-flex h-2 w-2 rounded-full bg-brand-neon" />
                    </span>
                    <Timer size={14} /> Live Float Interest — 3.5% ECB
                    annualized on EUR float
                  </p>
                  <p
                    data-testid="yield-ticker"
                    className="num mt-1 text-2xl font-bold text-[#0F5132]"
                  >
                    {accrued.toFixed(6)} {contract.target_currency}
                  </p>
                  <p className="mt-0.5 text-xs text-brand-subtle">
                    interest generated in the safeguarded EMI vault
                  </p>
                </section>
              )}

              <AuditTrailSection audit={audit} />

              {/* Role-gated actions */}
              {contract.role === "buyer" && contract.status === "INITIATED" && (
                <button
                  data-testid="fund"
                  onClick={() => setShowFundSheet(true)}
                  disabled={busy || !quote}
                  className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
                >
                  Fund via {contract.source_currency} Balance
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
              {contract.role === "seller" &&
                contract.status === "INITIATED" && (
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
      </motion.aside>

      {showCancel && (
        <CancelConfirmDialog
          busy={busy}
          onConfirm={cancel}
          onDismiss={() => setShowCancel(false)}
        />
      )}

      {/* What-you-see-is-what-you-pay funding sheet */}
      {showFundSheet && quote && contract && (
        <FundingConfirmSheet
          contract={contract}
          quote={quote}
          sourceBalance={liveBalance ?? sourceBalance}
          busy={busy}
          error={error}
          onConfirm={fund}
          onClose={() => setShowFundSheet(false)}
        />
      )}
    </div>
  );
}
