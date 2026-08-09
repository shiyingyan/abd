package com.autodeploy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("project_config")
public class ProjectConfig {

  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("project_key")
  private String projectKey;

  @TableField("project_name")
  private String projectName;

  private String version;

  @TableField("git_repo_url")
  private String gitRepoUrl;

  @TableField("git_branch")
  private String gitBranch;

  @TableField("git_auth_env_key")
  private String gitAuthEnvKey;

  @TableField("build_command")
  private String buildCommand;

  @TableField("build_work_dir")
  private String buildWorkDir;

  @TableField("deploy_server_host")
  private String deployServerHost;

  @TableField("deploy_server_port")
  private Integer deployServerPort;

  @TableField("deploy_server_user")
  private String deployServerUser;

  @TableField("deploy_auth_env_key")
  private String deployAuthEnvKey;

  @TableField("deploy_target_path")
  private String deployTargetPath;

  @TableField("deploy_source_path")
  private String deploySourcePath;

  @TableField("start_command")
  private String startCommand;

  @TableField("restart_command")
  private String restartCommand;

  @TableField("language_type")
  private String languageType;

  @TableField("language_version")
  private String languageVersion;

  @TableField("custom_install_dir")
  private String customInstallDir;

  @TableField("project_dir")
  private String projectDir;

  @TableField("install_dir")
  private String installDir;

  @TableField("script_dir")
  private String scriptDir;

  @TableField("last_module_scan_at")
  private LocalDateTime lastModuleScanAt;

  @TableField("last_module_scan_msg")
  private String lastModuleScanMsg;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getProjectKey() {
    return projectKey;
  }

  public void setProjectKey(String projectKey) {
    this.projectKey = projectKey;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getGitRepoUrl() {
    return gitRepoUrl;
  }

  public void setGitRepoUrl(String gitRepoUrl) {
    this.gitRepoUrl = gitRepoUrl;
  }

  public String getGitBranch() {
    return gitBranch;
  }

  public void setGitBranch(String gitBranch) {
    this.gitBranch = gitBranch;
  }

  public String getGitAuthEnvKey() {
    return gitAuthEnvKey;
  }

  public void setGitAuthEnvKey(String gitAuthEnvKey) {
    this.gitAuthEnvKey = gitAuthEnvKey;
  }

  public String getBuildCommand() {
    return buildCommand;
  }

  public void setBuildCommand(String buildCommand) {
    this.buildCommand = buildCommand;
  }

  public String getBuildWorkDir() {
    return buildWorkDir;
  }

  public void setBuildWorkDir(String buildWorkDir) {
    this.buildWorkDir = buildWorkDir;
  }

  public String getDeployServerHost() {
    return deployServerHost;
  }

  public void setDeployServerHost(String deployServerHost) {
    this.deployServerHost = deployServerHost;
  }

  public Integer getDeployServerPort() {
    return deployServerPort;
  }

  public void setDeployServerPort(Integer deployServerPort) {
    this.deployServerPort = deployServerPort;
  }

  public String getDeployServerUser() {
    return deployServerUser;
  }

  public void setDeployServerUser(String deployServerUser) {
    this.deployServerUser = deployServerUser;
  }

  public String getDeployAuthEnvKey() {
    return deployAuthEnvKey;
  }

  public void setDeployAuthEnvKey(String deployAuthEnvKey) {
    this.deployAuthEnvKey = deployAuthEnvKey;
  }

  public String getDeployTargetPath() {
    return deployTargetPath;
  }

  public void setDeployTargetPath(String deployTargetPath) {
    this.deployTargetPath = deployTargetPath;
  }

  public String getDeploySourcePath() {
    return deploySourcePath;
  }

  public void setDeploySourcePath(String deploySourcePath) {
    this.deploySourcePath = deploySourcePath;
  }

  public String getStartCommand() {
    return startCommand;
  }

  public void setStartCommand(String startCommand) {
    this.startCommand = startCommand;
  }

  public String getRestartCommand() {
    return restartCommand;
  }

  public void setRestartCommand(String restartCommand) {
    this.restartCommand = restartCommand;
  }

  public String getLanguageType() {
    return languageType;
  }

  public void setLanguageType(String languageType) {
    this.languageType = languageType;
  }

  public String getLanguageVersion() {
    return languageVersion;
  }

  public void setLanguageVersion(String languageVersion) {
    this.languageVersion = languageVersion;
  }

  public String getCustomInstallDir() {
    return customInstallDir;
  }

  public void setCustomInstallDir(String customInstallDir) {
    this.customInstallDir = customInstallDir;
  }

  public String getProjectDir() {
    return projectDir;
  }

  public void setProjectDir(String projectDir) {
    this.projectDir = projectDir;
  }

  public String getInstallDir() {
    return installDir;
  }

  public void setInstallDir(String installDir) {
    this.installDir = installDir;
  }

  public String getScriptDir() {
    return scriptDir;
  }

  public void setScriptDir(String scriptDir) {
    this.scriptDir = scriptDir;
  }

  public LocalDateTime getLastModuleScanAt() {
    return lastModuleScanAt;
  }

  public void setLastModuleScanAt(LocalDateTime lastModuleScanAt) {
    this.lastModuleScanAt = lastModuleScanAt;
  }

  public String getLastModuleScanMsg() {
    return lastModuleScanMsg;
  }

  public void setLastModuleScanMsg(String lastModuleScanMsg) {
    this.lastModuleScanMsg = lastModuleScanMsg;
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
