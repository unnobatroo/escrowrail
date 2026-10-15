import { useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { ShieldAlert, X } from "lucide-react";
import {
  accountsApi,
  apiError,
  currenciesApi,
  directoryApi,
  escrowApi,
} from "../../../api";
import type {
  Account,
  ContractView,
  Counterparty,
  DirectoryMember,
  Quote,
  TrackingType,
} from "../../../api";

import { money } from "../../../shared/utilities/currencyFormatter";
import FeeCalculationRail from "./FeeCalculationRail";
import BankComparisonDropdown from "./BankComparisonDropdown";
import CounterpartyField from "./CounterpartyField";
import AmountTier from "./AmountTier";
import { useDialogA11y } from "../../../shared/hooks/useDialogA11y";

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
  const [payInput, setPayInput] = useState("");
  // Which tier the user last touched — pay-side edits derive the recipient
  // amount, receive-side edits re-quote the deposit (the API contract).
  const [editingPay, setEditingPay] = useState(false);
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

  // Keep the pay field mirroring the quoted deposit unless the user is
  // typing there — overwriting their input mid-edit would fight them.
  // When they ARE driving the pay side, a fresh quote (e.g. after a currency
  // switch) re-derives the recipient amount at the new rate.
  useEffect(() => {
    if (editingPay) {
      if (quote) {
        const derived = deriveReceives(
          payInput,
          Number(quote.mid_market_rate),
        );
        if (derived && derived !== amount) setAmount(derived);
      }
    } else {
      setPayInput(quote?.total_deposit ?? "");
    }
  }, [quote, editingPay, payInput, amount]);

  const deriveReceives = (pay: string, rate: number) => {
    const p = Number(pay);
    if (!p || p <= 0) return "";
    // Mirror QuoteCalculator: 0.5% fee with a 0.01 floor, floored to cents
    // so the derived receive figure never over-promises.
    const fee = Math.max(p * 0.005, 0.01);
    return (Math.floor((p - fee) * rate * 100) / 100).toFixed(2);
  };

  const onPayEdit = (v: string) => {
    setEditingPay(true);
    setPayInput(v);
    if (quote) {
      const derived = deriveReceives(v, Number(quote.mid_market_rate));
      if (derived) setAmount(derived);
    }
  };

  const onAmountEdit = (v: string) => {
    setEditingPay(false);
    setAmount(v);
  };

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

  const available = (() => {
    const acct = accounts.find((a) => a.currency === sourceCurrency);
    return acct ? `Available: ${money(acct.balance, acct.currency)}` : "Available: —";
  })();

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-brand-forest/30 p-4 backdrop-blur-md"
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

        <CounterpartyField
          email={email}
          onChange={setEmail}
          cp={cp}
          cpUnknown={cpUnknown}
          members={members}
          onPick={(m) => {
            setEmail(m.email);
            setMembers([]);
          }}
        />

        {/* Two-tier conversion widget: "You pay" → fee rail → "They
            receive". Both tiers are editable — the last-touched one drives,
            the other derives live. The API contract stays target_amount. */}
        <AmountTier
          label="You pay"
          aside={<span className="num text-xs text-brand-subtle">{available}</span>}
          amount={payInput}
          onAmount={onPayEdit}
          amountTestId="quote-total"
          currency={sourceCurrency}
          onCurrency={setSourceCurrency}
          currencies={currencies}
          currencyTestId="source-currency"
          currencyLabel="Deposit currency"
        />

        {quote && <FeeCalculationRail quote={quote} />}

        <AmountTier
          label="Recipient receives"
          aside={
            quote && (
              <span className="num text-xs text-brand-subtle">
                1 {quote.source_currency} ={" "}
                {Number(quote.mid_market_rate).toFixed(6)}{" "}
                {quote.target_currency}
              </span>
            )
          }
          amount={amount}
          onAmount={onAmountEdit}
          amountTestId="amount"
          currency={targetCurrency}
          onCurrency={setTargetCurrency}
          currencies={currencies}
          currencyTestId="target-currency"
          currencyLabel="Recipient currency"
        />

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
