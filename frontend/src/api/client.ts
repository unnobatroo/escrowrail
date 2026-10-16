import axios from "axios";

const baseURL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
// 60s: the free-tier backend scales to zero when idle — the first request
// after idle pays a cold-start penalty and must not time out early.
export const api = axios.create({ baseURL, timeout: 60000 });

/**
 * Demo tooling (account switcher, webhook simulator, sandbox top-ups) is a
 * build-time flag — a production bundle compiled without it never even
 * requests the demo endpoints.
 */
export const isDemoMode = (): boolean =>
  import.meta.env.VITE_ENABLE_DEMO_TOOLS === "true";

const TOKEN_KEY = "escrowrail_token";

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

export type * from "./types";

import type {
  Account,
  ApiError,
  ContractView,
  Counterparty,
  CreateEscrowResponse,
  DemoConfig,
  DirectoryMember,
  LedgerEntry,
  Quote,
  TrackingType,
  UserProfile,
} from "./types";

export function apiError(err: unknown): ApiError {
  if (axios.isAxiosError(err)) {
    if (err.response) return (err.response.data as ApiError) ?? {};
    // No response at all — server unreachable (down, cold start, or CORS).
    if (err.code === "ECONNABORTED")
      return { message: "The server took too long — it may be waking up, try again in a few seconds." };
    return { message: "Can't reach the server right now — it may be restarting. Try again in a moment." };
  }
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
  }) =>
    api.post<{ status: string; email: string }>("/api/v1/auth/register", body),
  verifyEmail: (token: string) =>
    api.post<{ access_token: string; user: UserProfile }>(
      "/api/v1/auth/verify-email",
      { token },
    ),
  resendVerification: (email: string) =>
    api.post<{ status: string }>("/api/v1/auth/resend-verification", { email }),
  forgotPassword: (email: string) =>
    api.post<{ status: string }>("/api/v1/auth/forgot-password", { email }),
  resetPassword: (token: string, newPassword: string) =>
    api.post<{ status: string }>("/api/v1/auth/reset-password", {
      token,
      new_password: newPassword,
    }),
  login: (email: string, password: string) =>
    api.post<{ access_token: string }>("/api/v1/auth/login", {
      email,
      password,
    }),
  me: () => api.get<UserProfile>("/api/v1/auth/me"),
};

export const devApi = {
  latestVerificationToken: (email: string) =>
    api.get<{ token: string }>("/dev/verification-token", {
      params: { email },
    }),
};

export const escrowApi = {
  list: () => api.get<ContractView[]>("/api/v1/escrow"),
  get: (id: string) => api.get<ContractView>(`/api/v1/escrow/${id}`),
  audit: (id: string) => api.get<LedgerEntry[]>(`/api/v1/escrow/${id}/audit`),
  quote: (amount: string, targetCurrency = "EUR", sourceCurrency = "USD") =>
    api.get<Quote>("/api/v1/escrow/quote", {
      params: {
        target_amount: amount,
        target_currency: targetCurrency,
        source_currency: sourceCurrency,
      },
    }),
  counterparty: (email: string) =>
    api.get<Counterparty>("/api/v1/escrow/counterparty", { params: { email } }),
  create: (body: {
    counterparty_email: string;
    target_amount: string;
    target_currency?: string;
    source_currency?: string;
    tracking_type: TrackingType;
    tracking_key: string;
  }) => api.post<CreateEscrowResponse>("/api/v1/escrow", body),
  fund: (id: string, deposit_amount: string) =>
    api.post<ContractView>(`/api/v1/escrow/${id}/fund`, { deposit_amount }),
  cancel: (id: string) => api.post<ContractView>(`/api/v1/escrow/${id}/cancel`),
};

export const accountsApi = {
  list: () => api.get<Account[]>("/api/v1/accounts"),
};

/** Platform-supported currencies — whatever the clearing rails carry. */
export const currenciesApi = {
  supported: () => api.get<{ currencies: string[] }>("/api/v1/currencies"),
};

/** Common member directory — find real registered counterparties. */
export const directoryApi = {
  search: (query: string) =>
    api.get<{ members: DirectoryMember[] }>("/api/v1/directory", {
      params: { query },
    }),
};

/** Self-service profile + one-time onboarding deposits. */
export const profileApi = {
  update: (body: {
    legal_entity_name?: string;
    tax_id?: string;
    initial_deposits?: Record<string, string>;
  }) => api.put<UserProfile>("/api/v1/profile", body),
};

export const ledgerApi = {
  transactions: () => api.get<LedgerEntry[]>("/api/v1/ledger/transactions"),
};

export const demoApi = {
  config: () => api.get<DemoConfig>("/api/v1/demo/config"),
  topup: (currency: string, amount: string) =>
    api.post<Account>(
      "/api/v1/demo/topup",
      { currency, amount },
      // A fresh key per call: two identical top-ups are distinct intents —
      // without this the idempotency filter replays the first response.
      { headers: { "X-Idempotency-Key": crypto.randomUUID() } },
    ),
  simulateWebhook: (contractId: string) =>
    api.post<{ status: string; contract_id: string; signature: string }>(
      "/api/v1/demo/simulate-webhook",
      { contract_id: contractId },
      { headers: { "X-Idempotency-Key": crypto.randomUUID() } },
    ),
};
