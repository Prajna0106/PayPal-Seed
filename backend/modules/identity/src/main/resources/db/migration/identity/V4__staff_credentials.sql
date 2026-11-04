-- How staff prove who they are: an email and a password. The password column holds only an Argon2id hash.

ALTER TABLE identity.staff
    ADD COLUMN password_hash    text,
    ADD COLUMN failed_logins    integer NOT NULL DEFAULT 0,
    ADD COLUMN locked_until     timestamptz,
    ADD COLUMN linked_member_id uuid REFERENCES identity.member (id);  -- their own member account, so they can't decide their own case

-- A login names one person. Staff rows without a password (demo personas, directory entries) may still share an email.
CREATE UNIQUE INDEX staff_login_email_unique ON identity.staff (lower(email)) WHERE password_hash IS NOT NULL;
