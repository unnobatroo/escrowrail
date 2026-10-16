import type { ReactNode } from "react";
import { CurrencyFlag } from "../../../shared/components/CurrencyFlag";

/** One conversion tier: amount + currency picker. `editable` = input (recipient side); read mode = computed figure (deposit side). */
export default function AmountTier({
  label,
  aside,
  display,
  amount,
  onAmount,
  currency,
  onCurrency,
  currencies,
  amountTestId,
  currencyTestId,
  currencyLabel,
}: {
  label: string;
  aside?: ReactNode;
  /** Read-mode amount text (mutually exclusive with `amount`/`onAmount`). */
  display?: string;
  amount?: string;
  onAmount?: (v: string) => void;
  currency: string;
  onCurrency: (c: string) => void;
  currencies: string[];
  amountTestId: string;
  currencyTestId: string;
  currencyLabel: string;
}) {
  const editable = onAmount !== undefined;
  return (
    <div className="mt-3 rounded-2xl border border-brand-border bg-brand-surface p-4 transition-colors focus-within:border-brand-forest">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wider text-brand-subtle">
          {label}
        </span>
        {aside}
      </div>
      <div className="mt-2 flex items-end justify-between gap-3">
        {editable ? (
          <input
            data-testid={amountTestId}
            type="number"
            step="0.01"
            min="0.01"
            inputMode="decimal"
            required
            value={amount}
            onChange={(e) => onAmount(e.target.value)}
            aria-label={label}
            className="num w-full bg-transparent text-3xl font-bold text-brand-forest outline-none"
          />
        ) : (
          <span
            data-testid={amountTestId}
            className="num text-3xl font-bold text-brand-charcoal"
          >
            {display ?? "—"}
          </span>
        )}
        <span className="flex items-center gap-2">
          <CurrencyFlag code={currency} />
          <select
            data-testid={currencyTestId}
            value={currency}
            onChange={(e) => onCurrency(e.target.value)}
            aria-label={currencyLabel}
            className="rounded-lg border border-brand-border bg-transparent px-2 py-1 text-sm font-bold text-brand-charcoal"
          >
            {currencies.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </span>
      </div>
    </div>
  );
}
