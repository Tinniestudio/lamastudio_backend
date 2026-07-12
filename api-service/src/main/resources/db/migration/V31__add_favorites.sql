CREATE TABLE IF NOT EXISTS favorites (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id  UUID        NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_favorites_user_content UNIQUE (user_id, content_id)
);

CREATE INDEX idx_favorites_user_id ON favorites(user_id);
