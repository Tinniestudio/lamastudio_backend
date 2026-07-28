-- V39__add_notifications.sql

CREATE TABLE notification_templates (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL UNIQUE,
    title_template  VARCHAR(500) NOT NULL,
    body_template   TEXT         NOT NULL,
    channel         VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notif_template_event ON notification_templates(event_type);

CREATE TABLE notifications (
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

CREATE INDEX idx_notifications_user    ON notifications(user_id);
CREATE INDEX idx_notifications_unread  ON notifications(user_id) WHERE is_read = false;
CREATE INDEX idx_notifications_created ON notifications(created_at DESC);

CREATE TABLE notification_preferences (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel     VARCHAR(20) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    is_enabled  BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, channel, event_type)
);

CREATE INDEX idx_notif_pref_user ON notification_preferences(user_id);
