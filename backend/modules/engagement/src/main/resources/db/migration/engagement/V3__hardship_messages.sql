-- F5: hardship offers, reminders and updates join the same deduplicated, quiet-hours-aware message queue.
ALTER TABLE engagement.reminder DROP CONSTRAINT reminder_type_check;
ALTER TABLE engagement.reminder ADD CONSTRAINT reminder_type_check CHECK (type IN ('DUE_SOON', 'PAYMENT_FAILED',
    'GRACE_NUDGE', 'AUTOPAY_STOPPED', 'AUTOPAY_ATTENTION', 'HARDSHIP_OFFER', 'HARDSHIP_REMINDER', 'HARDSHIP_UPDATE'));
CREATE INDEX reminder_by_member ON engagement.reminder (member_id, created_at DESC);
