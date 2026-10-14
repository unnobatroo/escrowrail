import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-semibold",
  {
    variants: {
      variant: {
        invited: "bg-indigo-50 text-indigo-700 border-indigo-200",
        initiated: "bg-amber-100 text-amber-900 border-amber-300",
        funded: "bg-emerald-100 text-emerald-900 border-emerald-300",
        settled: "bg-brand-neon/40 text-brand-forest border-brand-neon font-bold",
        cancelled: "bg-stone-200 text-stone-700 border-transparent font-medium",
        default: "bg-stone-200 text-stone-700 border-transparent",
      },
    },
    defaultVariants: { variant: "default" },
  },
);

export const STATUS_VARIANT = {
  INVITED: "invited",
  INITIATED: "initiated",
  FUNDED: "funded",
  SETTLED: "settled",
  CANCELLED: "cancelled",
} as const;

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <span className={cn(badgeVariants({ variant }), className)} {...props} />
  );
}

export { Badge, badgeVariants };
