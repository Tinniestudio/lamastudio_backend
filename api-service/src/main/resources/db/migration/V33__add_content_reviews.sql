CREATE TABLE IF NOT EXISTS content_reviews (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id  UUID        NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    rating      SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body        TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_review_user_content UNIQUE (user_id, content_id)
);

CREATE INDEX idx_reviews_content_id ON content_reviews(content_id);
CREATE INDEX idx_reviews_user_id    ON content_reviews(user_id);
CREATE INDEX idx_reviews_status     ON content_reviews(status);
