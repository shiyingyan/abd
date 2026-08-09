package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.model.ProjectModule;
import com.autodeploy.model.ServerInfo;
import com.autodeploy.repository.ProjectModuleRepository;
import com.autodeploy.util.ArtifactResolver;
import com.autodeploy.util.EnvVarUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DeployService {

  private static final Logger log = LoggerFactory.getLogger(DeployService.class);

  @Autowired private SshService sshService;
  @Autowired private ServerInfoService serverInfoService;
  @Autowired private ProjectModuleRepository moduleRepository;

  @Value("${autodeploy.builds-dir}")
  private String buildsDir;

  /**
   * Deploy build artifacts to selected environments' servers. Supports multi-module projects and
   * environment filtering.
   *
   * @param config project config
   * @param workDir build working directory (repo root or buildWorkDir)
   * @param modulePaths selected module paths (null/empty = all modules)
   * @param envIds selected environment IDs (null/empty = all environments)
   * @param logConsumer log output consumer
   * @return true if all deployments succeeded
   */
  public boolean deploy(
      ProjectConfig config,
      String workDir,
      List<String> modulePaths,
      List<Long> envIds,
      Consumer<String> logConsumer) {
    try {
      // Step 1: Resolve module list
      List<String> modules =
          resolveModuleList(config.getId(), modulePaths, config.getLanguageType());
      logConsumer.accept("部署模块: " + String.join(", ", modules));

      // Step 2: Resolve artifacts for each module
      List<File> allArtifacts = new ArrayList<>();
      File workDirFile = new File(workDir);
      for (String modulePath : modules) {
        File moduleDir = ".".equals(modulePath) ? workDirFile : new File(workDirFile, modulePath);
        if (!moduleDir.exists()) {
          logConsumer.accept("模块目录不存在: " + moduleDir.getAbsolutePath());
          continue;
        }
        List<File> artifacts = ArtifactResolver.resolve(moduleDir, config, logConsumer);
        allArtifacts.addAll(artifacts);
      }

      if (allArtifacts.isEmpty()) {
        logConsumer.accept("所有模块均未发现构建产物");
        return false;
      }
      logConsumer.accept("共发现 " + allArtifacts.size() + " 个产物文件");

      // Step 3: Get target servers from associations, filtered by envIds
      List<ProjectEnvServer> associations =
          serverInfoService.listProjectAssociations(config.getId());
      if (associations.isEmpty()) {
        // Fallback to inline config fields for backward compatibility
        if (config.getDeployServerHost() != null
            && !config.getDeployServerHost().trim().isEmpty()) {
          logConsumer.accept("未配置环境服务器关联，使用内联配置 (legacy mode)");
          return deployToServer(
              config,
              config.getDeployServerHost(),
              config.getDeployServerPort() != null ? config.getDeployServerPort() : 22,
              config.getDeployServerUser(),
              config.getDeployAuthEnvKey(),
              allArtifacts,
              logConsumer);
        }
        logConsumer.accept("未配置部署服务器");
        return false;
      }

      // Filter by envIds if specified
      if (envIds != null && !envIds.isEmpty()) {
        associations.removeIf(a -> !envIds.contains(a.getEnvironmentId()));
        logConsumer.accept("按环境筛选后剩余 " + associations.size() + " 个服务器关联");
      }

      // Step 4: Deploy to each server
      boolean allSuccess = true;
      boolean anyDeployed = false;
      for (ProjectEnvServer assoc : associations) {
        if (assoc.getDeployEnabled() != null && !assoc.getDeployEnabled()) {
          logConsumer.accept("跳过服务器 (部署已禁用): id=" + assoc.getServerId());
          continue;
        }
        anyDeployed = true;
        ServerInfo server = serverInfoService.getById(assoc.getServerId());
        if (server == null) {
          logConsumer.accept("服务器不存在 (id=" + assoc.getServerId() + ")，跳过");
          allSuccess = false;
          continue;
        }
        logConsumer.accept("部署到服务器: " + server.getName() + " (" + server.getHost() + ")");
        int serverPort = server.getPort() != null ? server.getPort() : 22;
        boolean ok =
            deployToServer(
                config,
                server.getHost(),
                serverPort,
                server.getUser(),
                server.getAuthEnvKey(),
                allArtifacts,
                logConsumer);
        if (!ok) {
          logConsumer.accept("部署失败: " + server.getName());
          allSuccess = false;
        } else {
          logConsumer.accept("部署成功: " + server.getName());
        }
      }
      if (!anyDeployed) {
        logConsumer.accept("没有启用部署的服务器");
      }
      return allSuccess;
    } catch (Exception e) {
      log.error("Deploy failed for {}", config.getProjectName(), e);
      logConsumer.accept("部署异常: " + e.getMessage());
      return false;
    }
  }

  /**
   * Resolve the module list to deploy. If modulePaths is null/empty, query from database or use
   * single root module.
   */
  private List<String> resolveModuleList(
      Long projectId, List<String> modulePaths, String languageType) {
    if (modulePaths != null && !modulePaths.isEmpty()) {
      return modulePaths;
    }
    // NODE projects don't use modules
    if ("NODE".equals(languageType)) {
      List<String> result = new ArrayList<>();
      result.add(".");
      return result;
    }
    // Query scanned modules from database
    List<ProjectModule> modules =
        moduleRepository.selectList(
            new QueryWrapper<ProjectModule>()
                .eq("project_id", projectId)
                .orderByAsc("module_path"));
    if (modules.isEmpty()) {
      // No scanned modules, use single root module
      List<String> result = new ArrayList<>();
      result.add(".");
      return result;
    }
    List<String> result = new ArrayList<>();
    for (ProjectModule m : modules) {
      result.add(m.getModulePath());
    }
    return result;
  }

  /**
   * Compute the effective target path on the deploy server. If installDir is set, targetPath =
   * installDir + deployTargetPath. Otherwise, use deployTargetPath as-is.
   */
  private String effectiveTargetPath(ProjectConfig config) {
    return joinRelative(config.getInstallDir(), config.getDeployTargetPath());
  }

  /**
   * Compute the effective script directory. scriptDir is relative to installDir, so the effective
   * path is installDir + scriptDir. If installDir is unset, scriptDir is used as-is.
   */
  private String effectiveScriptPath(ProjectConfig config) {
    return joinRelative(config.getInstallDir(), config.getScriptDir());
  }

  /**
   * Join an optional base directory with a relative sub-path. Either may be null/empty. Strips a
   * leading "/" from sub so we never produce double slashes.
   */
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

  private boolean deployToServer(
      ProjectConfig config,
      String host,
      int port,
      String user,
      String authEnvKey,
      List<File> artifacts,
      Consumer<String> logConsumer) {
    try {
      String password = EnvVarUtil.getValue(authEnvKey);
      if (password == null) {
        logConsumer.accept("部署认证环境变量未设置: " + authEnvKey);
        log.error("Deploy auth env var not set: {}", authEnvKey);
        return false;
      }

      String targetPath = effectiveTargetPath(config);
      if (targetPath == null || targetPath.trim().isEmpty()) {
        logConsumer.accept("部署目标路径未配置");
        return false;
      }

      // Step 1: Back up at the existing location first — no mkdir yet.
      // If the target file/dir exists, it's already at its place; if not, backup is a no-op.
      // This avoids creating an empty target directory before a fresh deployment.
      for (File artifact : artifacts) {
        if (artifact.isFile()) {
          String serverTargetFile = targetPath + "/" + artifact.getName();
          backupServerPath(host, port, user, password, serverTargetFile, logConsumer);
        } else if (artifact.isDirectory()) {
          backupServerPath(host, port, user, password, targetPath, logConsumer);
        }
      }

      // Step 2: Ensure the target directory exists. Needed for first-time file deployments
      // (backup above was a no-op) and for folder deployments (backup renamed the old dir).
      sshService.executeCommand(host, port, user, password, "mkdir -p \"" + targetPath + "\"");

      // Step 3: Upload each artifact.
      for (File artifact : artifacts) {
        if (artifact.isFile()) {
          String serverTargetFile = targetPath + "/" + artifact.getName();
          logConsumer.accept(
              "上传文件: " + artifact.getName() + " -> " + host + ":" + serverTargetFile);
          sshService.uploadFile(host, port, user, password, artifact.getAbsolutePath(), targetPath);
        } else if (artifact.isDirectory()) {
          logConsumer.accept("上传目录: " + artifact.getName() + " -> " + host + ":" + targetPath);
          sshService.uploadFile(host, port, user, password, artifact.getAbsolutePath(), targetPath);
        } else {
          logConsumer.accept("未知产物类型，跳过: " + artifact.getAbsolutePath());
        }
      }
      logConsumer.accept("产物上传完成 (" + artifacts.size() + " 个)");
      log.info("Artifacts uploaded to {}:{}", host, targetPath);

      // Execute restart command (once per server)
      String cmd = config.getRestartCommand();
      if (cmd == null || cmd.trim().isEmpty()) {
        cmd = config.getStartCommand();
      }
      if (cmd != null && !cmd.trim().isEmpty()) {
        // scriptDir is relative to installDir
        String scriptPath = effectiveScriptPath(config);
        if (scriptPath != null && !scriptPath.isEmpty()) {
          cmd = "cd \"" + scriptPath + "\" && " + cmd;
        }
        logConsumer.accept("执行命令: " + cmd);
        String output = sshService.executeCommand(host, port, user, password, cmd);
        if (output != null && !output.trim().isEmpty()) {
          logConsumer.accept("命令输出: " + output.trim());
        }
        log.info("Restart command output: {}", output);
      }
      return true;
    } catch (Exception e) {
      log.error("Deploy to {} failed", host, e);
      logConsumer.accept("部署异常: " + e.getMessage());
      return false;
    }
  }

  /**
   * Back up a single path (file or directory) on the remote server by renaming it with a timestamp
   * suffix. Only the exact path is renamed — sibling files/dirs are untouched.
   */
  private void backupServerPath(
      String host,
      int port,
      String user,
      String password,
      String serverPath,
      Consumer<String> logConsumer)
      throws Exception {
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    String normalizedPath =
        serverPath.endsWith("/") ? serverPath.substring(0, serverPath.length() - 1) : serverPath;
    String backupPath = normalizedPath + "_" + timestamp;

    String checkCmd = "if [ -e \"" + normalizedPath + "\" ]; then echo EXISTS; fi";
    String result = sshService.executeCommand(host, port, user, password, checkCmd);
    if (result != null && result.contains("EXISTS")) {
      logConsumer.accept("备份: " + normalizedPath + " -> " + backupPath);
      sshService.executeCommand(
          host, port, user, password, "mv \"" + normalizedPath + "\" \"" + backupPath + "\"");
    } else {
      logConsumer.accept("无现有部署需备份: " + normalizedPath);
    }
  }

  /** Download build artifact from build server to local. */
  public String downloadFromBuildServer(
      String host, int port, String user, String password, String remotePath, String localDir)
      throws Exception {
    sshService.downloadFile(host, port, user, password, remotePath, localDir);
    java.nio.file.Path remote = java.nio.file.Paths.get(remotePath);
    String fileName = remote.getFileName().toString();
    return java.nio.file.Paths.get(localDir, fileName).toString();
  }

  /** Test SSH connection to a server. */
  public boolean testConnection(String host, int port, String user, String authEnvKey) {
    String password = EnvVarUtil.getValue(authEnvKey);
    if (password == null) {
      return false;
    }
    return sshService.testConnection(host, port, user, password);
  }
}
