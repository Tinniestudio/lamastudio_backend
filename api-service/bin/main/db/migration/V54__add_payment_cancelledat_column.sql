-- V54__add_payment_cancelled_at_column.sql
-- Track when a user explicitly cancelled their payment (access continues until end_date)

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
