package com.autodeploy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("project_module")
public class ProjectModule {

  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("project_id")
  private Long projectId;

  @TableField("module_key")
  private String moduleKey;

  @TableField("module_name")
  private String moduleName;

  @TableField("module_path")
  private String modulePath;

  @TableField("parent_module_key")
  private String parentModuleKey;

  @TableField("scanned_at")
  private LocalDateTime scannedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  public String getModuleKey() {
    return moduleKey;
  }

  public void setModuleKey(String moduleKey) {
    this.moduleKey = moduleKey;
  }

  public String getModuleName() {
    return moduleName;
  }

  public void setModuleName(String moduleName) {
    this.moduleName = moduleName;
  }

  public String getModulePath() {
    return modulePath;
  }

  public void setModulePath(String modulePath) {
    this.modulePath = modulePath;
  }

  public String getParentModuleKey() {
    return parentModuleKey;
  }

  public void setParentModuleKey(String parentModuleKey) {
    this.parentModuleKey = parentModuleKey;
  }

  public LocalDateTime getScannedAt() {
    return scannedAt;
  }

  public void setScannedAt(LocalDateTime scannedAt) {
    this.scannedAt = scannedAt;
  }
}
