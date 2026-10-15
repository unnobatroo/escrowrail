import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Info } from "lucide-react";

interface Props {
  /** The plain-language explanation shown on hover/focus. */
  text: string;
  /** Accessible name for the icon trigger. */
  label: string;
}

/** Lightweight popover — no portal, anchors above the trigger. */
export default function Tooltip({ text, label }: Props) {
  const [open, setOpen] = useState(false);
  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        aria-label={label}
        aria-expanded={open}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        className="text-brand-subtle transition-colors hover:text-brand-forest"
      >
        <Info size={14} />
      </button>
      <AnimatePresence>
        {open && (
          <motion.span
            role="tooltip"
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 4 }}
            transition={{ duration: 0.15 }}
            className="absolute bottom-6 left-1/2 z-50 w-64 -translate-x-1/2 rounded-xl bg-brand-charcoal px-3 py-2 text-xs font-normal leading-snug text-white shadow-elevated"
          >
            {text}
          </motion.span>
        )}
      </AnimatePresence>
    </span>
  );
}
