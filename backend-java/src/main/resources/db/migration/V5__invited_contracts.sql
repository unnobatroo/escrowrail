-- INVITED contracts: a trade can be created for a counterparty that has not
-- registered yet. seller_id stays open and invited_email records the address
-- the contract attaches to when that user signs up.

ALTER TABLE escrow_contracts
    ALTER COLUMN seller_id DROP NOT NULL,
    ADD COLUMN invited_email varchar(320);

ALTER TABLE escrow_contracts
    DROP CONSTRAINT escrow_contracts_status_check,
    ADD CONSTRAINT escrow_contracts_status_check
        CHECK (status IN ('INVITED', 'INITIATED', 'FUNDED', 'SETTLED', 'CANCELLED'));

-- Every row is either bound to a seller entity or waiting on an email —
-- never both, never neither.
ALTER TABLE escrow_contracts
    ADD CONSTRAINT escrow_contracts_seller_or_invite
        CHECK ((seller_id IS NULL) <> (invited_email IS NULL));

CREATE INDEX escrow_invited_email_idx
    ON escrow_contracts (invited_email) WHERE invited_email IS NOT NULL;
