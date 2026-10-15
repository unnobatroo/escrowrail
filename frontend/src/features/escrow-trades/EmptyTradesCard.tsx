import { motion } from "framer-motion";
import { ShieldCheck } from "lucide-react";
import { isDemoMode } from "../../api";

interface Props {
  onNewTrade: () => void;
  /** Demo preset: pre-fills seller@demo.com + 10,000 EUR in the trade modal. */
  onLoadDemo: () => void;
}

/** Institutional empty state for the dashboard trades table. */
export default function EmptyTradesCard({ onNewTrade, onLoadDemo }: Props) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="flex flex-col items-center rounded-2xl border border-brand-border bg-brand-surface px-6 py-12 text-center shadow-card"
    >
      {/* Cross-border escrow illustration */}
      <svg viewBox="0 0 120 72" className="mb-5 h-20 w-32" aria-hidden="true">
        <rect
          x="8"
          y="34"
          width="42"
          height="30"
          rx="6"
          fill="#F4F5F0"
          stroke="#E2E6DC"
        />
        <text
          x="29"
          y="53"
          textAnchor="middle"
          fontSize="12"
          fontWeight="700"
          fill="#163300"
        >
          USD
        </text>
        <rect
          x="70"
          y="34"
          width="42"
          height="30"
          rx="6"
          fill="#F4F5F0"
          stroke="#E2E6DC"
        />
        <text
          x="91"
          y="53"
          textAnchor="middle"
          fontSize="12"
          fontWeight="700"
          fill="#163300"
        >
          EUR
        </text>
        <rect x="42" y="10" width="36" height="24" rx="6" fill="#163300" />
        <path
          d="M55 16l5 5 7-8"
          stroke="#9FE870"
          strokeWidth="2.4"
          fill="none"
          strokeLinecap="round"
        />
        <path
          d="M36 34c4-9 9-12 14-12M84 34c-4-9-9-12-14-12"
          stroke="#9FE870"
          strokeWidth="2"
          fill="none"
          strokeDasharray="3 3"
        />
      </svg>
      <h3 className="text-lg font-bold text-brand-charcoal">
        No active trades yet
      </h3>
      <p className="mt-1 max-w-md text-sm text-brand-subtle">
        Initiate a cross-border trade, lock mid-market foreign exchange rates,
        and protect settlement behind automated milestone proofs.
      </p>
      <div className="mt-5 flex flex-wrap items-center justify-center gap-3">
        <button
          data-testid="empty-new-trade"
          onClick={onNewTrade}
          className="rounded-pill bg-brand-neon px-6 py-3 text-sm font-bold text-brand-forest transition-colors hover:bg-brand-neonHover"
        >
          + New Escrow Trade
        </button>
        {isDemoMode() && (
          <button
            data-testid="empty-load-demo"
            onClick={onLoadDemo}
            className="rounded-pill border border-brand-forest px-6 py-3 text-sm font-semibold text-brand-forest transition-colors hover:bg-brand-sand"
          >
            Load Demo Trade
          </button>
        )}
      </div>
      <p className="mt-4 flex items-center gap-1.5 text-xs text-brand-subtle">
        <ShieldCheck size={13} /> 100% of escrowed funds safeguarded under EMI
        regulation
      </p>
    </motion.div>
  );
}
