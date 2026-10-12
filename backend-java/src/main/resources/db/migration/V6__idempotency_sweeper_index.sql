-- Index the timestamp the hourly IdempotencySweeper deletes on.
-- Plain CREATE INDEX: the table holds at most ~24h of rows, so the lock
-- window is trivial; CONCURRENTLY stalls on pre-existing open transactions.
CREATE INDEX idempotency_records_created_at_idx
    ON public.idempotency_records (created_at);
