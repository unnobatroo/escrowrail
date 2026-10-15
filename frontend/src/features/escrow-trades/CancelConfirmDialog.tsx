/** Buyer cancellation confirmation: warns that FX drift is buyer-absorbed. */
export default function CancelConfirmDialog({
  busy,
  onConfirm,
  onDismiss,
}: {
  busy: boolean;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40">
      <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl">
        <h3 className="mb-2 font-bold text-[#1B1C1D]">Cancel contract?</h3>
        <p className="mb-4 text-sm text-[#5C685B]">
          Vaulted funds liquidate back to your originating USD account at the
          current spot rate — you absorb any FX movement. Escrowrail retains the
          original 0.5% conversion fee.
        </p>
        <div className="flex gap-2">
          <button
            data-testid="confirm-cancel"
            onClick={onConfirm}
            disabled={busy}
            className="flex-1 rounded-full bg-[#BA1A1A] py-2 font-semibold text-white"
          >
            Confirm cancellation
          </button>
          <button
            onClick={onDismiss}
            className="flex-1 rounded-full border border-slate-300 py-2 font-semibold text-[#5C685B]"
          >
            Keep escrow
          </button>
        </div>
      </div>
    </div>
  );
}
