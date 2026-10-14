import { useEffect } from "react";
import { motion, useSpring, useTransform } from "framer-motion";
import { money } from "../utilities/currencyFormatter";

/** Balance figure that eases to its new value instead of snapping. */
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
  const spring = useSpring(Number(value) || 0, { stiffness: 90, damping: 22 });
  useEffect(() => {
    spring.set(Number(value) || 0);
  }, [value, spring]);
  const display = useTransform(spring, (v) => money(v.toFixed(2), currency));
  return (
    <motion.span data-testid={testId} className={`num ${className ?? ""}`}>
      {display}
    </motion.span>
  );
}
