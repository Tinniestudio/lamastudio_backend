package com.tinniestudio.api.modules.jobs.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists one row per scheduled-job execution for observability.
 * Table: job_execution_log (created in V41 migration).
 * Does NOT extend BaseEntity because the table has no updated_at column.
 */
@Entity
@Table(
    name = "job_execution_log",
    indexes = {
        @Index(name = "idx_job_log_name",    columnList = "job_name"),
        @Index(name = "idx_job_log_started", columnList = "started_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class JobExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** RUNNING | SUCCESS | FAILED */
    @Column(nullable = false, length = 20)
    private String status = "RUNNING";

    @Column(name = "items_processed", nullable = false)
    private int itemsProcessed = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
