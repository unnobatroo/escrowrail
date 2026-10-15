import { motion } from "framer-motion";
import { Handshake } from "lucide-react";
import type { ContractView } from "../../../api";
import { Badge, STATUS_VARIANT } from "../../../components/ui/badge";
import { CurrencyFlag } from "../../../shared/components/CurrencyFlag";
import { money } from "../../../shared/utilities/currencyFormatter";
import EmptyTradesCard from "./EmptyTradesCard";

export type TradeFilter = "all" | "active" | "completed";

/** Trade list card: status tabs, rows, and the empty state. */
export default function TradesTable({
  rows,
  filter,
  onFilter,
  onSelect,
  onNewTrade,
  onLoadDemo,
}: {
  rows: ContractView[];
  filter: TradeFilter;
  onFilter: (f: TradeFilter) => void;
  onSelect: (id: string) => void;
  onNewTrade: () => void;
  onLoadDemo: () => void;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <div className="flex items-center gap-2 border-b border-slate-200 px-5 py-3">
        <h2 className="mr-2 flex items-center gap-1.5 font-semibold text-[#1B1C1D] sm:mr-4">
          <Handshake size={16} className="text-[#5C685B]" /> My Trades
        </h2>
        {(["all", "active", "completed"] as TradeFilter[]).map((f) => (
          <button
            key={f}
            data-testid={`tab-${f}`}
            onClick={() => onFilter(f)}
            className={`rounded-full px-3 py-1 text-sm capitalize ${
              filter === f
                ? "bg-[#163300] text-white"
                : "text-[#5C685B] hover:bg-slate-100"
            }`}
          >
            {f}
          </button>
        ))}
      </div>
      <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-200 text-left text-[#5C685B]">
            <th className="px-5 py-2 font-medium">Counterparty</th>
            <th className="px-5 py-2 font-medium">Invoice</th>
            <th className="hidden px-5 py-2 font-medium sm:table-cell">Role</th>
            <th className="px-5 py-2 font-medium">Status</th>
          </tr>
        </thead>
        <tbody data-testid="trades-body">
          {rows.map((c, i) => (
            <motion.tr
              key={c.id}
              data-testid={`trade-row-${c.id}`}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: Math.min(i * 0.04, 0.3), duration: 0.2 }}
              onClick={() => onSelect(c.id)}
              className="cursor-pointer border-b border-brand-border/60 last:border-0 transition-colors hover:bg-brand-sand"
            >
              <td className="px-5 py-3 font-medium text-[#1B1C1D]">
                {c.role === "buyer" ? c.seller_name : c.buyer_name}
                <span className="block text-xs font-normal capitalize text-[#5C685B] sm:hidden">
                  you {c.role === "buyer" ? "buy from" : "sell to"} them
                </span>
              </td>
              <td className="px-5 py-3">
                <span className="num inline-flex items-center gap-1.5 whitespace-nowrap">
                  <CurrencyFlag code={c.target_currency} />
                  {money(c.target_amount, c.target_currency)}
                </span>
              </td>
              <td className="hidden px-5 py-3 capitalize text-[#5C685B] sm:table-cell">{c.role}</td>
              <td className="px-5 py-3">
                <Badge variant={STATUS_VARIANT[c.status] ?? "default"}>
                  {c.status}
                </Badge>
              </td>
            </motion.tr>
          ))}
        </tbody>
      </table>
      </div>
      {rows.length === 0 && (
        <div className="border-t border-brand-border px-5 py-6">
          <EmptyTradesCard onNewTrade={onNewTrade} onLoadDemo={onLoadDemo} />
        </div>
      )}
    </div>
  );
}
