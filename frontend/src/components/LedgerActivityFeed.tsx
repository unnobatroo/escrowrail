import { useEffect, useState } from "react";
import { Landmark } from "lucide-react";
import { ledgerApi } from "../api";
import type { Account, LedgerEntry } from "../api";
import { money } from "../format";

const TYPE_STYLE: Record<string, string> = {
  TOP_UP: "bg-[#D1E7DD] text-[#0F5132]",
  FX_CONVERSION: "bg-[#CFF4FC] text-[#084298]",
  VAULT_LOCK: "bg-[#CFF4FC] text-[#084298]",
  ESCROW_PAYOUT: "bg-[#D1E7DD] text-[#0F5132]",
  FEE_DEDUCTION: "bg-[#FFF3CD] text-[#7A5800]",
  VAULT_LIQUIDATION: "bg-[#E2E3E5] text-[#41464B]",
  REFUND: "bg-[#D1E7DD] text-[#0F5132]",
  TRANSFER: "bg-[#E2E3E5] text-[#41464B]",
};

const SYSTEM_LABELS: Record<string, string> = {
  "00000000-0000-0000-0000-000000000001": "Wise Clearing USD",
  "00000000-0000-0000-0000-000000000002": "Wise Clearing EUR",
  "00000000-0000-0000-0000-000000000003": "Wise Fees USD",
  "00000000-0000-0000-0000-000000000004": "Wise Fees EUR",
};

/**
 * Real-time cryptographic ledger stream — the tenant's latest double-entry
 * journal rows with their SHA-256 chain links.
 */
export default function LedgerActivityFeed({
  accounts,
  refreshKey,
}: {
  accounts: Account[];
  refreshKey: number;
}) {
  const [entries, setEntries] = useState<LedgerEntry[]>([]);

  useEffect(() => {
    ledgerApi
      .transactions()
      .then((r) => setEntries(r.data))
      .catch(() => {});
  }, [refreshKey]);

  const label = (accountId: string) => {
    const mine = accounts.find((a) => a.id === accountId);
    if (mine) return `You (${mine.currency})`;
    return SYSTEM_LABELS[accountId] ?? "Escrow Vault";
  };

  const signed = (e: LedgerEntry) => {
    const debit = accounts.some((a) => a.id === e.debit_account_id);
    return `${debit ? "-" : "+"}${money(e.amount, e.currency)}`;
  };

  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <h2 className="flex items-center gap-1.5 border-b border-slate-200 px-5 py-3 font-semibold text-[#0E150E]">
        <Landmark size={16} /> Cryptographic Ledger Activity
      </h2>
      <div data-testid="ledger-feed" className="max-h-96 overflow-y-auto">
        {entries.length === 0 && (
          <p className="px-5 py-6 text-sm text-[#5C685B]">No ledger activity yet.</p>
        )}
        {entries.map((e) => (
          <div key={e.id} className="border-b border-slate-100 px-5 py-2.5 last:border-0">
            <div className="flex items-center gap-2 text-sm">
              <span
                className={`rounded-full px-2 py-0.5 text-xs font-semibold ${
                  TYPE_STYLE[e.tx_type] ?? TYPE_STYLE.TRANSFER
                }`}
              >
                {e.tx_type}
              </span>
              <span className="text-xs text-[#5C685B]">
                {label(e.debit_account_id)} → {label(e.credit_account_id)}
              </span>
              <span className="ml-auto font-medium text-[#0E150E]">{signed(e)}</span>
            </div>
            <div className="mt-0.5 flex justify-between text-xs text-[#5C685B]">
              <span>
                {new Date(e.created_at).toLocaleString()} · {e.description}
              </span>
              <span className="font-mono" title={e.current_hash}>
                hash_n = SHA-256({e.current_hash.slice(0, 12)}…)
              </span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
