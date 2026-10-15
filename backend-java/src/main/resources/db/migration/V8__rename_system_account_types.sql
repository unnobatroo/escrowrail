-- V8: rebrand system account types ESCROWRAIL_* -> ESCROWRAIL_*.
-- The partial uniqueness index and both CHECKs reference the literal
-- type values, so they are dropped and recreated around the UPDATE.

DROP INDEX IF EXISTS public.accounts_system_uq;
ALTER TABLE public.accounts DROP CONSTRAINT accounts_type_check;
ALTER TABLE public.accounts DROP CONSTRAINT chk_account_balance_non_negative;

UPDATE public.accounts SET type = 'ESCROWRAIL_CLEARING' WHERE type = 'ESCROWRAIL_CLEARING';
UPDATE public.accounts SET type = 'ESCROWRAIL_FEE'      WHERE type = 'ESCROWRAIL_FEE';

ALTER TABLE public.accounts
    ADD CONSTRAINT accounts_type_check
    CHECK (type IN ('USER', 'ESCROW_VAULT', 'ESCROWRAIL_FEE', 'ESCROWRAIL_CLEARING'));

ALTER TABLE public.accounts
    ADD CONSTRAINT chk_account_balance_non_negative
    CHECK (type = 'ESCROWRAIL_CLEARING' OR balance >= 0);

CREATE UNIQUE INDEX accounts_system_uq
    ON public.accounts (type, currency)
    WHERE entity_id IS NULL
      AND type IN ('ESCROWRAIL_FEE', 'ESCROWRAIL_CLEARING');
