package com.autodeploy.service;

import com.autodeploy.model.BuildRecord;
import com.autodeploy.repository.BuildRecordRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BuildHistoryService {

  private static final Logger log = LoggerFactory.getLogger(BuildHistoryService.class);

  @Autowired private BuildRecordRepository buildRecordRepository;
  @Autowired private SystemSettingsService settingsService;
  @Autowired private BuildService buildService;

  public Page<BuildRecord> queryRecords(
      int pageNum,
      int pageSize,
      String projectName,
      String status,
      LocalDateTime dateFrom,
      LocalDateTime dateTo) {
    QueryWrapper<BuildRecord> wrapper = new QueryWrapper<>();
    if (projectName != null && !projectName.isEmpty()) {
      wrapper.eq("project_name", projectName);
    }
    if (status != null && !status.isEmpty()) {
      wrapper.eq("status", status);
    }
    if (dateFrom != null) {
      wrapper.ge("build_time", dateFrom);
    }
    if (dateTo != null) {
      wrapper.le("build_time", dateTo);
    }
    wrapper.orderByDesc("build_time");
    return buildRecordRepository.selectPage(new Page<>(pageNum, pageSize), wrapper);
  }

  public BuildRecord getById(Long id) {
    return buildRecordRepository.selectById(id);
  }

  public String getLogContent(Long id) {
    BuildRecord record = getById(id);
    if (record == null || record.getLogFilePath() == null) return null;
    File logFile = new File(record.getLogFilePath());
    if (!logFile.exists()) return "日志文件不存在";
    try {
      return new String(java.nio.file.Files.readAllBytes(logFile.toPath()));
    } catch (Exception e) {
      return "读取日志文件失败: " + e.getMessage();
    }
  }

  public Map<String, Object> getLogContentTail(Long id, int tailLines) {
    BuildRecord record = getById(id);
    if (record == null || record.getLogFilePath() == null) {
      Map<String, Object> result = new java.util.HashMap<>();
      result.put("content", "无日志");
      result.put("hasMore", false);
      result.put("totalLines", 0);
      return result;
    }
    File logFile = new File(record.getLogFilePath());
    return buildService.readTailFromFile(logFile, tailLines);
  }

  /** Clean up expired build records and their log files. */
  public void cleanExpiredRecords() {
    int retentionDays = settingsService.getInt(SystemSettingsService.KEY_HISTORY_RETENTION, 90);
    LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
    List<BuildRecord> expired = buildRecordRepository.findExpiredRecords(cutoff);
    for (BuildRecord record : expired) {
      // Delete log file
      if (record.getLogFilePath() != null) {
        File logFile = new File(record.getLogFilePath());
        if (logFile.exists()) {
          if (logFile.delete()) {
            log.debug("Deleted log file: {}", record.getLogFilePath());
          }
        }
      }
      // Delete DB record
      buildRecordRepository.deleteById(record.getId());
    }
    log.info(
        "Cleaned {} expired build records (older than {} days)", expired.size(), retentionDays);
  }

  /** Clean up expired log files (based on log retention days). */
  public void cleanExpiredLogs() {
    int retentionDays = settingsService.getInt(SystemSettingsService.KEY_LOG_RETENTION, 7);
    // Log cleanup is handled by the scheduled task using file system scanning
    log.debug("Log retention: {} days", retentionDays);
  }
}
