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

  @TableField("selected_modules")
  private String selectedModules;

  @TableField("selected_envs")
  private String selectedEnvs;

  @TableField("auto_deploy")
  private Boolean autoDeploy;

  @TableField("artifact_paths")
  private String artifactPaths;

  @TableField("build_server_host")
  private String buildServerHost;

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

  public String getSelectedModules() {
    return selectedModules;
  }

  public void setSelectedModules(String selectedModules) {
    this.selectedModules = selectedModules;
  }

  public String getSelectedEnvs() {
    return selectedEnvs;
  }

  public void setSelectedEnvs(String selectedEnvs) {
    this.selectedEnvs = selectedEnvs;
  }

  public Boolean getAutoDeploy() {
    return autoDeploy;
  }

  public void setAutoDeploy(Boolean autoDeploy) {
    this.autoDeploy = autoDeploy;
  }

  public String getArtifactPaths() {
    return artifactPaths;
  }

  public void setArtifactPaths(String artifactPaths) {
    this.artifactPaths = artifactPaths;
  }

  public String getBuildServerHost() {
    return buildServerHost;
  }

  public void setBuildServerHost(String buildServerHost) {
    this.buildServerHost = buildServerHost;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
