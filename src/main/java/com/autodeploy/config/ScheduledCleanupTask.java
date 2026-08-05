package com.autodeploy.config;

import com.autodeploy.service.BuildHistoryService;
import com.autodeploy.service.SystemSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Component
public class ScheduledCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupTask.class);

    @Autowired private BuildHistoryService buildHistoryService;
    @Autowired private SystemSettingsService settingsService;

    @Value("${autodeploy.logs-dir}")
    private String logsDir;

    /**
     * Run daily at 3:00 AM - clean expired build records and log files.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyCleanup() {
        log.info("Starting daily cleanup task...");
        // Clean expired DB records + associated log files
        buildHistoryService.cleanExpiredRecords();
        // Clean orphaned log files
        cleanOrphanedLogFiles();
        log.info("Daily cleanup task completed.");
    }

    private void cleanOrphanedLogFiles() {
        int retentionDays = settingsService.getInt(SystemSettingsService.KEY_LOG_RETENTION, 7);
        File logsDirFile = new File(logsDir);
        if (!logsDirFile.exists()) return;

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        File[] files = logsDirFile.listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null) return;

        int deleted = 0;
        for (File file : files) {
            long fileAgeMs = System.currentTimeMillis() - file.lastModified();
            long fileAgeDays = fileAgeMs / (24 * 60 * 60 * 1000);
            if (fileAgeDays > retentionDays) {
                if (file.delete()) {
                    deleted++;
                }
            }
        }
        log.info("Deleted {} orphaned log files (older than {} days)", deleted, retentionDays);
    }
}
