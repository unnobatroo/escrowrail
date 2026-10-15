import { useRef } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { BadgeCheck, Landmark, Lock, X, Zap } from "lucide-react";
import { useDialogA11y } from "../hooks/useDialogA11y";

const STAGES = [
  {
    icon: Lock,
    title: "1 · Spot quote locked",
    body: "The buyer sees the exact deposit upfront — mid-market FX rate, one transparent 0.5% fee, zero hidden spread.",
  },
  {
    icon: Landmark,
    title: "2 · EMI safeguarding",
    body: "Converted funds sit in a segregated Electronic Money vault. They can never be lent, leveraged, or touched by operations.",
  },
  {
    icon: BadgeCheck,
    title: "3 · Automated milestone proof",
    body: "A signed webhook from GitHub or the logistics carrier proves the deliverable — no manual release calls.",
  },
  {
    icon: Zap,
    title: "4 · Instant settlement",
    body: "Proof verified → net funds pay out to the seller with a 0.8% milestone fee. Every movement is double-entry and hash-chained.",
  },
];

/** "How Escrowrail Works" — 4-stage explainer drawer from the header. */
export default function HowItWorksGuideModal({ onClose }: { onClose: () => void }) {
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogA11y(dialogRef, onClose);
  return (
    <AnimatePresence>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        className="fixed inset-0 z-50 flex items-center justify-center bg-brand-forest/30 p-4 backdrop-blur-md"
        role="dialog"
        aria-modal="true"
        aria-label="How Escrowrail works"
      >
        <motion.div
          initial={{ scale: 0.95, opacity: 0, y: 16 }}
          animate={{ scale: 1, opacity: 1, y: 0 }}
          transition={{ type: "spring", duration: 0.3 }}
          className="w-full max-w-md rounded-3xl bg-brand-surface p-6 shadow-modal"
          ref={dialogRef}
          tabIndex={-1}
        >
          <div className="mb-5 flex items-center justify-between">
            <h2 className="text-lg font-bold text-brand-charcoal">
              How Escrowrail works
            </h2>
            <button
              onClick={onClose}
              aria-label="Close explainer"
              className="rounded-full p-1.5 text-brand-subtle transition-colors hover:bg-brand-sand"
            >
              <X size={18} />
            </button>
          </div>
          <ol className="space-y-4">
            {STAGES.map(({ icon: Icon, title, body }) => (
              <li key={title} className="flex gap-3">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-neon/40 text-brand-forest">
                  <Icon size={16} />
                </span>
                <div>
                  <p className="text-sm font-bold text-brand-charcoal">
                    {title}
                  </p>
                  <p className="mt-0.5 text-sm leading-snug text-brand-subtle">
                    {body}
                  </p>
                </div>
              </li>
            ))}
          </ol>
        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
}
