/** One-click test persona generator (name, tax ID, deposit). */

const ADJECTIVES = [
  "Swift", "Amber", "Velvet", "Iron", "Cobalt", "Golden", "Crimson",
  "Silent", "Bright", "North", "Lunar", "Copper", "Misty", "Bold",
];

const NOUNS = [
  "Falcon", "Pine", "Otter", "Harbor", "Maple", "Fox", "Cedar",
  "Heron", "Sparrow", "Birch", "Comet", "River", "Wolf", "Oak",
];

const INDUSTRIES = [
  "Logistics", "Robotics", "Solutions", "Systems", "Trading",
  "Industries", "Labs", "Works", "Supply", "Freight",
];

interface CountryStyle {
  code: string;
  scheme: string;
  suffixes: string[];
}

const COUNTRIES: CountryStyle[] = [
  { code: "US", scheme: "EIN", suffixes: ["Inc", "LLC", "Corp"] },
  { code: "GB", scheme: "CRN", suffixes: ["Ltd", "PLC"] },
  { code: "DE", scheme: "HRB", suffixes: ["GmbH", "AG"] },
  { code: "FR", scheme: "SIRET", suffixes: ["SAS", "SARL"] },
  { code: "NL", scheme: "KVK", suffixes: ["B.V."] },
  { code: "SE", scheme: "ORG", suffixes: ["AB"] },
];

/** Suggested starter deposit per currency, in major units. */
const DEPOSIT_SUGGESTIONS: Record<string, string> = {
  USD: "25000.00",
  EUR: "5000.00",
  GBP: "4000.00",
};

export interface Persona {
  legalName: string;
  taxId: string;
  country: string;
  /** Currency -> suggested onboarding deposit. */
  deposits: Record<string, string>;
}

const pick = <T,>(list: T[]): T =>
  list[Math.floor(Math.random() * list.length)];

const serial = () => String(1000 + Math.floor(Math.random() * 900000));

/** Builds a persona; `supportedCurrencies` should come from GET /api/v1/currencies so suggestions only cover live rails. */
export function randomPersona(
  supportedCurrencies: string[] = ["USD", "EUR"],
): Persona {
  const country = pick(COUNTRIES);
  const deposits: Record<string, string> = {};
  for (const cur of supportedCurrencies.slice(0, 2)) {
    deposits[cur] = DEPOSIT_SUGGESTIONS[cur] ?? "5000.00";
  }
  return {
    legalName: `${pick(ADJECTIVES)}${pick(NOUNS)} ${pick(INDUSTRIES)} ${pick(country.suffixes)}`,
    taxId: `${country.code}-${country.scheme}-${serial()}`,
    country: country.code,
    deposits,
  };
}
