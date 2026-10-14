import { AnimatePresence, motion } from "framer-motion";
import { money } from "../utilities/currencyFormatter";

/**
 * Balance figure that slides the new wire-formatted amount in on change.
 * Never does arithmetic — amounts are wire strings, formatted only.
 */
export default function AnimatedMoney({
  value,
  currency,
  className,
  testId,
}: {
  value: string | number;
  currency: string;
  className?: string;
  testId?: string;
}) {
  const text = money(value, currency);
  return (
    <AnimatePresence mode="popLayout" initial={false}>
      <motion.span
        key={text}
        data-testid={testId}
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -6 }}
        transition={{ duration: 0.2, ease: "easeOut" }}
        className={`num inline-block ${className ?? ""}`}
      >
        {text}
      </motion.span>
    </AnimatePresence>
  );
}
