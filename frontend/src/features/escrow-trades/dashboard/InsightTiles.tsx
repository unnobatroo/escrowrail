import { ShieldCheck, TrendingUp } from "lucide-react";
import Tooltip from "../../../shared/components/Tooltip";
import { money } from "../../../shared/utilities/currencyFormatter";

/** Safeguarded vault total + accrued float yield. */
export default function InsightTiles({
  safeguarded,
  accruedYield,
}: {
  safeguarded: string;
  accruedYield: string;
}) {
  return (
    <div className="mb-4 grid grid-cols-2 gap-3 sm:gap-4">
      <div className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
        <p className="flex items-center gap-1 text-sm text-[#5C685B]">
          <span className="mr-1 flex h-6 w-6 items-center justify-center rounded-full bg-[#CFF4FC] text-[#084298]">
            <ShieldCheck size={13} />
          </span>
          Safeguarded
          <Tooltip
            label="About safeguarded funds"
            text="Under Electronic Money Regulations, Escrowrail isolates 100% of escrowed balances from operational assets. Funds can never be lent or leveraged."
          />
        </p>
        <p data-testid="metric-vault" className="num mt-1 text-xl font-bold text-[#084298] sm:text-2xl">
          {money(safeguarded, "EUR")}
        </p>
        <p className="text-xs text-[#5C685B]">Segregated EMI vault</p>
      </div>
      <div className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
        <p className="flex items-center gap-1 text-sm text-[#5C685B]">
          <span className="mr-1 flex h-6 w-6 items-center justify-center rounded-full bg-[#D1E7DD] text-[#0F5132]">
            <TrendingUp size={13} />
          </span>
          Float Yield
          <Tooltip
            label="About float yield"
            text="Interest generated on safeguarded EUR balances at the 3.5% ECB annualized base rate, accruing per millisecond while the vault holds funds."
          />
        </p>
        <p data-testid="metric-yield" className="num mt-1 text-xl font-bold text-[#0F5132] sm:text-2xl">
          {money(accruedYield, "EUR")}
        </p>
        <p className="text-xs text-[#5C685B]">3.5% ECB base rate</p>
      </div>
    </div>
  );
}
