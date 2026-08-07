package com.autodeploy.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to clean up per-user git temp directories older than 7 days. Runs daily at 3:00
 * AM.
 */
@Component
public class TempDirCleanupTask {

  private static final Logger log = LoggerFactory.getLogger(TempDirCleanupTask.class);
  private static final long RETENTION_DAYS = 7;

  @Scheduled(cron = "0 0 3 * * ?")
  public void cleanup() {
    log.info("Starting temp directory cleanup...");
    String tempBase = System.getProperty("java.io.tmpdir");
    Path autodeployDir = Paths.get(tempBase, "autodeploy");

    if (!Files.exists(autodeployDir)) {
      log.info("No autodeploy temp directory found, skipping cleanup");
      return;
    }

    long cutoffMs = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS).toEpochMilli();
    int deletedCount = 0;

    try {
      // Iterate user directories
      File[] userDirs = autodeployDir.toFile().listFiles(File::isDirectory);
      if (userDirs != null) {
        for (File userDir : userDirs) {
          File[] projectDirs = userDir.listFiles(File::isDirectory);
          if (projectDirs != null) {
            for (File projectDir : projectDirs) {
              if (projectDir.lastModified() < cutoffMs) {
                if (deleteDirectoryRecursive(projectDir)) {
                  deletedCount++;
                  log.debug("Deleted old temp directory: {}", projectDir.getAbsolutePath());
                }
              }
            }
          }
          // Clean up empty user directories
          File[] remaining = userDir.listFiles();
          if (remaining != null && remaining.length == 0) {
            userDir.delete();
          }
        }
      }
    } catch (Exception e) {
      log.error("Error during temp directory cleanup", e);
    }

    log.info(
        "Temp directory cleanup completed. Deleted {} directories older than {} days.",
        deletedCount,
        RETENTION_DAYS);
  }

  private boolean deleteDirectoryRecursive(File dir) {
    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          deleteDirectoryRecursive(file);
        } else {
          file.delete();
        }
      }
    }
    return dir.delete();
  }
}
