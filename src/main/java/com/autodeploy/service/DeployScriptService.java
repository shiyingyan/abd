package com.autodeploy.service;

import com.autodeploy.model.BuildTask;
import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.model.ServerInfo;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service to generate deployment scripts for manual deployment. Generates .bat (Windows) or .sh
 * (Linux) scripts that: 1. Download artifacts from build server 2. Upload to deploy servers 3.
 * Execute restart commands
 */
@Service
public class DeployScriptService {

  @Autowired private ServerInfoService serverInfoService;

  /**
   * Generate deployment script content.
   *
   * @param task the build task
   * @param os "windows" or "linux"
   * @return script content
   */
  public String generate(BuildTask task, String os) {
    boolean isWindows = "windows".equalsIgnoreCase(os);
    ProjectConfig config = task.getConfigSnapshot();
    StringBuilder sb = new StringBuilder();

    // Header
    if (isWindows) {
      sb.append("@echo off\r\n");
      sb.append("chcp 65001 >nul\r\n");
      sb.append("echo ==========================================\r\n");
      sb.append("echo 部署脚本 - ").append(config.getProjectName()).append("\r\n");
      sb.append("echo 任务ID: ").append(task.getTaskId()).append("\r\n");
      sb.append("echo ==========================================\r\n\r\n");
    } else {
      sb.append("#!/usr/bin/env bash\n");
      sb.append("set -e\n");
      sb.append("echo \"==========================================\"\n");
      sb.append("echo \"部署脚本 - ").append(config.getProjectName()).append("\"\n");
      sb.append("echo \"任务ID: ").append(task.getTaskId()).append("\"\n");
      sb.append("echo \"==========================================\"\n\n");
    }

    // Get target servers
    List<ProjectEnvServer> associations = serverInfoService.listProjectAssociations(config.getId());
    if (task.getSelectedEnvIds() != null && !task.getSelectedEnvIds().isEmpty()) {
      associations.removeIf(a -> !task.getSelectedEnvIds().contains(a.getEnvironmentId()));
    }

    // Compute target path (installDir + deployTargetPath)
    String targetPath = effectiveTargetPath(config);
    if (targetPath == null || targetPath.isEmpty()) {
      targetPath = "/opt/app";
    }
    String scriptPath = effectiveScriptPath(config);

    // Get artifact paths
    Map<String, String> artifactPaths = task.getRemoteArtifactPaths();
    if (artifactPaths == null || artifactPaths.isEmpty()) {
      sb.append(isWindows ? "echo 未发现构建产物\r\n" : "echo \"未发现构建产物\"\n");
      sb.append(isWindows ? "pause\r\n" : "");
      return sb.toString();
    }

    // Generate script for each server
    int serverIndex = 1;
    for (ProjectEnvServer assoc : associations) {
      if (assoc.getDeployEnabled() != null && !assoc.getDeployEnabled()) {
        continue;
      }
      ServerInfo server = serverInfoService.getById(assoc.getServerId());
      if (server == null) continue;

      int port = server.getPort() != null ? server.getPort() : 22;
      String user = server.getUser();
      String host = server.getHost();

      if (isWindows) {
        sb.append("echo ------------------------------------------\r\n");
        sb.append("echo 部署到服务器 ")
            .append(serverIndex)
            .append(": ")
            .append(server.getName())
            .append(" (")
            .append(host)
            .append(")\r\n");
        sb.append("echo ------------------------------------------\r\n\r\n");
      } else {
        sb.append("echo \"------------------------------------------\"\n");
        sb.append("echo \"部署到服务器 ")
            .append(serverIndex)
            .append(": ")
            .append(server.getName())
            .append(" (")
            .append(host)
            .append(")\"\n");
        sb.append("echo \"------------------------------------------\"\n\n");
      }

      // Step 1: Back up at the existing location (no mkdir yet — if target exists, it's
      // already in place; if not, backup is a no-op). This avoids creating an empty
      // target directory before a fresh deployment.
      sb.append(isWindows ? "\r\necho 备份现有部署...\r\n" : "\necho \"备份现有部署...\"\n");
      for (Map.Entry<String, String> entry : artifactPaths.entrySet()) {
        String artifactPath = entry.getValue();
        String artifactName =
            artifactPath.contains("/")
                ? artifactPath.substring(artifactPath.lastIndexOf('/') + 1)
                : artifactPath;
        String serverTargetFile = targetPath + "/" + artifactName;

        if (isWindows) {
          sb.append("echo 备份 ").append(serverTargetFile).append("...\r\n");
          sb.append("ssh -p ")
              .append(port)
              .append(" ")
              .append(user)
              .append("@")
              .append(host)
              .append(" \"if [ -e \\\"")
              .append(serverTargetFile)
              .append("\\\" ]; then mv \\\"")
              .append(serverTargetFile)
              .append("\\\" \\\"")
              .append(serverTargetFile)
              .append("_$(date +%Y%m%d_%H%M%S)")
              .append("\\\"; fi\"\r\n");
        } else {
          sb.append("echo \"备份 ").append(serverTargetFile).append("...\"\n");
          sb.append("ssh -p ")
              .append(port)
              .append(" ")
              .append(user)
              .append("@")
              .append(host)
              .append(" \"if [ -e \\\"")
              .append(serverTargetFile)
              .append("\\\" ]; then mv \\\"")
              .append(serverTargetFile)
              .append("\\\" \\\"")
              .append(serverTargetFile)
              .append("_$(date +%Y%m%d_%H%M%S)")
              .append("\\\"; fi\"\n");
        }
      }

      // Step 2: Create target directory (needed after backup renames the old dir/file).
      sb.append(isWindows ? "echo 创建目标目录...\r\n" : "echo \"创建目标目录...\"\n");
      sb.append("ssh -p ")
          .append(port)
          .append(" ")
          .append(user)
          .append("@")
          .append(host)
          .append(" \"mkdir -p ")
          .append(targetPath)
          .append("\"\r\n");

      // Step 3: Upload artifacts
      sb.append(isWindows ? "\r\necho 上传产物...\r\n" : "\necho \"上传产物...\"\n");
      for (Map.Entry<String, String> entry : artifactPaths.entrySet()) {
        String artifactPath = entry.getValue();
        sb.append("scp -P ")
            .append(port)
            .append(" \"")
            .append(artifactPath)
            .append("\" ")
            .append(user)
            .append("@")
            .append(host)
            .append(":")
            .append(targetPath)
            .append("/\r\n");
      }

      // Execute restart command (scriptDir is relative to installDir)
      String cmd = config.getRestartCommand();
      if (cmd == null || cmd.isEmpty()) {
        cmd = config.getStartCommand();
      }
      if (cmd != null && !cmd.isEmpty()) {
        if (scriptPath != null && !scriptPath.isEmpty()) {
          cmd = "cd " + scriptPath + " && " + cmd;
        }
        sb.append(isWindows ? "\r\necho 执行重启命令...\r\n" : "\necho \"执行重启命令...\"\n");
        sb.append("ssh -p ")
            .append(port)
            .append(" ")
            .append(user)
            .append("@")
            .append(host)
            .append(" \"")
            .append(cmd.replace("\"", "\\\""))
            .append("\"\r\n");
      }

      if (isWindows) {
        sb.append("\r\necho 服务器 ").append(serverIndex).append(" 部署完成\r\n\r\n");
      } else {
        sb.append("\necho \"服务器 ").append(serverIndex).append(" 部署完成\"\n\n");
      }
      serverIndex++;
    }

    // Footer
    sb.append(
        isWindows
            ? "echo ==========================================\r\n"
            : "echo \"==========================================\"\n");
    sb.append(isWindows ? "echo 所有服务器部署完成！\r\n" : "echo \"所有服务器部署完成！\"\n");
    sb.append(
        isWindows
            ? "echo ==========================================\r\n"
            : "echo \"==========================================\"\n");
    if (isWindows) {
      sb.append("pause\r\n");
    }

    return sb.toString();
  }

  private String effectiveTargetPath(ProjectConfig config) {
    return joinRelative(config.getInstallDir(), config.getDeployTargetPath());
  }

  private String effectiveScriptPath(ProjectConfig config) {
    return joinRelative(config.getInstallDir(), config.getScriptDir());
  }

  private String joinRelative(String baseDir, String subPath) {
    if (baseDir == null || baseDir.trim().isEmpty()) {
      return subPath == null ? null : (subPath.trim().isEmpty() ? null : subPath.trim());
    }
    if (subPath == null || subPath.trim().isEmpty()) {
      return baseDir.trim();
    }
    String normalizedBase = baseDir.trim();
    if (normalizedBase.endsWith("/")) {
      normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
    }
    String normalizedSub = subPath.trim();
    if (normalizedSub.startsWith("/")) {
      normalizedSub = normalizedSub.substring(1);
    }
    return normalizedBase + "/" + normalizedSub;
  }
}
