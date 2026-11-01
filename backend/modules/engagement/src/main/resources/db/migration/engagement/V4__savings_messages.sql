-- F1: a failed or returned withdrawal is told to the member through the same deduplicated, quiet-hours-aware queue.
-- Such a message belongs to a savings goal, not to a loan, so a message names a loan or a goal (or both).
ALTER TABLE engagement.reminder ALTER COLUMN loan_id DROP NOT NULL;
ALTER TABLE engagement.reminder ADD COLUMN goal_id uuid;
ALTER TABLE engagement.reminder ADD CONSTRAINT reminder_subject_check CHECK (loan_id IS NOT NULL OR goal_id IS NOT NULL);
ALTER TABLE engagement.reminder DROP CONSTRAINT reminder_type_check;
ALTER TABLE engagement.reminder ADD CONSTRAINT reminder_type_check CHECK (type IN ('DUE_SOON', 'PAYMENT_FAILED',
    'GRACE_NUDGE', 'AUTOPAY_STOPPED', 'AUTOPAY_ATTENTION', 'HARDSHIP_OFFER', 'HARDSHIP_REMINDER', 'HARDSHIP_UPDATE',
    'WITHDRAWAL_FAILED', 'WITHDRAWAL_RETURNED'));
CREATE INDEX reminder_by_goal ON engagement.reminder (goal_id, created_at) WHERE goal_id IS NOT NULL;
