import { useRef } from "react";
import { useDialogA11y } from "../../../shared/hooks/useDialogA11y";

/** Buyer cancellation confirmation: warns that FX drift is buyer-absorbed when funded. */
export default function CancelConfirmDialog({
  busy,
  sourceCurrency = "USD",
  isFunded = true,
  onConfirm,
  onDismiss,
}: {
  busy: boolean;
  sourceCurrency?: string;
  isFunded?: boolean;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useDialogA11y(ref, onDismiss);
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40">
      <div
        ref={ref}
        tabIndex={-1}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="cancel-dialog-title"
        className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
      >
        <h3 id="cancel-dialog-title" className="mb-2 font-bold text-[#1B1C1D]">
          Cancel contract?
        </h3>
        <p className="mb-4 text-sm text-[#5C685B]">
          {isFunded
            ? `Vaulted funds liquidate back to your originating ${sourceCurrency} account at the current spot rate — you absorb any FX movement. Escrowrail retains the original 0.5% conversion fee.`
            : "No funds have been locked in the vault yet. Cancelling before funding is free and immediately closes this trade."}
        </p>
        <div className="flex gap-2">
          <button
            type="button"
            data-testid="confirm-cancel"
            onClick={onConfirm}
            disabled={busy}
            className="flex-1 rounded-full bg-[#BA1A1A] py-2 font-semibold text-white"
          >
            Confirm cancellation
          </button>
          <button
            type="button"
            onClick={onDismiss}
            disabled={busy}
            className="flex-1 rounded-full border border-slate-300 py-2 font-semibold text-[#5C685B]"
          >
            Keep escrow
          </button>
        </div>
      </div>
    </div>
  );
}
