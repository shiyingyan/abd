package com.autodeploy.model;

public enum BuildTaskStatus {
  QUEUED("排队中"),
  BUILDING("构建中"),
  SUCCESS("成功"),
  FAILED("失败"),
  DEPLOYING("部署中"),
  DEPLOY_SUCCESS("部署成功"),
  DEPLOY_FAILED("部署失败");

  private final String label;

  BuildTaskStatus(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  public boolean isFinished() {
    return this == SUCCESS || this == FAILED || this == DEPLOY_SUCCESS || this == DEPLOY_FAILED;
  }
}
