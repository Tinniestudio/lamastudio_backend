package com.tinniestudio.worker.repository;

import com.tinniestudio.worker.entity.ProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
    boolean existsByJobIdAndStatus(String jobId, String status);
    Optional<ProcessingJob> findByJobId(String jobId);
}
