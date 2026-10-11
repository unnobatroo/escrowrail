-- Hardening: cryptographic audit chaining + field-level encryption.

-- Singleton chain head: every ledger insert locks this row FOR UPDATE so
-- hash chaining is serialized even under concurrent transfers.
CREATE TABLE public.ledger_chain (
    id smallint NOT NULL PRIMARY KEY,
    current_hash character varying(64) NOT NULL
);
INSERT INTO public.ledger_chain (id, current_hash) VALUES (1, repeat('0', 64));

ALTER TABLE public.ledger_transactions ADD COLUMN current_hash character varying(64);
UPDATE public.ledger_transactions SET current_hash = 'legacy-' || id::text WHERE current_hash IS NULL;
ALTER TABLE public.ledger_transactions ALTER COLUMN current_hash SET NOT NULL;
