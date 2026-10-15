import type { Counterparty, DirectoryMember } from "../../api";
import Tooltip from "../../shared/components/Tooltip";

const input = "w-full rounded-lg border border-slate-200 px-3 py-2 text-sm";

/** Email input with member-directory autocomplete and UBO resolution hints. */
export default function CounterpartyField({
  email,
  onChange,
  cp,
  cpUnknown,
  members,
  onPick,
}: {
  email: string;
  onChange: (email: string) => void;
  cp: Counterparty | null;
  cpUnknown: boolean;
  members: DirectoryMember[];
  onPick: (member: DirectoryMember) => void;
}) {
  return (
    <>
      <label className="mb-1 block text-sm font-medium text-[#5C685B]">
        Counterparty
      </label>
      <div className="relative mb-1">
        <input
          data-testid="cp-email"
          type="email"
          required
          value={email}
          onChange={(e) => onChange(e.target.value)}
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
                  onClick={() => onPick(m)}
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
    </>
  );
}
