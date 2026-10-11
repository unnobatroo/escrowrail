-- Email verification: accounts register PENDING_VERIFICATION and must
-- activate via a single-use hashed token before they can authenticate.

ALTER TABLE app_users
    ADD COLUMN email_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN status varchar(24) NOT NULL DEFAULT 'PENDING_VERIFICATION';

-- Users that predate verification are trusted — grandfather them in.
UPDATE app_users SET email_verified = true, status = 'ACTIVE';

ALTER TABLE app_users
    ADD CONSTRAINT app_users_status_check
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED'));

CREATE TABLE email_verification_tokens (
    id uuid NOT NULL PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    token_hash varchar(64) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    used_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL
);

-- Verification looks tokens up by hash: index the hash, and keep one
-- active token per user cheap to find for the resend flow.
CREATE INDEX evt_token_hash_idx ON email_verification_tokens (token_hash);
CREATE INDEX evt_user_idx ON email_verification_tokens (user_id);
