import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { ChevronDown } from "lucide-react";
import { money } from "../../shared/currencyFormatter";
import type { Quote } from "../../api";

/** "Compare with traditional banks" collapsible cost breakdown. */
export default function BankComparisonDropdown({ quote }: { quote: Quote }) {
  const [open, setOpen] = useState(false);
  const ccy = quote.source_currency;

  return (
    <div className="mt-3 rounded-2xl border border-brand-border bg-brand-surface">
      <button
        type="button"
        data-testid="bank-comparison"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between px-4 py-3 text-left"
      >
        <span className="text-sm font-semibold text-brand-charcoal">
          Compare with traditional banks
        </span>
        <ChevronDown
          size={16}
          className={`text-brand-subtle transition-transform ${open ? "rotate-180" : ""}`}
        />
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div className="space-y-1.5 px-4 pb-4 text-sm">
              <div className="flex justify-between border-t border-brand-border pt-3 font-semibold">
                <span className="text-brand-charcoal">Wise</span>
                <span className="num text-brand-forest">
                  {money(quote.total_deposit, ccy)}
                </span>
              </div>
              <div className="flex justify-between text-brand-subtle">
                <span>FX markup (3.8%)</span>
                <span className="num">
                  {money(quote.bank_spread_cost, ccy)}
                </span>
              </div>
              <div className="flex justify-between text-brand-subtle">
                <span>SWIFT fee</span>
                <span className="num">
                  {money(quote.bank_swift_fee, ccy)}
                </span>
              </div>
              <div className="flex justify-between text-brand-subtle">
                <span>Traditional commercial bank</span>
                <span className="num">
                  {money(quote.bank_total_cost, ccy)}
                </span>
              </div>
              <div className="pt-2">
                <span
                  data-testid="customer-savings"
                  className="inline-flex items-center gap-1.5 rounded-pill bg-brand-neon px-3 py-1 text-xs font-bold text-brand-forest"
                >
                  You save {money(quote.customer_savings, ccy)} on this
                  transaction with Wise
                </span>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
