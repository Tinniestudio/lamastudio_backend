ALTER TABLE contents
    ADD COLUMN IF NOT EXISTS average_rating NUMERIC(3,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS review_count   INTEGER      NOT NULL DEFAULT 0;

CREATE OR REPLACE FUNCTION update_content_review_aggregate()
RETURNS TRIGGER AS $$
DECLARE
    target_content_id UUID;
BEGIN
    target_content_id := COALESCE(NEW.content_id, OLD.content_id);

    UPDATE contents
    SET
        average_rating = COALESCE(
            (SELECT AVG(rating::numeric)
             FROM content_reviews
             WHERE content_id = target_content_id
               AND status = 'APPROVED'),
            0
        ),
        review_count = (
            SELECT COUNT(*)
            FROM content_reviews
            WHERE content_id = target_content_id
              AND status = 'APPROVED'
        )
    WHERE id = target_content_id;

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_content_review_aggregate
    AFTER INSERT OR UPDATE OR DELETE ON content_reviews
    FOR EACH ROW
    EXECUTE FUNCTION update_content_review_aggregate();
