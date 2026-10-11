import axios from "axios";

const baseURL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";
export const api = axios.create({ baseURL, timeout: 15000 });

export interface Entity {
  id: string;
  legal_name: string;
  ubo_tax_id: string;
  country: string;
}

export interface Account {
  id: string;
  entity_id: string;
  account_type: string;
  currency: string;
  balance: string;
}

export interface EscrowContract {
  id: string;
  buyer_id: string;
  seller_id: string;
  buyer_account_id: string | null;
  target_currency: string;
  target_amount: string;
  source_currency: string;
  deposit_amount: string | null;
  locked_spot_rate: string | null;
  escrowed_amount: string | null;
  status: "INITIATED" | "FUNDED" | "SETTLED" | "CANCELLED";
  tracking_type: "GITHUB_PR" | "SHIPPING_CARRIER";
  tracking_key: string;
}

export interface ReadyResponse {
  status: string;
  ledger_balanced: boolean;
  discrepancy_cents: number;
}

export interface WebhookResult {
  status: string;
  contract_id: string | null;
}

export interface LedgerEntry {
  id: string;
  contract_id: string | null;
  debit_account_id: string;
  credit_account_id: string;
  amount: string;
  currency: string;
  description: string;
  created_at: string;
}

export const getReady = () => api.get<ReadyResponse>("/health/ready");
export const getEntities = () => api.get<Entity[]>("/entities");
export const getEntityAccounts = (entityId: string) =>
  api.get<Account[]>(`/entities/${entityId}/accounts`);
export const getAccount = (accountId: string) =>
  api.get<Account>(`/accounts/${accountId}`);
export const getContract = (id: string) =>
  api.get<EscrowContract>(`/contracts/${id}`);
export const getContractLedger = (id: string) =>
  api.get<LedgerEntry[]>(`/contracts/${id}/ledger`);
export const createContract = (body: {
  buyer_id: string;
  seller_id: string;
  target_currency: string;
  target_amount: string;
  source_currency: string;
  tracking_type: string;
  tracking_key: string;
}) => api.post<EscrowContract>("/contracts", body);
export const fundContract = (
  id: string,
  body: { account_id: string; deposit_amount: string },
) => api.post<EscrowContract>(`/contracts/${id}/fund`, body);
export const cancelContract = (id: string) =>
  api.post<EscrowContract>(`/contracts/${id}/cancel`);
export const githubWebhook = (payload: {
  action: string;
  pull_request: { merged: boolean; html_url: string };
}) => api.post<WebhookResult>("/webhooks/github", payload);
export const logisticsWebhook = (payload: {
  carrier: string;
  status: string;
  tracking_key: string;
}) => api.post<WebhookResult>("/webhooks/logistics", payload);
