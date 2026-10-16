export function money(amount: string | number | null | undefined, currency: string): string {
  const wire = typeof amount === "number" ? amount.toFixed(2) : amount;
  return `${groupMoney(wire)} ${currency}`;
}

/** Parses a wire-formatted decimal string into signed integer cents without IEEE-754 float math. Invalid non-empty input returns null, not a silent zero. */
export function toCents(amount: string | null | undefined): bigint | null {
  if (!amount) return 0n;
  const trimmed = amount.trim();
  if (!trimmed) return 0n;
  const neg = trimmed.startsWith("-");
  const unsigned = neg || trimmed.startsWith("+") ? trimmed.slice(1) : trimmed;
  if (!/^\d+(\.\d{1,2})?$/.test(unsigned)) return null;
  const [whole, frac = ""] = unsigned.split(".");
  const magnitude = BigInt(whole) * 100n + BigInt((frac + "00").slice(0, 2));
  return neg ? -magnitude : magnitude;
}

/** Formats signed integer cents as a canonical 2-decimal string (e.g. "10000.00"). */
export function formatCents(cents: bigint): string {
  const neg = cents < 0n;
  const abs = neg ? -cents : cents;
  return `${neg ? "-" : ""}${abs / 100n}.${(abs % 100n).toString().padStart(2, "0")}`;
}

/** en-US grouping for a wire amount — pure string ops, exact past 2^53 (backend accepts 18 integer digits). */
export function groupMoney(amount: string | null | undefined): string {
  const cents = toCents(amount);
  if (cents === null) return amount ?? "0.00";
  const s = formatCents(cents);
  const neg = s.startsWith("-");
  const unsigned = neg ? s.slice(1) : s;
  const [whole, frac] = unsigned.split(".");
  return `${neg ? "-" : ""}${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${frac}`;
}

/** Exact cent-level sum of wire-formatted amounts — no float rounding, handles negative values. */
export function sumMoney(amounts: (string | null | undefined)[]): string {
  const cents = amounts.reduce<bigint>((sum, a) => sum + (toCents(a) ?? 0n), 0n);
  return formatCents(cents);
}

/** Exact cent-level difference (a - b) of wire-formatted amounts — no float rounding. */
export function subtractMoney(
  a: string | null | undefined,
  b: string | null | undefined,
): string {
  return formatCents((toCents(a) ?? 0n) - (toCents(b) ?? 0n));
}

/** Exact cent-level comparison: returns -1 if a < b, 0 if a === b, 1 if a > b. */
export function compareMoney(
  a: string | null | undefined,
  b: string | null | undefined,
): -1 | 0 | 1 {
  const diff = (toCents(a) ?? 0n) - (toCents(b) ?? 0n);
  return diff < 0n ? -1 : diff > 0n ? 1 : 0;
}

/** Banker's rounding (HALF_EVEN) for non-negative BigInt numerator / positive BigInt denominator. */
function divRoundHalfEven(numerator: bigint, denominator: bigint): bigint {
  const q = numerator / denominator;
  const r = numerator % denominator;
  const twiceR = r * 2n;
  if (twiceR > denominator) return q + 1n;
  if (twiceR < denominator) return q;
  return q % 2n === 0n ? q : q + 1n;
}

/**
 * Exact BigInt forward conversion: given a deposit string (`pay`) and
 * mid-market rate string (`rateStr`), computes the rounded net target amount
 * after the 0.5% platform fee (HALF_EVEN cents, 0.01 minimum) without float math.
 */
export function deriveReceivesFromDeposit(pay: string, rateStr: string): string {
  const payCents = toCents(pay);
  if (payCents === null || payCents <= 1n) return "";
  const trimmedRate = rateStr.trim();
  if (!/^\d+(\.\d+)?$/.test(trimmedRate)) return "";
  const [rateWhole, rateFrac = ""] = trimmedRate.split(".");
  const rateScaled = BigInt(rateWhole + rateFrac);
  if (rateScaled <= 0n) return "";
  const rateScaleDenominator = 10n ** BigInt(rateFrac.length);

  // 0.5% fee = 5 / 1000, rounded HALF_EVEN to cents, floored at 1 cent (0.01).
  const rawFeeCents = divRoundHalfEven(payCents * 5n, 1000n);
  const feeCents = rawFeeCents < 1n ? 1n : rawFeeCents;
  const principalCents = payCents - feeCents;
  if (principalCents <= 0n) return "";

  const convertedCents = divRoundHalfEven(
    principalCents * rateScaled,
    rateScaleDenominator,
  );
  if (convertedCents <= 0n) return "";
  return formatCents(convertedCents);
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
