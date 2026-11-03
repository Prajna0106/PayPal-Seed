-- ADR 0024: a loan that never stood (its payout came back, or the member cancelled in the cooling-off window) gets one
-- calm message, and the messages still waiting for that loan are cancelled instead of sent.
ALTER TABLE engagement.reminder DROP CONSTRAINT reminder_status_check;
ALTER TABLE engagement.reminder ADD CONSTRAINT reminder_status_check CHECK (status IN ('QUEUED', 'SENT', 'SKIPPED_OPT_OUT',
    'SKIPPED_NO_CONTACT', 'FAILED', 'CANCELLED'));
ALTER TABLE engagement.reminder DROP CONSTRAINT reminder_type_check;
ALTER TABLE engagement.reminder ADD CONSTRAINT reminder_type_check CHECK (type IN ('DUE_SOON', 'PAYMENT_FAILED',
    'GRACE_NUDGE', 'AUTOPAY_STOPPED', 'AUTOPAY_ATTENTION', 'HARDSHIP_OFFER', 'HARDSHIP_REMINDER', 'HARDSHIP_UPDATE',
    'WITHDRAWAL_FAILED', 'WITHDRAWAL_RETURNED', 'LOAN_VOIDED'));
-- "Has this loan been closed as void?" is asked before every loan message.
CREATE INDEX reminder_loan_voided ON engagement.reminder (loan_id) WHERE type = 'LOAN_VOIDED';
