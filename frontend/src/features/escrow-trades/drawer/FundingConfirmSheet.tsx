import { useRef } from "react";
import type { ContractView, Quote } from "../../../api";
import {
  compareMoney,
  money,
  subtractMoney,
} from "../../../shared/utilities/currencyFormatter";
import { useDialogA11y } from "../../../shared/hooks/useDialogA11y";

const CURRENCY_SYMBOLS: Record<string, string> = {
  USD: "$",
  EUR: "€",
  GBP: "£",
};

/**
 * What-you-see-is-what-you-pay bottom sheet — shown before any escrow
 * funding so the gross debit, platform fee, vault amount, and bank estimate
 * are explicit. A shortfall in the source-currency balance disables the
 * confirm action and says exactly how much is missing.
 */
export default function FundingConfirmSheet({
  contract,
  quote,
  sourceBalance,
  busy,
  error,
  onConfirm,
  onClose,
}: {
  contract: ContractView;
  quote: Quote;
  /** Buyer's available balance in the source currency, if loaded. */
  sourceBalance?: string;
  busy: boolean;
  error?: string;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const shortfall =
    sourceBalance != null && compareMoney(quote.total_deposit, sourceBalance) > 0
      ? subtractMoney(quote.total_deposit, sourceBalance)
      : null;
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogA11y(dialogRef, onClose);

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/40 sm:items-center">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="fund-sheet-title"
        ref={dialogRef}
        tabIndex={-1}
        className="w-full max-w-md rounded-t-2xl bg-white p-6 shadow-xl sm:rounded-xl"
      >
        <h3 id="fund-sheet-title" className="mb-3 font-bold text-[#1B1C1D]">
          Confirm and deposit
        </h3>
        <p className="mb-3 text-sm text-[#1B1C1D]">
          You are locking{" "}
          <strong data-testid="sheet-gross">
            {money(quote.total_deposit, quote.source_currency)}
          </strong>{" "}
          to send{" "}
          <strong>
            {money(contract.target_amount, contract.target_currency)}
          </strong>
          .
        </p>
        <div className="mb-3 space-y-1 rounded-lg bg-[#F4F5F0] p-3 text-sm">
          <div className="flex justify-between">
            <span className="text-[#5C685B]">You deposit</span>
            <span className="font-semibold">
              {money(quote.total_deposit, quote.source_currency)}
            </span>
          </div>
          <div className="flex justify-between">
            <span className="text-[#5C685B]">Platform fee (0.5%)</span>
            <span>{money(quote.platform_fee, quote.source_currency)}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-[#5C685B]">They receive</span>
            <span>{money(quote.target_amount, quote.target_currency)}</span>
          </div>
          <div className="flex justify-between border-t border-slate-200 pt-1 text-xs">
            <span className="text-[#5C685B]">
              A bank would have charged about
            </span>
            <span data-testid="sheet-bank">
              {money(quote.bank_total_cost, quote.source_currency)}
            </span>
          </div>
        </div>
        {shortfall && (
          <p
            data-testid="shortfall-banner"
            className="mb-3 rounded-lg bg-[#FFF3CD] px-3 py-2 text-sm font-semibold text-[#7A5800]"
          >
            You need{" "}
            {CURRENCY_SYMBOLS[quote.source_currency]
              ? `${CURRENCY_SYMBOLS[quote.source_currency]}${Number(
                  shortfall,
                ).toLocaleString("en-US", { minimumFractionDigits: 2 })}`
              : money(shortfall, quote.source_currency)}{" "}
            more to fund this trade.
          </p>
        )}
        {error && (
          <p className="mb-3 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]">
            {error}
          </p>
        )}
        <div className="flex gap-2">
          <button
            data-testid="confirm-fund"
            onClick={onConfirm}
            disabled={busy || !!shortfall}
            className="flex-1 rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
          >
            {busy ? "Locking…" : "Confirm and deposit"}
          </button>
          <button
            data-testid="sheet-close"
            onClick={onClose}
            className="flex-1 rounded-full border border-slate-300 py-2 font-semibold text-[#5C685B]"
          >
            Not now
          </button>
        </div>
      </div>
    </div>
  );
}
