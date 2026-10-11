import axios from "axios";

const baseURL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
// 60s: the free-tier backend scales to zero when idle — the first request
// after idle pays a cold-start penalty and must not time out early.
export const api = axios.create({ baseURL, timeout: 60000 });

const TOKEN_KEY = "wise_token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (axios.isAxiosError(err) && err.response?.status === 401 && getToken()) {
      setToken(null);
      window.location.assign("/login");
    }
    return Promise.reject(err);
  },
);

// ---------- types (backend response shapes) ----------

export interface Entity {
  id: string;
  legal_name: string;
  ubo_tax_id: string;
  country: string;
}

export interface UserProfile {
  id: string;
  email: string;
  is_active: boolean;
  created_at: string;
  entity: Entity;
}

export interface Account {
  id: string;
  entity_id: string;
  account_type: string;
  currency: string;
  balance: string;
}

export type ContractStatus = "INITIATED" | "FUNDED" | "SETTLED" | "CANCELLED";
export type TrackingType = "GITHUB_PR" | "SHIPPING_CARRIER";

export interface ContractView {
  id: string;
  buyer_id: string;
  seller_id: string;
  buyer_name: string;
  seller_name: string;
  role: "buyer" | "seller";
  status: ContractStatus;
  target_currency: string;
  target_amount: string;
  source_currency: string;
  deposit_amount: string | null;
  locked_spot_rate: string | null;
  escrowed_amount: string | null;
  tracking_type: TrackingType;
  tracking_key: string;
  funded_at: string | null;
  created_at: string;
}

export interface Quote {
  source_currency: string;
  target_currency: string;
  target_amount: string;
  mid_market_rate: string;
  principal: string;
  wise_fee: string;
  total_deposit: string;
  bank_spread_cost: string;
  bank_swift_fee: string;
  bank_total_cost: string;
  customer_savings: string;
}

export interface Counterparty {
  email: string;
  entity_id: string;
  legal_name: string;
  country: string;
  same_ubo: boolean;
}

export interface LedgerEntry {
  id: string;
  debit_account_id: string;
  credit_account_id: string;
  amount: string;
  currency: string;
  description: string;
  created_at: string;
}

export interface ApiError {
  error_code?: string;
  code?: string;
  message?: string;
  context?: Record<string, unknown>;
}

export function apiError(err: unknown): ApiError {
  if (axios.isAxiosError(err)) return (err.response?.data as ApiError) ?? {};
  return {};
}

// ---------- calls ----------

export const authApi = {
  register: (body: {
    email: string;
    password: string;
    legal_name: string;
    ubo_tax_id: string;
    country: string;
  }) => api.post<{ access_token: string; user: UserProfile }>("/api/v1/auth/register", body),
  login: (email: string, password: string) =>
    api.post<{ access_token: string }>("/api/v1/auth/login", {
      email,
      password,
    }),
  me: () => api.get<UserProfile>("/api/v1/auth/me"),
};

export const escrowApi = {
  list: () => api.get<ContractView[]>("/api/v1/escrow"),
  get: (id: string) => api.get<ContractView>(`/api/v1/escrow/${id}`),
  audit: (id: string) => api.get<LedgerEntry[]>(`/api/v1/escrow/${id}/audit`),
  quote: (amount: string) =>
    api.get<Quote>("/api/v1/escrow/quote", {
      params: { target_amount: amount, target_currency: "EUR", source_currency: "USD" },
    }),
  counterparty: (email: string) =>
    api.get<Counterparty>("/api/v1/escrow/counterparty", { params: { email } }),
  create: (body: {
    counterparty_email: string;
    target_amount: string;
    tracking_type: TrackingType;
    tracking_key: string;
  }) => api.post<ContractView | { status: "invited" }>("/api/v1/escrow", body),
  fund: (id: string, deposit_amount: string) =>
    api.post<ContractView>(`/api/v1/escrow/${id}/fund`, { deposit_amount }),
  cancel: (id: string) => api.post<ContractView>(`/api/v1/escrow/${id}/cancel`),
};

export const accountsApi = {
  list: () => api.get<Account[]>("/api/v1/accounts"),
};
