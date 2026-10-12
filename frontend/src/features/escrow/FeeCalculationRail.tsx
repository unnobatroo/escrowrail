import { useEffect, useState } from "react";
import { Info } from "lucide-react";
import Tooltip from "../../shared/Tooltip";
import { money } from "../../shared/currencyFormatter";
import type { Quote } from "../../api";

/** Connects the "you pay" and "recipient receives" tiers like Wise's quote. */
export default function FeeCalculationRail({ quote }: { quote: Quote }) {
  const [pulse, setPulse] = useState(false);

  // Mid-market rate re-check pulse every 30 s.
  useEffect(() => {
    const t = setInterval(() => {
      setPulse(true);
      setTimeout(() => setPulse(false), 1800);
    }, 30_000);
    return () => clearInterval(t);
  }, []);

  return (
    <div className="relative my-1 ml-6 space-y-3 border-l-2 border-brand-border py-2 pl-5">
      <div className="relative flex items-center gap-2 text-sm">
        <span className="absolute -left-[27px] flex h-4 w-4 items-center justify-center rounded-full bg-brand-surface text-[10px] font-bold text-brand-subtle ring-1 ring-brand-border">
          −
        </span>
        <span className="num text-brand-charcoal">
          {money(quote.wise_fee, quote.source_currency)}
        </span>
        <span className="text-brand-subtle">0.5% Wise fee</span>
        <Tooltip
          label="Wise fee breakdown"
          text={`$0 transmission fee + ${money(quote.wise_fee, quote.source_currency)} FX conversion fee. One transparent charge — no hidden spread.`}
        />
      </div>
      <div className="relative flex items-center gap-2 text-sm">
        <span className="absolute -left-[27px] flex h-4 w-4 items-center justify-center rounded-full bg-brand-surface text-[10px] font-bold text-brand-subtle ring-1 ring-brand-border">
          =
        </span>
        <span className="num text-brand-charcoal">
          {money(quote.total_deposit, quote.source_currency)}
        </span>
        <span className="text-brand-subtle">Amount to convert</span>
      </div>
      <div className="relative flex items-center gap-2 text-sm">
        <span className="absolute -left-[27px] flex h-4 w-4 items-center justify-center rounded-full bg-brand-surface text-[10px] font-bold text-brand-subtle ring-1 ring-brand-border">
          ×
        </span>
        <span className="num text-brand-charcoal">
          {Number(quote.mid_market_rate).toFixed(6)}
        </span>
        <span className="text-brand-subtle">Guaranteed mid-market rate</span>
        <span className="relative flex h-2 w-2" aria-hidden="true">
          <span
            className={`absolute inline-flex h-full w-full rounded-full bg-brand-neon ${
              pulse ? "animate-ping" : "opacity-40"
            }`}
          />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-brand-neon" />
        </span>
        <Info size={12} className="sr-only" />
      </div>
    </div>
  );
}
