-- V39__add_notifications.sql

CREATE TABLE IF NOT EXISTS notification_templates (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL UNIQUE,
    title_template  VARCHAR(500) NOT NULL,
    body_template   TEXT         NOT NULL,
    channel         VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS event_type VARCHAR(100) NOT NULL UNIQUE;
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS title_template VARCHAR(500) NOT NULL;
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS body_template TEXT NOT NULL;
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS channel VARCHAR(20) NOT NULL DEFAULT 'IN_APP';
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE notification_templates ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_notif_template_event ON notification_templates(event_type);

CREATE TABLE IF NOT EXISTS notifications (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type      VARCHAR(100) NOT NULL,
    title           VARCHAR(500) NOT NULL,
    body            TEXT         NOT NULL,
    channel         VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',
    is_read         BOOLEAN      NOT NULL DEFAULT false,
    read_at         TIMESTAMPTZ,
    reference_type  VARCHAR(50),
    reference_id    UUID,
    sent_at         TIMESTAMPTZ,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS event_type VARCHAR(100) NOT NULL;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS title VARCHAR(500) NOT NULL;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS body TEXT NOT NULL;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS channel VARCHAR(20) NOT NULL DEFAULT 'IN_APP';
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS reference_type VARCHAR(50);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS reference_id UUID;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS sent_at TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE notifications ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_notifications_user    ON notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_unread  ON notifications(user_id) WHERE is_read = false;
CREATE INDEX IF NOT EXISTS idx_notifications_created ON notifications(created_at DESC);

CREATE TABLE IF NOT EXISTS notification_preferences (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel     VARCHAR(20) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    is_enabled  BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, channel, event_type)
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS channel VARCHAR(20) NOT NULL;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS event_type VARCHAR(100) NOT NULL;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS is_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE notification_preferences ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_notif_pref_user ON notification_preferences(user_id);
