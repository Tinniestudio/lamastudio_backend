-- V42__add_missing_indexes.sql
-- Query-optimization indexes for background jobs, analytics, and watch history
-- Only adds indexes not already present in prior migrations.

-- ── Upload sessions ─────────────────────────────────────────────────────────
-- Cleanup job: quickly locate expired PENDING sessions without a full scan.
CREATE INDEX IF NOT EXISTS idx_upload_sessions_expires_at
    ON upload_sessions(expires_at)
    WHERE upload_status = 'PENDING';

-- ── Video assets ─────────────────────────────────────────────────────────────
-- Stale-job recovery: find PROCESSING or FAILED assets older than 60 min.
-- V27 added idx_video_assets_status on (processing_status) alone; this composite
-- adds updated_at so the planner can satisfy the WHERE + ORDER BY in one scan.
CREATE INDEX IF NOT EXISTS idx_video_assets_status_updated
    ON video_assets(processing_status, updated_at)
    WHERE processing_status IN ('PROCESSING', 'FAILED');

-- ── User sessions ─────────────────────────────────────────────────────────────
-- Token-expiry cleanup job: scan for sessions past their expiry deadline.
-- V4 has indexes on user_id and a partial for active sessions, but none on expires_at.
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at
    ON user_sessions(expires_at);

-- ── Watch history ─────────────────────────────────────────────────────────────
-- Content-based look-ups (e.g. "who watched this content?", analytics joins).
-- V32 added idx_watch_history_user_id but no content_id-only index.
CREATE INDEX IF NOT EXISTS idx_watch_history_content_id
    ON watch_history(content_id);

-- ── Content analytics daily ───────────────────────────────────────────────────
-- Analytics range queries filter on both content_id AND analytics_date.
-- V40 added separate single-column indexes; a composite satisfies both in one scan.
CREATE INDEX IF NOT EXISTS idx_analytics_content_date
    ON content_analytics_daily(content_id, analytics_date DESC);
