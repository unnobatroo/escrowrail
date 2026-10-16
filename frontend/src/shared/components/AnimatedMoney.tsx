import { motion } from "framer-motion";
import { money } from "../utilities/currencyFormatter";

/** Balance figure that pops on change — display only, no arithmetic. */
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
    <motion.span
      key={text}
      data-testid={testId}
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2, ease: "easeOut" }}
      className={`num inline-block ${className ?? ""}`}
    >
      {text}
    </motion.span>
  );
}
