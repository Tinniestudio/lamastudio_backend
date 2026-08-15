package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.jobs.repository.JobExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records job start / success / failure into job_execution_log.
 * Each method uses REQUIRES_NEW so the log row commits independently
 * of any outer job transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLogger {

    private final JobExecutionLogRepository logRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JobExecutionLog start(String jobName) {
        JobExecutionLog entry = new JobExecutionLog();
        entry.setJobName(jobName);
        entry.setStatus("RUNNING");
        entry.setStartedAt(Instant.now());
        return logRepo.save(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(JobExecutionLog entry, int itemsProcessed) {
        entry.setStatus("SUCCESS");
        entry.setItemsProcessed(itemsProcessed);
        entry.setFinishedAt(Instant.now());
        logRepo.save(entry);
        log.info("[JobLogger] {} completed — {} item(s) processed",
                entry.getJobName(), itemsProcessed);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(JobExecutionLog entry, String error) {
        entry.setStatus("FAILED");
        entry.setErrorMessage(error);
        entry.setFinishedAt(Instant.now());
        logRepo.save(entry);
        log.error("[JobLogger] {} FAILED: {}", entry.getJobName(), error);
    }
}
