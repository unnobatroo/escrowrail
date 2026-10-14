import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-forest focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "bg-brand-neon text-brand-forest hover:bg-brand-neonHover",
        forest: "bg-brand-forest text-white hover:bg-[#0f2400]",
        outline: "border border-brand-forest text-brand-forest hover:bg-brand-sand",
        destructive: "border border-wise-blocked-text text-wise-blocked-text hover:bg-wise-blocked-bg",
        ghost: "text-wise-subtext hover:bg-slate-100",
      },
      size: {
        default: "px-4 py-2",
        sm: "px-3 py-1 text-xs",
        lg: "px-5 py-2.5",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => (
    <button
      className={cn(buttonVariants({ variant, size, className }))}
      ref={ref}
      {...props}
    />
  ),
);
Button.displayName = "Button";

export { Button, buttonVariants };
