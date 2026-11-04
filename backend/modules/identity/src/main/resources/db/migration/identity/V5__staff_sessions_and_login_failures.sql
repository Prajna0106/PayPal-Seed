-- Ending desk sessions, forcing a first password change, one member account per staff login, and failed logins
-- counted per source address in a time window instead of a counter that never decays (ADR 0023).

ALTER TABLE identity.staff
    ADD COLUMN sessions_valid_after     timestamptz,                    -- desk sessions that began earlier are refused
    ADD COLUMN password_change_required boolean NOT NULL DEFAULT false, -- set when someone else chose the password
    DROP COLUMN failed_logins,
    DROP COLUMN locked_until;

CREATE UNIQUE INDEX staff_linked_member_unique ON identity.staff (linked_member_id) WHERE linked_member_id IS NOT NULL;

-- source is the client address the desk saw, or '*' for the account as a whole. A guesser from one address locks only
-- that address; the account-wide row stops guessing spread over many addresses.
CREATE TABLE identity.staff_login_failure (
    staff_id          uuid        NOT NULL REFERENCES identity.staff (id) ON DELETE CASCADE,
    source            text        NOT NULL,
    failures          integer     NOT NULL,
    window_started_at timestamptz NOT NULL,
    locked_until      timestamptz,
    PRIMARY KEY (staff_id, source)
);
