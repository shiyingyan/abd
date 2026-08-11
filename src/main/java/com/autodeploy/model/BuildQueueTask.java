package com.autodeploy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("build_queue_task")
public class BuildQueueTask {

  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("username")
  private String username;

  @TableField("config_id")
  private Long configId;

  @TableField("project_name")
  private String projectName;

  @TableField("target_branch")
  private String targetBranch;

  @TableField("deploy_environments")
  private String deployEnvironments;

  @TableField("deploy_servers")
  private String deployServers;

  @TableField("build_mode")
  private String buildMode;

  @TableField("selected_modules")
  private String selectedModules;

  @TableField("auto_deploy")
  private Boolean autoDeploy;

  @TableField("status")
  private Integer status;

  @TableField("priority")
  private Integer priority;

  @TableField("submit_time")
  private LocalDateTime submitTime;

  @TableField("start_time")
  private LocalDateTime startTime;

  @TableField("completion_time")
  private LocalDateTime completionTime;

  @TableField("build_record_id")
  private Long buildRecordId;

  @TableField("build_task_id")
  private String buildTaskId;

  @TableField("worktree_path")
  private String worktreePath;

  @TableField("error_message")
  private String errorMessage;

  @TableField("log_file_path")
  private String logFilePath;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;

  public static final int STATUS_SUCCESS = 0;
  public static final int STATUS_FAILURE = 1;
  public static final int STATUS_EXECUTING = 2;
  public static final int STATUS_QUEUED = 3;
  public static final int STATUS_CANCELLED = 4;

  public String getStatusLabel() {
    if (status == null) return "";
    switch (status) {
      case STATUS_SUCCESS:
        return "成功";
      case STATUS_FAILURE:
        return "失败";
      case STATUS_EXECUTING:
        return "执行中";
      case STATUS_QUEUED:
        return "排队中";
      case STATUS_CANCELLED:
        return "已取消";
      default:
        return "未知";
    }
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public Long getConfigId() {
    return configId;
  }

  public void setConfigId(Long configId) {
    this.configId = configId;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getTargetBranch() {
    return targetBranch;
  }

  public void setTargetBranch(String targetBranch) {
    this.targetBranch = targetBranch;
  }

  public String getDeployEnvironments() {
    return deployEnvironments;
  }

  public void setDeployEnvironments(String deployEnvironments) {
    this.deployEnvironments = deployEnvironments;
  }

  public String getDeployServers() {
    return deployServers;
  }

  public void setDeployServers(String deployServers) {
    this.deployServers = deployServers;
  }

  public String getBuildMode() {
    return buildMode;
  }

  public void setBuildMode(String buildMode) {
    this.buildMode = buildMode;
  }

  public String getSelectedModules() {
    return selectedModules;
  }

  public void setSelectedModules(String selectedModules) {
    this.selectedModules = selectedModules;
  }

  public Boolean getAutoDeploy() {
    return autoDeploy;
  }

  public void setAutoDeploy(Boolean autoDeploy) {
    this.autoDeploy = autoDeploy;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer status) {
    this.status = status;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public LocalDateTime getSubmitTime() {
    return submitTime;
  }

  public void setSubmitTime(LocalDateTime submitTime) {
    this.submitTime = submitTime;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getCompletionTime() {
    return completionTime;
  }

  public void setCompletionTime(LocalDateTime completionTime) {
    this.completionTime = completionTime;
  }

  public Long getBuildRecordId() {
    return buildRecordId;
  }

  public void setBuildRecordId(Long buildRecordId) {
    this.buildRecordId = buildRecordId;
  }

  public String getBuildTaskId() {
    return buildTaskId;
  }

  public void setBuildTaskId(String buildTaskId) {
    this.buildTaskId = buildTaskId;
  }

  public String getWorktreePath() {
    return worktreePath;
  }

  public void setWorktreePath(String worktreePath) {
    this.worktreePath = worktreePath;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getLogFilePath() {
    return logFilePath;
  }

  public void setLogFilePath(String logFilePath) {
    this.logFilePath = logFilePath;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
