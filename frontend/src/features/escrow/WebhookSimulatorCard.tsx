import { useEffect, useState } from "react";
import { ChevronDown, ChevronRight, Radio } from "lucide-react";
import { apiError, demoApi } from "../../api";
import type { ContractView, DemoConfig } from "../../api";
import { money } from "../../shared/currencyFormatter";

/**
 * Developer gateway panel: exposes the sandbox webhook endpoints + tenant
 * signing secrets, and dispatches signed milestone events for the selected
 * FUNDED contract through the real verification path.
 */
export default function WebhookSimulatorCard({
  contracts,
  onSettled,
}: {
  contracts: ContractView[];
  onSettled: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [config, setConfig] = useState<DemoConfig | null>(null);
  const [contractId, setContractId] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ status: string; signature: string } | null>(null);
  const [error, setError] = useState("");

  const funded = contracts.filter((c) => c.status === "FUNDED");

  useEffect(() => {
    demoApi
      .config()
      .then((r) => setConfig(r.data))
      .catch(() => setConfig(null));
  }, []);

  useEffect(() => {
    if (!contractId && funded.length > 0) setContractId(funded[0].id);
  }, [funded, contractId]);

  if (!config) return null;

  const selected = funded.find((c) => c.id === contractId);

  const trigger = async () => {
    if (!selected) return;
    setBusy(true);
    setError("");
    setResult(null);
    try {
      const r = await demoApi.simulateWebhook(selected.id);
      setResult(r.data);
      onSettled();
    } catch (err) {
      setError(apiError(err).message ?? "Webhook dispatch failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <button
        data-testid="gateway-toggle"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-1.5 px-5 py-3 text-left font-semibold text-[#1B1C1D]"
      >
        {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        <Radio size={16} /> Escrowrail Platform Webhook Gateway
        <span className="ml-auto text-xs font-normal text-[#5C685B]">developer sandbox</span>
      </button>
      {open && (
        <div className="space-y-3 border-t border-slate-200 px-5 py-4 text-sm">
          <div className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 font-mono text-xs">
            <span className="text-[#5C685B]">GitHub endpoint</span>
            <span data-testid="gw-github-url">{config.github_webhook_url}</span>
            <span className="text-[#5C685B]">Logistics endpoint</span>
            <span>{config.logistics_webhook_url}</span>
            <span className="text-[#5C685B]">HMAC secret</span>
            <span data-testid="gw-secret" className="break-all">
              {config.github_secret}
            </span>
            <span className="text-[#5C685B]">Logistics secret</span>
            <span className="break-all">{config.logistics_secret}</span>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <select
              data-testid="gw-contract"
              value={contractId}
              onChange={(e) => setContractId(e.target.value)}
              className="rounded-lg border border-slate-300 px-2 py-1.5 text-sm"
            >
              {funded.length === 0 && <option value="">No funded contracts</option>}
              {funded.map((c) => (
                <option key={c.id} value={c.id}>
                  {money(c.target_amount, c.target_currency)} ·{" "}
                  {c.role === "buyer" ? c.seller_name : c.buyer_name}
                </option>
              ))}
            </select>
            <button
              data-testid="gw-trigger"
              onClick={trigger}
              disabled={busy || !selected}
              className="rounded-full bg-[#163300] px-4 py-1.5 text-sm font-semibold text-white hover:bg-[#0f2400] disabled:opacity-40"
            >
              {selected?.tracking_type === "SHIPPING_CARRIER"
                ? "Trigger DHL Delivery"
                : "Trigger GitHub PR Merge"}
            </button>
          </div>

          {result && (
            <div
              data-testid="gw-result"
              className="rounded-lg bg-[#D1E7DD] px-3 py-2 font-mono text-xs text-[#0F5132]"
            >
              HTTP 200 OK · status={result.status} · X-Hub-Signature-256:{" "}
              {result.signature} · Idempotency-Key: accepted
            </div>
          )}
          {error && (
            <div className="rounded-lg bg-[#FFDAD6] px-3 py-2 text-xs text-[#BA1A1A]">
              {error}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
