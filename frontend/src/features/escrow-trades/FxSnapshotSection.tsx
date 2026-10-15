import type { ContractView, Quote } from "../../api";
import Tooltip from "../../shared/components/Tooltip";
import { money } from "../../shared/utilities/currencyFormatter";

/** Locked-rate economics: deposit, fee, and vault balance. */
export default function FxSnapshotSection({
  contract,
  quote,
}: {
  contract: ContractView;
  quote: Quote | null;
}) {
  const fxFee = contract.platform_conversion_fee ?? quote?.platform_fee ?? null;
  return (
    <section className="rounded-xl border border-slate-200 p-4">
      <h3 className="mb-3 flex items-center gap-1 text-sm font-semibold text-[#1B1C1D]">
        FX Snapshot
        <Tooltip
          label="About the mid-market rate"
          text="The true midpoint between buy and sell prices in global currency markets without added markup spreads."
        />
      </h3>
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
