-- V62__add_support_tickets.sql

CREATE TABLE IF NOT EXISTS support_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    subject VARCHAR(255) NOT NULL,
    ticket_number VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table already
-- pre-existed (see V16/V18 production drift fix for why this defensive block exists).
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS subject VARCHAR(255) NOT NULL;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS ticket_number VARCHAR(20) NOT NULL;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'OPEN';
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE support_tickets ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX IF NOT EXISTS idx_support_tickets_ticket_number ON support_tickets(ticket_number);
CREATE INDEX IF NOT EXISTS idx_support_tickets_user_id ON support_tickets(user_id);
CREATE INDEX IF NOT EXISTS idx_support_tickets_status ON support_tickets(status);

CREATE TABLE IF NOT EXISTS ticket_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES support_tickets(id),
    sender_type VARCHAR(10) NOT NULL,
    sender_id UUID NOT NULL,
    body TEXT NOT NULL,
    attachment_url VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS ticket_id UUID NOT NULL REFERENCES support_tickets(id);
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS sender_type VARCHAR(10) NOT NULL;
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS sender_id UUID NOT NULL;
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS body TEXT NOT NULL;
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(2048);
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE ticket_messages ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_ticket_messages_ticket_id ON ticket_messages(ticket_id);
