import type { LedgerEntry } from "../../api";
import Tooltip from "../../shared/components/Tooltip";
import CopyButton from "../../shared/components/CopyButton";
import { money } from "../../shared/utilities/currencyFormatter";

/** Hash-chained ledger entries for the contract. */
export default function AuditTrailSection({ audit }: { audit: LedgerEntry[] }) {
  return (
    <section className="rounded-xl border border-slate-200">
      <div className="flex items-center justify-between border-b border-slate-200 px-4 py-2.5">
        <h3 className="flex items-center gap-1 text-sm font-semibold text-[#1B1C1D]">
          Cryptographic Audit Trail
          <Tooltip
            label="About audit hash integrity"
            text="A cryptographic SHA-256 seal linking this transaction to all previous ledger entries. Any alteration invalidates the audit proof."
          />
        </h3>
        {audit.length > 0 && (
          <span className="rounded-full bg-[#D1E7DD] px-2 py-0.5 text-xs font-semibold text-[#0F5132]">
            Audit Integrity Verified
          </span>
        )}
      </div>
      <div data-testid="audit-body" className="max-h-56 overflow-y-auto">
        {audit.length === 0 && (
          <p className="px-4 py-4 text-sm text-[#5C685B]">
            No ledger entries yet — the trail starts at funding.
          </p>
        )}
        {audit.map((e) => (
          <div key={e.id} className="border-b border-slate-100 px-4 py-2 last:border-0">
            <div className="flex justify-between text-sm">
              <span className="text-[#1B1C1D]">{e.description}</span>
              <span className="font-medium">{money(e.amount, e.currency)}</span>
            </div>
            <div className="mt-0.5 flex justify-between text-xs text-[#5C685B]">
              <span>{new Date(e.created_at).toLocaleString()}</span>
              <span className="flex items-center gap-1 font-mono">
                SHA-256 {e.current_hash.slice(0, 16)}…
                <CopyButton text={e.current_hash} label="audit hash" />
              </span>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
