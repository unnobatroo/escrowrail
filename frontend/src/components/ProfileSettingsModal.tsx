import { useEffect, useState } from "react";
import { Dices, X } from "lucide-react";
import {
  apiError,
  currenciesApi,
  demoApi,
  isDemoMode,
  profileApi,
} from "../api";
import type { UserProfile } from "../api";
import { useAuth } from "../auth";
import { money } from "../currencyFormatter";
import { randomPersona } from "../utils/personaGenerator";

const input =
  "w-full rounded-lg border border-slate-200 px-3 py-2 text-sm";

/**
 * Self-service profile: legal entity name, tax identity, the one-time
 * onboarding deposit (per supported currency), and — in demo builds —
 * instant sandbox top-ups so evaluations never stall on empty balances.
 */
export default function ProfileSettingsModal({
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved?: (user: UserProfile) => void;
}) {
  const { user, refresh } = useAuth();
  const onboarding = !(user?.onboarding_completed ?? true);
  const [legalName, setLegalName] = useState(user?.entity.legal_name ?? "");
  const [taxId, setTaxId] = useState(user?.entity.ubo_tax_id ?? "");
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [deposits, setDeposits] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [topupBusy, setTopupBusy] = useState("");

  // The supported-currency list comes from the API — never hardcoded.
  useEffect(() => {
    currenciesApi
      .supported()
      .then((r) => setCurrencies(r.data.currencies))
      .catch(() => setCurrencies(["USD", "EUR"]));
  }, []);

  const randomize = () => {
    const p = randomPersona(currencies);
    setLegalName(p.legalName);
    setTaxId(p.taxId);
    setDeposits(p.deposits);
  };

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const payload: Parameters<typeof profileApi.update>[0] = {
        legal_entity_name: legalName,
      };
      // Only re-validate the tax ID when it actually changed — existing
      // verified IDs may predate the country-format rule.
      if (taxId && taxId !== user?.entity.ubo_tax_id) {
        payload.tax_id = taxId;
      }
      const wanted = Object.fromEntries(
        Object.entries(deposits).filter(([, v]) => Number(v) > 0),
      );
      if (onboarding && Object.keys(wanted).length > 0) {
        payload.initial_deposits = wanted;
      }
      const res = await profileApi.update(payload);
      onSaved?.(res.data);
      await refresh();
      onClose();
    } catch (err) {
      setError(apiError(err).message ?? "Could not save your profile");
    } finally {
      setBusy(false);
    }
  };

  const topUp = async (currency: string) => {
    setTopupBusy(currency);
    setNotice("");
    try {
      await demoApi.topup(currency, "5000.00");
      setNotice(`Added ${money("5000.00", currency)} to your balance.`);
    } catch (err) {
      setError(apiError(err).message ?? "Top-up failed");
    } finally {
      setTopupBusy("");
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="profile-modal-title"
      className="fixed inset-0 z-20 flex items-center justify-center bg-black/40"
    >
      <form
        onSubmit={save}
        className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2
            id="profile-modal-title"
            className="text-lg font-bold text-[#1B1C1D]"
          >
            {onboarding ? "Set up your profile" : "Profile settings"}
          </h2>
          <button
            type="button"
            data-testid="settings-close"
            onClick={onClose}
            className="text-[#5C685B]"
            aria-label="Close"
          >
            <X size={20} />
          </button>
        </div>

        <div className="mb-1 flex items-center justify-between">
          <label className="text-sm font-medium text-[#5C685B]">
            Company name
          </label>
          <button
            type="button"
            data-testid="randomize"
            onClick={randomize}
            className="flex items-center gap-1 text-xs font-semibold text-[#163300] hover:underline"
          >
            <Dices size={14} /> Generate random company
          </button>
        </div>
        <input
          data-testid="profile-name"
          value={legalName}
          onChange={(e) => setLegalName(e.target.value)}
          required
          className={`${input} mb-3`}
        />

        <label className="mb-1 block text-sm font-medium text-[#5C685B]">
          Tax ID{" "}
          <span className="font-normal">({user?.entity.country})</span>
        </label>
        <input
          data-testid="profile-taxid"
          value={taxId}
          onChange={(e) => setTaxId(e.target.value)}
          className={`${input} mb-1`}
          placeholder="GB-CRN-882190"
        />
        <p className="mb-3 text-xs text-[#5C685B]">
          Format: country-scheme-serial, e.g. US-EIN-4471, DE-HRB-90210.
        </p>

        {onboarding && currencies.length > 0 && (
          <div className="mb-3 rounded-lg bg-[#F4F5F0] p-3">
            <p className="mb-2 text-sm font-semibold text-[#1B1C1D]">
              Starting balance — one-time setup credit
            </p>
            <div className="grid grid-cols-2 gap-3">
              {currencies.map((cur) => (
                <div key={cur}>
                  <label className="mb-1 block text-xs font-medium text-[#5C685B]">
                    {cur}
                  </label>
                  <input
                    data-testid={`deposit-${cur.toLowerCase()}`}
                    type="number"
                    step="0.01"
                    min="0"
                    value={deposits[cur] ?? ""}
                    onChange={(e) =>
                      setDeposits({ ...deposits, [cur]: e.target.value })
                    }
                    placeholder="0.00"
                    className={input}
                  />
                </div>
              ))}
            </div>
          </div>
        )}

        {isDemoMode() && !onboarding && currencies.length > 0 && (
          <div className="mb-3 rounded-lg bg-[#F4F5F0] p-3">
            <p className="mb-2 text-sm font-semibold text-[#1B1C1D]">
              Sandbox balances
            </p>
            <div className="flex gap-2">
              {currencies.map((cur) => (
                <button
                  key={cur}
                  type="button"
                  data-testid={`settings-topup-${cur.toLowerCase()}`}
                  onClick={() => topUp(cur)}
                  disabled={topupBusy === cur}
                  className="rounded-full border border-[#163300] px-3 py-1 text-xs font-semibold text-[#163300] hover:bg-white disabled:opacity-40"
                >
                  + {money("5000.00", cur)}
                </button>
              ))}
            </div>
          </div>
        )}

        {notice && (
          <p
            data-testid="settings-notice"
            className="mb-3 rounded-lg bg-[#D1E7DD] px-3 py-2 text-sm text-[#0F5132]"
          >
            {notice}
          </p>
        )}
        {error && (
          <p
            data-testid="settings-error"
            className="mb-3 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
          >
            {error}
          </p>
        )}

        <button
          data-testid="profile-save"
          type="submit"
          disabled={busy}
          className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
        >
          {busy
            ? "Saving…"
            : onboarding
              ? "Save and start trading"
              : "Save changes"}
        </button>
      </form>
    </div>
  );
}
