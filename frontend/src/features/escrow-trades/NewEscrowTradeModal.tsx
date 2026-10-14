import { useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { ShieldAlert, X } from "lucide-react";
import {
  accountsApi,
  apiError,
  currenciesApi,
  directoryApi,
  escrowApi,
} from "../../api";
import type {
  Account,
  ContractView,
  Counterparty,
  DirectoryMember,
  Quote,
  TrackingType,
} from "../../api";

import { money } from "../../shared/utilities/currencyFormatter";
import { CurrencyFlag } from "../../shared/components/CurrencyFlag";
import Tooltip from "../../shared/components/Tooltip";
import FeeCalculationRail from "./FeeCalculationRail";
import BankComparisonDropdown from "./BankComparisonDropdown";
import { useDialogA11y } from "../../shared/hooks/useDialogA11y";

interface Props {
  onClose: () => void;
  onCreated: (c: ContractView) => void;
  /** Optional pre-fill for demo/evaluator fast paths. */
  preset?: { email?: string; amount?: string };
}

export default function NewEscrowTradeModal({
  onClose,
  onCreated,
  preset,
}: Props) {
  const [email, setEmail] = useState(preset?.email ?? "");
  const [cp, setCp] = useState<Counterparty | null>(null);
  const [cpUnknown, setCpUnknown] = useState(false);
  const [members, setMembers] = useState<DirectoryMember[]>([]);
  const [amount, setAmount] = useState(preset?.amount ?? "10000.00");
  const [trackingType, setTrackingType] = useState<TrackingType>("GITHUB_PR");
  const [trackingKey, setTrackingKey] = useState("");
  const [quote, setQuote] = useState<Quote | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  // Currency pickers are fed by GET /api/v1/currencies — whatever rails
  // the platform actually clears, not a hardcoded pair.
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [sourceCurrency, setSourceCurrency] = useState("USD");
  const [targetCurrency, setTargetCurrency] = useState("EUR");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLElement>(null);
  useDialogA11y(dialogRef, onClose);

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
    accountsApi
      .list()
      .then((r) => setAccounts(r.data))
      .catch(() => setAccounts([]));
  }, []);

  // Debounced counterparty resolution + member directory lookup.
  useEffect(() => {
    setCp(null);
    setCpUnknown(false);
    let stale = false;
    const t = setTimeout(() => {
      if (email.trim().length >= 2) {
        directoryApi
          .search(email)
          .then((r) => {
            if (!stale) setMembers(r.data.members);
          })
          .catch(() => {
            if (!stale) setMembers([]);
          });
      } else {
        setMembers([]);
      }
      if (email.includes("@")) {
        escrowApi
          .counterparty(email)
          .then((r) => {
            if (!stale) setCp(r.data);
          })
          .catch(() => {
            if (!stale) setCpUnknown(true);
          });
      }
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
      onCreated(res.data as ContractView);
    } catch (err) {
      setError(apiError(err).message ?? "Could not create the trade");
    } finally {
      setBusy(false);
    }
  };

  const input =
    "w-full rounded-lg border border-slate-200 px-3 py-2 text-sm";

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="fixed inset-0 z-10 flex items-center justify-center bg-brand-forest/30 p-4 backdrop-blur-md"
    >
      <motion.form
        onSubmit={submit}
        initial={{ scale: 0.95, opacity: 0, y: 16 }}
        animate={{ scale: 1, opacity: 1, y: 0 }}
        transition={{ type: "spring", duration: 0.3 }}
        className="w-full max-w-lg rounded-3xl bg-brand-surface p-6 shadow-modal"
        role="dialog"
        aria-modal="true"
        aria-label="New escrow trade"
        ref={dialogRef as React.RefObject<HTMLFormElement>}
        tabIndex={-1}
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-brand-charcoal">
            New Escrow Trade
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close new trade"
            className="rounded-full p-1 text-brand-subtle transition-colors hover:bg-brand-sand"
          >
            <X size={20} />
          </button>
        </div>

        <label className="mb-1 block text-sm font-medium text-[#5C685B]">
          Counterparty
        </label>
        <div className="relative mb-1">
          <input
            data-testid="cp-email"
            type="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className={input}
            placeholder="Search members by name or email"
            autoComplete="off"
          />
          {!cp && members.length > 0 && (
            <ul
              data-testid="cp-directory"
              className="absolute z-20 mt-1 max-h-48 w-full overflow-auto rounded-lg border border-slate-200 bg-white shadow-lg"
            >
              {members.map((m) => (
                <li key={m.entity_id}>
                  <button
                    type="button"
                    data-testid={`cp-member-${m.email}`}
                    onClick={() => {
                      setEmail(m.email);
                      setMembers([]);
                    }}
                    className="block w-full px-3 py-2 text-left hover:bg-[#F4F5F0]"
                  >
                    <span className="block text-sm font-medium text-[#1B1C1D]">
                      {m.legal_name}
                    </span>
                    <span className="block text-xs text-[#5C685B]">
                      {m.email} · {m.country}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        {cp && (
          <p
            data-testid="cp-resolved"
            className="mb-2 flex items-center gap-1 text-xs text-[#0F5132]"
          >
            {cp.legal_name} · {cp.country}
            <Tooltip
              label="About the ownership check"
              text="Automated AML verification ensuring buyer and seller entities do not share common beneficial ownership or tax IDs."
            />
          </p>
        )}
        {cpUnknown && (
          <p data-testid="cp-unknown" className="mb-2 text-xs text-brand-subtle">
            Not on the platform yet — the trade will wait as INVITED and an
            invite email goes out to them.
          </p>
        )}

        {/* Wise two-tier conversion widget: "You pay" → fee rail → "They
            receive". The editable field is the recipient amount (the API
            contract is target_amount); the deposit tier recalculates live. */}
        <div className="mt-3 rounded-2xl border border-brand-border bg-brand-surface p-4 transition-colors focus-within:border-brand-forest">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-brand-subtle">
              You pay
            </span>
            <span className="num text-xs text-brand-subtle">
              {(() => {
                const acct = accounts.find(
                  (a) => a.currency === sourceCurrency,
                );
                return acct
                  ? `Available: ${money(acct.balance, acct.currency)}`
                  : "Available: —";
              })()}
            </span>
          </div>
          <div className="mt-2 flex items-end justify-between gap-3">
            <span
              data-testid="quote-total"
              className="num text-3xl font-bold text-brand-charcoal"
            >
              {quote ? money(quote.total_deposit, quote.source_currency) : "—"}
            </span>
            <span className="flex items-center gap-2">
              <CurrencyFlag code={sourceCurrency} />
              <select
                data-testid="source-currency"
                value={sourceCurrency}
                onChange={(e) => setSourceCurrency(e.target.value)}
                aria-label="Deposit currency"
                className="rounded-lg border border-brand-border bg-transparent px-2 py-1 text-sm font-bold text-brand-charcoal"
              >
                {currencies.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </span>
          </div>
        </div>

        {quote && <FeeCalculationRail quote={quote} />}

        <div className="rounded-2xl border border-brand-border bg-brand-surface p-4 transition-colors focus-within:border-brand-forest">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-brand-subtle">
              Recipient receives
            </span>
            {quote && (
              <span className="num text-xs text-brand-subtle">
                1 {quote.source_currency} ={" "}
                {Number(quote.mid_market_rate).toFixed(6)}{" "}
                {quote.target_currency}
              </span>
            )}
          </div>
          <div className="mt-2 flex items-end justify-between gap-3">
            <input
              data-testid="amount"
              type="number"
              step="0.01"
              min="0.01"
              inputMode="decimal"
              required
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              aria-label="Amount the recipient receives"
              className="num w-full bg-transparent text-3xl font-bold text-brand-forest outline-none"
            />
            <span className="flex items-center gap-2">
              <CurrencyFlag code={targetCurrency} />
              <select
                data-testid="target-currency"
                value={targetCurrency}
                onChange={(e) => setTargetCurrency(e.target.value)}
                aria-label="Recipient currency"
                className="rounded-lg border border-brand-border bg-transparent px-2 py-1 text-sm font-bold text-brand-charcoal"
              >
                {currencies.map((c) => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </span>
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
          <div data-testid="quote">
            <BankComparisonDropdown quote={quote} />
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

        <motion.button
          data-testid="create-trade"
          type="submit"
          disabled={busy || uboCollision || (!cp && !cpUnknown)}
          whileTap={{ scale: 0.98 }}
          className="w-full rounded-pill bg-brand-neon py-2.5 font-semibold text-brand-forest transition-colors hover:bg-brand-neonHover disabled:opacity-50"
        >
          {busy ? "Creating…" : "Create Escrow Trade"}
        </motion.button>
      </motion.form>
    </motion.div>
  );
}
