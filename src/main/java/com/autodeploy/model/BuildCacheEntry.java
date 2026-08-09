package com.autodeploy.model;

import java.util.List;

/** Persisted snapshot of the last successful build for a project config, used to skip rebuilds. */
public class BuildCacheEntry {

  private Long configId;
  private String gitHash;
  private List<String> modulePaths;
  private String deploySourcePath;
  private String buildWorkDir;
  private String buildCommand;
  private String languageVersion;
  private String buildMode;
  private long updatedAt;

  public Long getConfigId() {
    return configId;
  }

  public void setConfigId(Long configId) {
    this.configId = configId;
  }

  public String getGitHash() {
    return gitHash;
  }

  public void setGitHash(String gitHash) {
    this.gitHash = gitHash;
  }

  public List<String> getModulePaths() {
    return modulePaths;
  }

  public void setModulePaths(List<String> modulePaths) {
    this.modulePaths = modulePaths;
  }

  public String getDeploySourcePath() {
    return deploySourcePath;
  }

  public void setDeploySourcePath(String deploySourcePath) {
    this.deploySourcePath = deploySourcePath;
  }

  public String getBuildWorkDir() {
    return buildWorkDir;
  }

  public void setBuildWorkDir(String buildWorkDir) {
    this.buildWorkDir = buildWorkDir;
  }

  public String getBuildCommand() {
    return buildCommand;
  }

  public void setBuildCommand(String buildCommand) {
    this.buildCommand = buildCommand;
  }

  public String getLanguageVersion() {
    return languageVersion;
  }

  public void setLanguageVersion(String languageVersion) {
    this.languageVersion = languageVersion;
  }

  public String getBuildMode() {
    return buildMode;
  }

  public void setBuildMode(String buildMode) {
    this.buildMode = buildMode;
  }

  public long getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(long updatedAt) {
    this.updatedAt = updatedAt;
  }
}
