package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsWeeklyRepository;
import com.tinniestudio.api.modules.analytics.util.IsoWeekUtil;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Nightly: rolls content_analytics_daily up into content_analytics_weekly for the
 * ISO week (Monday-Sunday) containing "today" in UTC (Batch 16 #6). Re-running for
 * the same week is idempotent — it recomputes the week's totals from the daily
 * rows each night, so the still-in-progress current week stays up to date without
 * double counting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyAnalyticsRollupJob {

    private final ContentAnalyticsWeeklyRepository weeklyRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "WeeklyAnalyticsRollupJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("WeeklyAnalyticsRollupJob");
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate weekStart = IsoWeekUtil.mondayOf(today);
            LocalDate weekEnd = IsoWeekUtil.sundayOf(today);
            int rows = weeklyRepo.rollupWeek(weekStart, weekEnd);
            jobLogger.success(logEntry, rows);
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
