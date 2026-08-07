package com.autodeploy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("build_records")
public class BuildRecord {

  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("project_name")
  private String projectName;

  @TableField("package_name")
  private String packageName;

  private String version;

  @TableField("repo_url")
  private String repoUrl;

  @TableField("build_time")
  private LocalDateTime buildTime;

  @TableField("build_user")
  private String buildUser;

  private String status;

  @TableField("build_mode")
  private String buildMode;

  @TableField("log_file_path")
  private String logFilePath;

  @TableField("deploy_status")
  private String deployStatus;

  @TableField("created_at")
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getRepoUrl() {
    return repoUrl;
  }

  public void setRepoUrl(String repoUrl) {
    this.repoUrl = repoUrl;
  }

  public LocalDateTime getBuildTime() {
    return buildTime;
  }

  public void setBuildTime(LocalDateTime buildTime) {
    this.buildTime = buildTime;
  }

  public String getBuildUser() {
    return buildUser;
  }

  public void setBuildUser(String buildUser) {
    this.buildUser = buildUser;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getBuildMode() {
    return buildMode;
  }

  public void setBuildMode(String buildMode) {
    this.buildMode = buildMode;
  }

  public String getLogFilePath() {
    return logFilePath;
  }

  public void setLogFilePath(String logFilePath) {
    this.logFilePath = logFilePath;
  }

  public String getDeployStatus() {
    return deployStatus;
  }

  public void setDeployStatus(String deployStatus) {
    this.deployStatus = deployStatus;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
