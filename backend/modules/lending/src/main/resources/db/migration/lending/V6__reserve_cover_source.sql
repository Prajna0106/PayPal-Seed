-- The loss reserve can pay part of a loan (ADR 0019, ADR 0020). The ledger already shows it when reserve posts the
-- cover, so lending records the same money as a receipt whose source is the reserve and posts nothing again.
ALTER TABLE lending.loan_transaction DROP CONSTRAINT loan_transaction_source_check;
ALTER TABLE lending.loan_transaction ADD CONSTRAINT loan_transaction_source_check CHECK (source IN
    ('AUTOPAY', 'AUTOPAY_RETRY', 'CHECKOUT', 'PAYMENT_LINK', 'RECONCILER', 'PAYPAL', 'SEED', 'RESERVE'));
