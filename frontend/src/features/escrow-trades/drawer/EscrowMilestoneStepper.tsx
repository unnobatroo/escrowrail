import { Check, CircleDot } from "lucide-react";

const STEPS = [
  { key: "INITIATED", label: "Initiated & quoted", sub: "Spot rate locked" },
  { key: "FUNDED", label: "Safeguarded", sub: "Vaulted under EMI rules" },
  {
    key: "VERIFYING",
    label: "Milestone verified",
    sub: "Webhook proof received",
  },
  { key: "SETTLED", label: "Settled", sub: "Net paid to seller" },
] as const;

const RANK: Record<string, number> = {
  INITIATED: 0,
  FUNDED: 1,
  SETTLED: 3,
  CANCELLED: -1,
};

/** Segmented lifecycle timeline — contract status → step position. */
export default function EscrowMilestoneStepper({ status }: { status: string }) {
  const current = RANK[status] ?? 0;
  const cancelled = status === "CANCELLED";
  return (
    <ol className="flex w-full" aria-label="Escrow lifecycle progress">
      {STEPS.map((s, i) => {
        const done = !cancelled && i < current;
        const active = !cancelled && i === current;
        return (
          <li key={s.key} className="flex flex-1 items-start">
            <div className="flex w-full flex-col items-center text-center">
              <div className="flex w-full items-center">
                <div
                  className={`h-0.5 flex-1 ${i === 0 ? "invisible" : done || active ? "bg-brand-forest" : "bg-brand-border"}`}
                />
                <div
                  className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold ${
                    done
                      ? "bg-brand-forest text-brand-neon"
                      : active
                        ? "bg-brand-neon text-brand-forest shadow-[0_0_0_6px_rgba(159,232,112,0.35)] animate-pulse"
                        : cancelled
                          ? "bg-stone-200 text-stone-400"
                          : "bg-brand-surface text-brand-subtle ring-1 ring-brand-border"
                  }`}
                >
                  {done ? (
                    <Check size={14} />
                  ) : active ? (
                    <CircleDot size={14} />
                  ) : (
                    i + 1
                  )}
                </div>
                <div
                  className={`h-0.5 flex-1 ${i === STEPS.length - 1 ? "invisible" : done ? "bg-brand-forest" : "bg-brand-border"}`}
                />
              </div>
              <p
                className={`mt-1.5 text-[10px] font-semibold leading-tight ${
                  active ? "text-brand-forest" : "text-brand-subtle"
                }`}
              >
                {s.label}
              </p>
              <p className="text-[10px] leading-tight text-brand-subtle/80">
                {s.sub}
              </p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
