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
