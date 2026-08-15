-- Add processing_attempts tracking to video_assets
ALTER TABLE video_assets
  ADD COLUMN IF NOT EXISTS processing_attempts INTEGER NOT NULL DEFAULT 0;

-- Processing jobs table for tracking per-attempt lifecycle
CREATE TABLE processing_jobs (
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

CREATE UNIQUE INDEX uidx_processing_jobs_job_id ON processing_jobs(job_id);
CREATE INDEX idx_processing_jobs_video_asset_id ON processing_jobs(video_asset_id);
CREATE INDEX idx_processing_jobs_status ON processing_jobs(status);
