-- Password reset: single-use hashed tokens with a 1-hour TTL, separate
-- from email_verification_tokens so a verification link can never be
-- replayed as a password reset.

CREATE TABLE password_reset_tokens (
    id uuid NOT NULL PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    token_hash varchar(64) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    used_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL
);

-- Lookup is by hash: one row per hash, and an index on user_id keeps the
-- invalidate-on-reissue scan cheap.
CREATE UNIQUE INDEX prt_token_hash_uq ON password_reset_tokens (token_hash);
CREATE INDEX prt_user_idx ON password_reset_tokens (user_id);
