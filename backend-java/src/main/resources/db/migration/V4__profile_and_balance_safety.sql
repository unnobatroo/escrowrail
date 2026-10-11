-- V4: profile onboarding flag + hard zero-debt constraint.
-- Only the platform clearing account may ever be overdrawn (it funds the
-- sandbox grants and FX legs); every other account — user, vault, fee —
-- is physically barred from a negative balance at the database layer.

ALTER TABLE public.accounts
    ADD CONSTRAINT chk_account_balance_non_negative
    CHECK (type = 'WISE_CLEARING' OR balance >= 0);

ALTER TABLE public.app_users
    ADD COLUMN onboarding_completed boolean NOT NULL DEFAULT false;
