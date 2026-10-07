-- Add processing_attempts tracking to video_assets
ALTER TABLE video_assets
  ADD COLUMN IF NOT EXISTS processing_attempts INTEGER NOT NULL DEFAULT 0;

-- Processing jobs table for tracking per-attempt lifecycle
CREATE TABLE IF NOT EXISTS processing_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_asset_id   UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE,
    job_id           VARCHAR(255) NOT NULL,
    status           VARCHAR(50)  NOT NULL DEFAULT 'VALIDATING',
    stage_started_at TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    error_message    TEXT,
    attempt          INTEGER NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS video_asset_id UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE;
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS job_id VARCHAR(255) NOT NULL;
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'VALIDATING';
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS stage_started_at TIMESTAMPTZ;
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS attempt INTEGER NOT NULL DEFAULT 1;
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE processing_jobs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE processing_jobs ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX IF NOT EXISTS uidx_processing_jobs_job_id ON processing_jobs(job_id);
CREATE INDEX IF NOT EXISTS idx_processing_jobs_video_asset_id ON processing_jobs(video_asset_id);
CREATE INDEX IF NOT EXISTS idx_processing_jobs_status ON processing_jobs(status);
