package com.autodeploy.config;

import com.autodeploy.model.BuildQueueTask;
import com.autodeploy.repository.BuildQueueRepository;
import com.autodeploy.service.BuildHistoryService;
import com.autodeploy.service.SystemSettingsService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledCleanupTask {

  private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupTask.class);

  @Autowired private BuildHistoryService buildHistoryService;
  @Autowired private SystemSettingsService settingsService;
  @Autowired private BuildQueueRepository buildQueueRepository;

  @Value("${autodeploy.logs-dir}")
  private String logsDir;

  /** Run daily at 3:00 AM - clean expired build records and log files. */
  @Scheduled(cron = "0 0 3 * * ?")
  public void dailyCleanup() {
    log.info("Starting daily cleanup task...");
    // Clean expired DB records + associated log files
    buildHistoryService.cleanExpiredRecords();
    // Clean orphaned log files
    cleanOrphanedLogFiles();
    // Clean stale worktree directories
    cleanStaleWorktrees();
    // Clean old queue task records
    cleanOldQueueTasks();
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

  private void cleanStaleWorktrees() {
    File worktreeBase = new File(System.getProperty("java.io.tmpdir"), "autodeploy-worktrees");
    if (!worktreeBase.exists()) return;

    File[] dirs = worktreeBase.listFiles(File::isDirectory);
    if (dirs == null) return;

    int deleted = 0;
    for (File dir : dirs) {
      long ageDays = (System.currentTimeMillis() - dir.lastModified()) / (24 * 60 * 60 * 1000L);
      if (ageDays > 1) {
        try {
          Files.walk(dir.toPath())
              .sorted(Comparator.reverseOrder())
              .map(Path::toFile)
              .forEach(File::delete);
          deleted++;
        } catch (Exception e) {
          log.warn("Failed to clean worktree dir: {}", dir.getAbsolutePath(), e);
        }
      }
    }
    if (deleted > 0) {
      log.info("Cleaned {} stale worktree directories", deleted);
    }
  }

  private void cleanOldQueueTasks() {
    int retentionDays = settingsService.getInt(SystemSettingsService.KEY_LOG_RETENTION, 7);
    LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    wrapper
        .in(
            "status",
            BuildQueueTask.STATUS_SUCCESS,
            BuildQueueTask.STATUS_FAILURE,
            BuildQueueTask.STATUS_CANCELLED)
        .lt("completion_time", cutoff);
    int deleted = buildQueueRepository.delete(wrapper);
    if (deleted > 0) {
      log.info("Cleaned {} old queue task records (older than {} days)", deleted, retentionDays);
    }
  }
}
