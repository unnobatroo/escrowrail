import { Wallet } from "lucide-react";
import type { Account } from "../../api";
import AnimatedMoney from "../../shared/components/AnimatedMoney";
import { CurrencyFlag } from "../../shared/components/CurrencyFlag";

/** Per-currency balance cards with optional sandbox top-up. */
export default function AccountCards({
  accounts,
  demoEnabled,
  topupBusy,
  onTopUp,
}: {
  accounts: Account[];
  demoEnabled: boolean;
  topupBusy: string;
  onTopUp: (currency: string) => void;
}) {
  const cash = (cur: string) =>
    accounts.find((a) => a.currency === cur)?.balance ?? "0.00";

  return (
    <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
      {(["USD", "EUR"] as const).map((cur) => (
        <div
          key={cur}
          className="rounded-xl border border-slate-200 bg-white p-5"
        >
          <div className="flex items-center justify-between">
            <p className="flex items-center gap-2 text-sm font-medium text-[#5C685B]">
              <CurrencyFlag code={cur} />
              {cur} Account
            </p>
            <span className="flex items-center gap-2">
              <Wallet size={15} className="text-[#5C685B]" />
              {demoEnabled && (
                <button
                  data-testid={`topup-${cur.toLowerCase()}`}
                  onClick={() => onTopUp(cur)}
                  disabled={topupBusy === cur}
                  className="rounded-full border border-[#163300] px-3 py-1 text-xs font-semibold text-[#163300] hover:bg-[#F4F5F0] disabled:opacity-40"
                >
                  + Add Funds
                </button>
              )}
            </span>
          </div>
          <AnimatedMoney
            testId={`metric-cash-${cur.toLowerCase()}`}
            value={cash(cur)}
            currency={cur}
            className="mt-1 block text-2xl font-bold text-[#1B1C1D]"
          />
          <p className="text-xs text-[#5C685B]">
            {accounts.find((a) => a.currency === cur)?.account_type ?? "USER"} · available
          </p>
        </div>
      ))}
    </div>
  );
}
