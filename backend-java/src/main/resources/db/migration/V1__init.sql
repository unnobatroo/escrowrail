-- V1: initial schema, generated from the JPA entity model on
-- PostgreSQL 16. Hibernate runs in `validate` mode — this file is the
-- single source of truth for DDL (includes the Spring Modulith
-- event_publication outbox table).

CREATE TABLE public.accounts (
    id uuid NOT NULL,
    balance numeric(20,2) NOT NULL,
    currency character varying(3) NOT NULL,
    entity_id uuid,
    type character varying(255) NOT NULL,
    CONSTRAINT accounts_type_check CHECK (((type)::text = ANY ((ARRAY['USER'::character varying, 'ESCROW_VAULT'::character varying, 'WISE_FEE'::character varying, 'WISE_CLEARING'::character varying])::text[])))
);

CREATE TABLE public.app_users (
    id uuid NOT NULL,
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    email character varying(255) NOT NULL,
    entity_id uuid NOT NULL,
    password_hash character varying(255) NOT NULL
);

CREATE TABLE public.escrow_contracts (
    id uuid NOT NULL,
    buyer_account_id uuid,
    buyer_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    deposit_amount numeric(20,2),
    escrowed_amount numeric(20,2),
    funded_at timestamp(6) with time zone,
    seller_id uuid NOT NULL,
    snapshot_spot_rate numeric(20,8),
    source_currency character varying(3) NOT NULL,
    status character varying(255) NOT NULL,
    target_amount numeric(20,2) NOT NULL,
    target_currency character varying(3) NOT NULL,
    tracking_key character varying(255) NOT NULL,
    tracking_type character varying(255) NOT NULL,
    vault_account_id uuid,
    version bigint NOT NULL,
    CONSTRAINT escrow_contracts_status_check CHECK (((status)::text = ANY ((ARRAY['INITIATED'::character varying, 'FUNDED'::character varying, 'SETTLED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT escrow_contracts_tracking_type_check CHECK (((tracking_type)::text = ANY ((ARRAY['GITHUB_PR'::character varying, 'SHIPPING_CARRIER'::character varying])::text[])))
);

CREATE TABLE public.event_publication (
    id uuid NOT NULL,
    completion_date timestamp(6) with time zone,
    event_type character varying(255),
    listener_id character varying(255),
    publication_date timestamp(6) with time zone,
    serialized_event text
);

CREATE TABLE public.idempotency_records (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    response_body text,
    response_status integer NOT NULL
);

CREATE TABLE public.ledger_transactions (
    id uuid NOT NULL,
    amount numeric(20,2) NOT NULL,
    contract_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    credit_account_id uuid NOT NULL,
    currency character varying(3) NOT NULL,
    debit_account_id uuid NOT NULL,
    description character varying(255) NOT NULL
);

CREATE TABLE public.organizations (
    id uuid NOT NULL,
    country character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    legal_name character varying(255) NOT NULL,
    ubo_tax_id character varying(255) NOT NULL
);

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.escrow_contracts
    ADD CONSTRAINT escrow_contracts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.event_publication
    ADD CONSTRAINT event_publication_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.ledger_transactions
    ADD CONSTRAINT ledger_transactions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT uk4n513qjxj7epephrwxk0n4flf UNIQUE (entity_id, type, currency);

ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_email_uq UNIQUE (email);

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT ukol0gjg0uap11mq1y9ug506f1i UNIQUE (idempotency_key);
-- Wise system accounts (entity_id IS NULL): one row per (type, currency)
-- for the singleton fee/clearing accounts. The (entity_id, type,
-- currency) UNIQUE constraint cannot enforce this because Postgres
-- treats NULLs as distinct. ESCROW_VAULT is deliberately excluded —
-- every funded contract gets its own vault row.
CREATE UNIQUE INDEX accounts_system_uq
    ON public.accounts (type, currency)
    WHERE entity_id IS NULL
      AND type IN ('WISE_FEE', 'WISE_CLEARING');

-- Referential integrity + lookup indexes for the hot read paths.
ALTER TABLE ONLY public.ledger_transactions
    ADD CONSTRAINT ledger_debit_account_fk FOREIGN KEY (debit_account_id) REFERENCES public.accounts(id);
ALTER TABLE ONLY public.ledger_transactions
    ADD CONSTRAINT ledger_credit_account_fk FOREIGN KEY (credit_account_id) REFERENCES public.accounts(id);
ALTER TABLE ONLY public.ledger_transactions
    ADD CONSTRAINT ledger_contract_fk FOREIGN KEY (contract_id) REFERENCES public.escrow_contracts(id);

CREATE INDEX ledger_contract_idx ON public.ledger_transactions (contract_id);
CREATE INDEX ledger_debit_idx ON public.ledger_transactions (debit_account_id);
CREATE INDEX ledger_credit_idx ON public.ledger_transactions (credit_account_id);
CREATE INDEX escrow_tracking_idx ON public.escrow_contracts (tracking_type, tracking_key);

-- Singleton Wise system accounts for the supported pair. Seeding them
-- here means services never race to create them at runtime.
INSERT INTO public.accounts (id, entity_id, type, currency, balance) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'WISE_CLEARING', 'USD', 0),
    ('00000000-0000-0000-0000-000000000002', NULL, 'WISE_CLEARING', 'EUR', 0),
    ('00000000-0000-0000-0000-000000000003', NULL, 'WISE_FEE',      'USD', 0),
    ('00000000-0000-0000-0000-000000000004', NULL, 'WISE_FEE',      'EUR', 0);
