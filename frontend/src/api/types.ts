/** Wire types — backend response shapes. */



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
  email_verified: boolean;
  status: string;
  onboarding_completed: boolean;
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

export type ContractStatus =
  | "INVITED"
  | "INITIATED"
  | "FUNDED"
  | "SETTLED"
  | "CANCELLED";
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
  // Unbundled funding receipt — only present on the fund response.
  gross_debited?: string | null;
  platform_conversion_fee?: string | null;
  net_vaulted?: string | null;
}

export interface Quote {
  source_currency: string;
  target_currency: string;
  target_amount: string;
  mid_market_rate: string;
  principal: string;
  platform_fee: string;
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

export interface DirectoryMember {
  email: string;
  entity_id: string;
  legal_name: string;
  country: string;
}

export interface LedgerEntry {
  id: string;
  debit_account_id: string;
  credit_account_id: string;
  amount: string;
  currency: string;
  description: string;
  contract_id: string | null;
  tx_type: string;
  current_hash: string;
  created_at: string;
}

export interface DemoConfig {
  enabled: boolean;
  buyer_email: string;
  seller_email: string;
  buyer_password: string;
  seller_password: string;
  github_webhook_url: string;
  logistics_webhook_url: string;
}

export interface ApiError {
  error_code?: string;
  code?: string;
  message?: string;
  context?: Record<string, unknown>;
}
