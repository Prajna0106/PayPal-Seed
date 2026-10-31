-- F4 reminders (FR-12): one row per message ever decided, deduplicated by business key.

-- Messaging preferences engagement owns, including the STOP opt-out.
CREATE TABLE engagement.contact (
    member_id      uuid        PRIMARY KEY,
    phone_e164     text        CHECK (phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    locale         text        NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'es')),
    timezone       text        NOT NULL DEFAULT 'America/Los_Angeles',
    sms_opt_out_at timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE engagement.reminder (
    dedupe_key  text        PRIMARY KEY,          -- 'loan:<id>:v<ver>:s<seq>:<type>': one message per fact
    member_id   uuid        NOT NULL,
    loan_id     uuid        NOT NULL,
    type        text        NOT NULL CHECK (type IN ('DUE_SOON', 'PAYMENT_FAILED', 'GRACE_NUDGE', 'AUTOPAY_STOPPED', 'AUTOPAY_ATTENTION')),
    channel     text        NOT NULL CHECK (channel IN ('SMS', 'IN_APP')),
    locale      text        NOT NULL,
    body        text        NOT NULL,
    send_after  timestamptz NOT NULL,             -- quiet hours already applied
    status      text        NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'SKIPPED_OPT_OUT', 'SKIPPED_NO_CONTACT', 'FAILED')),
    attempts    int         NOT NULL DEFAULT 0,
    last_error  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz
);
CREATE INDEX reminder_due ON engagement.reminder (send_after) WHERE status = 'QUEUED';
CREATE INDEX reminder_by_loan ON engagement.reminder (loan_id, created_at);
