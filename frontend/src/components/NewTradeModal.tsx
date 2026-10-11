import { useEffect, useState } from "react";
import { ShieldAlert, X } from "lucide-react";
import { apiError, currenciesApi, escrowApi } from "../api";
import type { ContractView, Counterparty, Quote, TrackingType } from "../api";

import { money } from "../format";

interface Props {
  onClose: () => void;
  onCreated: (c: ContractView) => void;
}

export default function NewTradeModal({ onClose, onCreated }: Props) {
  const [email, setEmail] = useState("");
  const [cp, setCp] = useState<Counterparty | null>(null);
  const [cpUnknown, setCpUnknown] = useState(false);
  const [amount, setAmount] = useState("10000.00");
  const [trackingType, setTrackingType] = useState<TrackingType>("GITHUB_PR");
  const [trackingKey, setTrackingKey] = useState("");
  const [quote, setQuote] = useState<Quote | null>(null);
  // Currency pickers are fed by GET /api/v1/currencies — whatever rails
  // the platform actually clears, not a hardcoded pair.
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [sourceCurrency, setSourceCurrency] = useState("USD");
  const [targetCurrency, setTargetCurrency] = useState("EUR");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [invited, setInvited] = useState(false);

  useEffect(() => {
    currenciesApi
      .supported()
      .then((r) => {
        setCurrencies(r.data.currencies);
        // Sensible defaults: first alphabetically is the pay-out side.
        if (r.data.currencies.length > 1) {
          setTargetCurrency(r.data.currencies[0]);
          setSourceCurrency(r.data.currencies[1]);
        }
      })
      .catch(() => setCurrencies(["EUR", "USD"]));
  }, []);

  // Debounced counterparty resolution.
  useEffect(() => {
    setCp(null);
    setCpUnknown(false);
    if (!email.includes("@")) return;
    let stale = false;
    const t = setTimeout(() => {
      escrowApi
        .counterparty(email)
        .then((r) => {
          if (!stale) setCp(r.data);
        })
        .catch(() => {
          if (!stale) setCpUnknown(true);
        });
    }, 300);
    return () => {
      stale = true;
      clearTimeout(t);
    };
  }, [email]);

  // Live backend quote for the chosen amount and currency pair.
  useEffect(() => {
    const n = Number(amount);
    if (!n || n <= 0 || sourceCurrency === targetCurrency) {
      setQuote(null);
      return;
    }
    let stale = false;
    const t = setTimeout(() => {
      escrowApi
        .quote(amount, targetCurrency, sourceCurrency)
        .then((r) => {
          if (!stale) setQuote(r.data);
        })
        .catch(() => {
          if (!stale) setQuote(null);
        });
    }, 300);
    return () => {
      stale = true;
      clearTimeout(t);
    };
  }, [amount, sourceCurrency, targetCurrency]);

  const uboCollision = cp?.same_ubo === true;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const res = await escrowApi.create({
        counterparty_email: email,
        target_amount: amount,
        target_currency: targetCurrency,
        source_currency: sourceCurrency,
        tracking_type: trackingType,
        tracking_key: trackingKey,
      });
      if (res.status === 202) {
        setInvited(true);
      } else {
        onCreated(res.data as ContractView);
      }
    } catch (err) {
      setError(apiError(err).message ?? "Could not create the trade");
    } finally {
      setBusy(false);
    }
  };

  const input =
    "w-full rounded-lg border border-slate-200 px-3 py-2 text-sm";

  return (
    <div className="fixed inset-0 z-10 flex items-center justify-center bg-black/40">
      <form
        onSubmit={submit}
        className="w-full max-w-lg rounded-xl bg-white p-6 shadow-xl"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-[#1B1C1D]">New Escrow Trade</h2>
          <button type="button" onClick={onClose} className="text-[#5C685B]">
            <X size={20} />
          </button>
        </div>

        <label className="mb-1 block text-sm font-medium text-[#5C685B]">
          Counterparty email
        </label>
        <input
          data-testid="cp-email"
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className={`${input} mb-1`}
          placeholder="seller@company.com"
        />
        {cp && (
          <p data-testid="cp-resolved" className="mb-2 text-xs text-[#0F5132]">
            {cp.legal_name} · {cp.country}
          </p>
        )}
        {cpUnknown && (
          <p data-testid="cp-invite" className="mb-2 text-xs text-[#084298]">
            Not on the platform — an invitation will be sent on submit.
          </p>
        )}

        <div className="mt-3 grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-sm font-medium text-[#5C685B]">
              They receive
            </label>
            <div className="flex gap-2">
              <input
                data-testid="amount"
                type="number"
                step="0.01"
                min="0.01"
                required
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className={input}
              />
              <select
                data-testid="target-currency"
                value={targetCurrency}
                onChange={(e) => setTargetCurrency(e.target.value)}
                className="w-20 rounded-lg border border-slate-200 px-2 py-2 text-sm"
              >
                {currencies.map((c) => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </div>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-[#5C685B]">
              You deposit
            </label>
            <select
              data-testid="source-currency"
              value={sourceCurrency}
              onChange={(e) => setSourceCurrency(e.target.value)}
              className={input}
            >
              {currencies.map((c) => (
                <option key={c} value={c}>
                  {c} balance
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3">
          <div>
            <label className="mb-1 block text-sm font-medium text-[#5C685B]">
              Milestone
            </label>
            <select
              data-testid="tracking-type"
              value={trackingType}
              onChange={(e) => setTrackingType(e.target.value as TrackingType)}
              className={input}
            >
              <option value="GITHUB_PR">GitHub PR merge</option>
              <option value="SHIPPING_CARRIER">Logistics tracking</option>
            </select>
          </div>
        </div>

        <label className="mb-1 mt-3 block text-sm font-medium text-[#5C685B]">
          {trackingType === "GITHUB_PR" ? "Pull request URL" : "Tracking number"}
        </label>
        <input
          data-testid="tracking-key"
          required
          value={trackingKey}
          onChange={(e) => setTrackingKey(e.target.value)}
          className={`${input} mb-3`}
          placeholder={
            trackingType === "GITHUB_PR"
              ? "https://github.com/org/repo/pull/1"
              : "DHL-987654"
          }
        />

        {quote && (
          <div
            data-testid="quote"
            className="mb-3 rounded-lg bg-[#F4F5F0] p-3 text-sm"
          >
            <div className="flex justify-between">
              <span className="text-[#5C685B]">Mid-market rate</span>
              <span>
                1 {quote.source_currency} ={" "}
                {quote.mid_market_rate.slice(0, 8)} {quote.target_currency}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-[#5C685B]">They receive</span>
              <span>{money(quote.target_amount, quote.target_currency)}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[#5C685B]">Wise FX fee (0.5%)</span>
              <span>{money(quote.wise_fee, quote.source_currency)}</span>
            </div>
            <div className="mt-1 flex justify-between border-t border-slate-200 pt-1 font-semibold">
              <span>You deposit</span>
              <span data-testid="quote-total">
                {money(quote.total_deposit, quote.source_currency)}
              </span>
            </div>
            <details
              data-testid="bank-comparison"
              className="mt-2 border-t border-slate-200 pt-2"
            >
              <summary className="cursor-pointer text-xs font-semibold text-[#5C685B]">
                Compare with a traditional bank
              </summary>
              <div className="mt-1 space-y-0.5 text-xs">
                <div className="flex justify-between">
                  <span className="text-[#5C685B]">
                    Bank FX markup (3.8%)
                  </span>
                  <span>
                    {money(quote.bank_spread_cost, quote.source_currency)}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-[#5C685B]">SWIFT fee</span>
                  <span>
                    {money(quote.bank_swift_fee, quote.source_currency)}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-[#5C685B]">Bank total cost</span>
                  <span>
                    {money(quote.bank_total_cost, quote.source_currency)}
                  </span>
                </div>
                <div className="flex justify-between font-semibold text-[#163300]">
                  <span>You save</span>
                  <span data-testid="customer-savings">
                    {money(quote.customer_savings, quote.source_currency)}
                  </span>
                </div>
              </div>
            </details>
          </div>
        )}

        {uboCollision && (
          <div
            data-testid="ubo-warning"
            className="mb-3 flex items-start gap-2 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
          >
            <ShieldAlert size={18} className="mt-0.5 shrink-0" />
            Hold on: buyer and seller share the same tax identity
            (self-trading is blocked).
          </div>
        )}
        {error && (
          <div
            data-testid="create-error"
            className="mb-3 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
          >
            {error}
          </div>
        )}

        {invited ? (
          <div
            data-testid="invite-sent"
            className="rounded-lg bg-[#D1E7DD] px-3 py-2 text-sm text-[#0F5132]"
          >
            Invitation sent to {email} — they can register and the trade can be
            re-created once they join.
          </div>
        ) : (
          <button
            data-testid="create-trade"
            type="submit"
            disabled={busy || uboCollision || (!cp && !cpUnknown)}
            className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
          >
            {busy
              ? "Creating…"
              : cpUnknown
                ? "Send Invitation"
                : "Create Escrow Trade"}
          </button>
        )}
      </form>
    </div>
  );
}
