-- V41__add_job_execution_log_and_shedlock.sql

CREATE TABLE job_execution_log (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name         VARCHAR(100) NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    status           VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    items_processed  INTEGER      NOT NULL DEFAULT 0,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_log_name    ON job_execution_log(job_name);
CREATE INDEX idx_job_log_started ON job_execution_log(started_at DESC);

CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
