-- App's own record of the Stripe Checkout Session deadline (Payment Session Expiry design,
-- 2026-09-21). Nullable, no backfill: pre-existing PENDING rows have no known real expiry and
-- must simply never match the new fallback-sweep job's query.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

-- PendingPaymentExpiryJob (Task 4) queries exactly
-- WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < now() every 5 minutes.
-- Scoping the index to PENDING keeps it small since most payments settle out of PENDING quickly.
CREATE INDEX IF NOT EXISTS idx_payments_pending_expires_at
    ON payments (expires_at)
    WHERE status = 'PENDING';
