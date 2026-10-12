-- Index the timestamp the hourly IdempotencySweeper deletes on, so the
-- batch DELETE scans an index range instead of the whole table.
CREATE INDEX idempotency_records_created_at_idx
    ON public.idempotency_records (created_at);
