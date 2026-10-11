import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { accountsApi, escrowApi } from "../api";
import type { Account, ContractView } from "../api";
import NewTradeModal from "../components/NewTradeModal";
import Shell from "../components/Shell";
import { money, yieldAccrued } from "../format";

type Filter = "all" | "active" | "completed";

const STATUS_STYLE: Record<string, string> = {
  INITIATED: "bg-[#FFF3CD] text-[#7A5800]",
  FUNDED: "bg-[#CFF4FC] text-[#084298]",
  SETTLED: "bg-[#D1E7DD] text-[#0F5132]",
  CANCELLED: "bg-[#E2E3E5] text-[#41464B]",
};

const ACTIVE = new Set(["INITIATED", "FUNDED"]);

export default function Dashboard() {
  const [contracts, setContracts] = useState<ContractView[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [filter, setFilter] = useState<Filter>("all");
  const [showNew, setShowNew] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();

  const reload = () => {
    Promise.all([escrowApi.list(), accountsApi.list()])
      .then(([c, a]) => {
        setContracts(c.data);
        setAccounts(a.data);
      })
      .catch(() => {});
  };

  useEffect(reload, []);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 100);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (params.get("new")) {
      setShowNew(true);
      params.delete("new");
      setParams(params, { replace: true });
    }
  }, [params, setParams]);

  const cash = (cur: string) =>
    accounts.find((a) => a.currency === cur)?.balance ?? "0.00";

  const safeguarded = useMemo(
    () =>
      contracts
        .filter((c) => c.status === "FUNDED")
        .reduce((s, c) => s + Number(c.escrowed_amount ?? 0), 0),
    [contracts],
  );
  const accruedYield = useMemo(
    () =>
      contracts
        .filter((c) => c.status === "FUNDED" && c.funded_at)
        .reduce((s, c) => s + yieldAccrued(c.escrowed_amount!, c.funded_at!, now), 0),
    [contracts, now],
  );

  const rows = contracts.filter((c) =>
    filter === "all"
      ? true
      : filter === "active"
        ? ACTIVE.has(c.status)
        : !ACTIVE.has(c.status),
  );

  return (
    <Shell>
      <div className="mb-6 grid grid-cols-3 gap-4">
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="text-sm text-[#5C685B]">Available Cash</p>
          <p data-testid="metric-cash-usd" className="mt-1 text-2xl font-bold text-[#0E150E]">
            {money(cash("USD"), "USD")}
          </p>
          <p data-testid="metric-cash-eur" className="text-lg font-semibold text-[#5C685B]">
            {money(cash("EUR"), "EUR")}
          </p>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="text-sm text-[#5C685B]">Safeguarded in Escrow</p>
          <p data-testid="metric-vault" className="mt-1 text-2xl font-bold text-[#084298]">
            {money(safeguarded.toFixed(2), "EUR")}
          </p>
          <p className="text-xs text-[#5C685B]">Segregated EMI vault</p>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="text-sm text-[#5C685B]">Accrued Float Yield</p>
          <p data-testid="metric-yield" className="mt-1 text-2xl font-bold text-[#0F5132]">
            {money(accruedYield.toFixed(4), "EUR")}
          </p>
          <p className="text-xs text-[#5C685B]">3.5% ECB base rate</p>
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white">
        <div className="flex items-center gap-2 border-b border-slate-200 px-5 py-3">
          <h2 className="mr-4 font-semibold text-[#0E150E]">My Trades</h2>
          {(["all", "active", "completed"] as Filter[]).map((f) => (
            <button
              key={f}
              data-testid={`tab-${f}`}
              onClick={() => setFilter(f)}
              className={`rounded-full px-3 py-1 text-sm capitalize ${
                filter === f
                  ? "bg-[#163300] text-white"
                  : "text-[#5C685B] hover:bg-slate-100"
              }`}
            >
              {f}
            </button>
          ))}
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-[#5C685B]">
              <th className="px-5 py-2 font-medium">Counterparty</th>
              <th className="px-5 py-2 font-medium">Invoice</th>
              <th className="px-5 py-2 font-medium">Role</th>
              <th className="px-5 py-2 font-medium">Status</th>
              <th className="px-5 py-2" />
            </tr>
          </thead>
          <tbody data-testid="trades-body">
            {rows.map((c) => (
              <tr
                key={c.id}
                data-testid={`trade-row-${c.id}`}
                className="border-b border-slate-100 last:border-0"
              >
                <td className="px-5 py-3 font-medium text-[#0E150E]">
                  {c.role === "buyer" ? c.seller_name : c.buyer_name}
                </td>
                <td className="px-5 py-3">
                  {money(c.target_amount, c.target_currency)}
                </td>
                <td className="px-5 py-3 capitalize text-[#5C685B]">{c.role}</td>
                <td className="px-5 py-3">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[c.status]}`}
                  >
                    {c.status}
                  </span>
                </td>
                <td className="px-5 py-3 text-right">
                  <button
                    data-testid={`open-${c.id}`}
                    onClick={() => navigate(`/contracts/${c.id}`)}
                    className="text-sm font-medium text-[#163300] underline"
                  >
                    View
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-5 py-8 text-center text-[#5C685B]">
                  No trades yet — start one with “New Escrow Trade”.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showNew && (
        <NewTradeModal
          onClose={() => setShowNew(false)}
          onCreated={(c) => {
            setShowNew(false);
            reload();
            navigate(`/contracts/${c.id}`);
          }}
        />
      )}
    </Shell>
  );
}
