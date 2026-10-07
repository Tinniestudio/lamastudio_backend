-- V41__add_job_execution_log_and_shedlock.sql

CREATE TABLE IF NOT EXISTS job_execution_log (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name         VARCHAR(100) NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    status           VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    items_processed  INTEGER      NOT NULL DEFAULT 0,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE job_execution_log ADD COLUMN IF NOT EXISTS job_name VARCHAR(100) NOT NULL;
ALTER TABLE job_execution_log ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE job_execution_log ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ;
ALTER TABLE job_execution_log ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'RUNNING';
ALTER TABLE job_execution_log ADD COLUMN IF NOT EXISTS items_processed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE job_execution_log ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE job_execution_log ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_job_log_name    ON job_execution_log(job_name);
CREATE INDEX IF NOT EXISTS idx_job_log_started ON job_execution_log(started_at DESC);

CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE shedlock ADD COLUMN IF NOT EXISTS lock_until TIMESTAMPTZ NOT NULL;
ALTER TABLE shedlock ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ NOT NULL;
ALTER TABLE shedlock ADD COLUMN IF NOT EXISTS locked_by VARCHAR(255) NOT NULL;
