export function money(amount: string | number | null | undefined, currency: string): string {
  const n = Number(amount ?? 0);
  return `${n.toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })} ${currency}`;
}

export function yieldAccrued(escrowed: string, fundedAt: string, nowMs: number): number {
  const elapsed = Math.max(0, (nowMs - Date.parse(fundedAt)) / 1000);
  return (Number(escrowed) * 0.035 * elapsed) / (365 * 86400);
}

const RTF = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

/** "3h ago"/"yesterday" relative timestamps; locale date past a week. */
export function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diff / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return RTF.format(-mins, "minute");
  const hours = Math.round(mins / 60);
  if (hours < 24) return RTF.format(-hours, "hour");
  const days = Math.round(hours / 24);
  if (days < 7) return RTF.format(-days, "day");
  return new Date(iso).toLocaleDateString();
}
