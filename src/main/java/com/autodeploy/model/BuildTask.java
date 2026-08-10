package com.autodeploy.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class BuildTask {

  private String taskId;
  private ProjectConfig configSnapshot;
  private String buildMode; // LOCAL or REMOTE
  private BuildTaskStatus status;
  private LocalDateTime startTime;
  private LocalDateTime endTime;
  private String logFilePath;
  private String currentUser;
  private String errorMessage;

  // Module and environment selection
  private List<String> selectedModules;
  private List<Long> selectedEnvIds;
  private Boolean autoDeploy;
  private boolean skipGitPull;

  // REMOTE mode artifact tracking
  private Map<String, String> remoteArtifactPaths; // modulePath -> absolute path on build server
  private String stagingDir; // local staging directory for downloaded artifacts

  // SSE subscribers for real-time log
  private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  // In-memory log buffer for late subscribers
  private final CopyOnWriteArrayList<String> logBuffer = new CopyOnWriteArrayList<>();
  private static final int MAX_LOG_BUFFER = 10000;

  public BuildTask() {}

  public BuildTask(
      String taskId, ProjectConfig configSnapshot, String buildMode, String currentUser) {
    this.taskId = taskId;
    this.configSnapshot = configSnapshot;
    this.buildMode = buildMode;
    this.currentUser = currentUser;
    this.status = BuildTaskStatus.QUEUED;
  }

  public void addEmitter(SseEmitter emitter) {
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
  }

  public void removeEmitter(SseEmitter emitter) {
    emitters.remove(emitter);
  }

  public void pushLog(String line) {
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(line);
      } catch (Exception e) {
        emitters.remove(emitter);
      }
    }
  }

  public void bufferLogLine(String line) {
    if (logBuffer.size() >= MAX_LOG_BUFFER) {
      logBuffer.remove(0);
    }
    logBuffer.add(line);
  }

  public java.util.List<String> getLogBuffer() {
    return new java.util.ArrayList<>(logBuffer);
  }

  public java.util.List<String> getLogBufferTail(int n) {
    int size = logBuffer.size();
    if (size <= n) {
      return new java.util.ArrayList<>(logBuffer);
    }
    return new java.util.ArrayList<>(logBuffer.subList(size - n, size));
  }

  public void completeEmitters() {
    for (SseEmitter emitter : emitters) {
      try {
        emitter.complete();
      } catch (Exception e) {
        // ignore
      }
    }
    emitters.clear();
  }

  // Getters and setters
  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  public ProjectConfig getConfigSnapshot() {
    return configSnapshot;
  }

  public void setConfigSnapshot(ProjectConfig configSnapshot) {
    this.configSnapshot = configSnapshot;
  }

  public String getBuildMode() {
    return buildMode;
  }

  public void setBuildMode(String buildMode) {
    this.buildMode = buildMode;
  }

  public BuildTaskStatus getStatus() {
    return status;
  }

  public void setStatus(BuildTaskStatus status) {
    this.status = status;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getEndTime() {
    return endTime;
  }

  public void setEndTime(LocalDateTime endTime) {
    this.endTime = endTime;
  }

  public String getLogFilePath() {
    return logFilePath;
  }

  public void setLogFilePath(String logFilePath) {
    this.logFilePath = logFilePath;
  }

  public String getCurrentUser() {
    return currentUser;
  }

  public void setCurrentUser(String currentUser) {
    this.currentUser = currentUser;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public List<String> getSelectedModules() {
    return selectedModules;
  }

  public void setSelectedModules(List<String> selectedModules) {
    this.selectedModules = selectedModules;
  }

  public List<Long> getSelectedEnvIds() {
    return selectedEnvIds;
  }

  public void setSelectedEnvIds(List<Long> selectedEnvIds) {
    this.selectedEnvIds = selectedEnvIds;
  }

  public Boolean getAutoDeploy() {
    return autoDeploy;
  }

  public void setAutoDeploy(Boolean autoDeploy) {
    this.autoDeploy = autoDeploy;
  }

  public boolean isSkipGitPull() {
    return skipGitPull;
  }

  public void setSkipGitPull(boolean skipGitPull) {
    this.skipGitPull = skipGitPull;
  }

  public Map<String, String> getRemoteArtifactPaths() {
    return remoteArtifactPaths;
  }

  public void setRemoteArtifactPaths(Map<String, String> remoteArtifactPaths) {
    this.remoteArtifactPaths = remoteArtifactPaths;
  }

  public String getStagingDir() {
    return stagingDir;
  }

  public void setStagingDir(String stagingDir) {
    this.stagingDir = stagingDir;
  }
}
