package com.autodeploy.service;

import com.autodeploy.config.BuildThreadPoolManager;
import com.autodeploy.model.*;
import com.autodeploy.repository.BuildRecordRepository;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class BuildService {

  private static final Logger log = LoggerFactory.getLogger(BuildService.class);

  @Autowired private BuildThreadPoolManager poolManager;
  @Autowired private GitService gitService;
  @Autowired private LanguageRuntimeService runtimeService;
  @Autowired private ConfigService configService;
  @Autowired private SystemSettingsService settingsService;
  @Autowired private BuildRecordRepository buildRecordRepository;
  @Autowired private DeployService deployService;
  @Autowired private BuildCacheService buildCacheService;

  @Value("${autodeploy.builds-dir}")
  private String buildsDir;

  @Value("${autodeploy.logs-dir}")
  private String logsDir;

  private final ConcurrentHashMap<String, BuildTask> taskMap = new ConcurrentHashMap<>();

  /** Start a build for a project config. */
  public String startBuild(
      Long configId,
      String buildMode,
      String username,
      java.util.List<String> modulePaths,
      java.util.List<Long> envIds,
      Boolean autoDeploy,
      boolean skipGitPull,
      String selectedBranch) {
    ProjectConfig snapshot = configService.getSnapshot(configId);
    if (snapshot == null) {
      return null;
    }

    String taskId = UUID.randomUUID().toString().substring(0, 8);
    BuildTask task = new BuildTask(taskId, snapshot, buildMode, username);
    task.setStartTime(LocalDateTime.now());
    task.setSelectedModules(modulePaths);
    task.setSelectedEnvIds(envIds);
    // For LOCAL mode, always auto-deploy; for REMOTE, use the provided value
    task.setAutoDeploy("LOCAL".equals(buildMode) || autoDeploy == null || autoDeploy);
    task.setSkipGitPull(skipGitPull);
    task.setSelectedBranch(selectedBranch);

    // Create log file
    String logFileName =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            + "_"
            + snapshot.getProjectName()
            + "_"
            + taskId
            + ".log";
    String logFilePath = Paths.get(logsDir, logFileName).toString();
    task.setLogFilePath(logFilePath);

    taskMap.put(taskId, task);
    poolManager.submit(() -> executeBuild(task));

    return taskId;
  }

  /** Execute the build task (runs in thread pool). */
  private void executeBuild(BuildTask task) {
    ProjectConfig config = task.getConfigSnapshot();
    task.setStatus(BuildTaskStatus.BUILDING);

    // Track original branch and repo dir for restoration after build+deploy
    String originalBranch = null;
    File repoDir = null;

    try (PrintWriter logWriter =
        new PrintWriter(
            // Always UTF-8: log files are read back as UTF-8 by readTailFromFile and streamed to
            // the frontend, so writing in GBK on Windows caused garbled Chinese text.
            new OutputStreamWriter(new FileOutputStream(task.getLogFilePath()), "UTF-8"), true)) {

      try {

        // Step 1: Git clone/pull (use configured projectDir, or per-user temp directory)
        String userGitDir;
        if (config.getProjectDir() != null && !config.getProjectDir().trim().isEmpty()) {
          userGitDir = config.getProjectDir().trim();
        } else {
          String tempBase = System.getProperty("java.io.tmpdir");
          userGitDir =
              java.nio.file.Paths.get(
                      tempBase, "autodeploy", task.getCurrentUser(), config.getProjectName())
                  .toString();
        }
        repoDir = new File(userGitDir);
        boolean repoExists = repoDir.exists() && new File(repoDir, ".git").exists();
        if (task.isSkipGitPull()) {
          logLine(task, logWriter, "=== 跳过 Git pull（使用本地已有代码） ===");
          logLine(task, logWriter, "Git directory: " + userGitDir);
        } else {
          // Handle branch switching if a different branch is selected
          String selectedBranch = task.getSelectedBranch();
          if (repoExists && selectedBranch != null && !selectedBranch.trim().isEmpty()) {
            String currentBranch = gitService.getCurrentBranch(repoDir);
            if (currentBranch != null && !currentBranch.equals(selectedBranch)) {
              logLine(
                  task, logWriter, "=== 分支切换: " + currentBranch + " -> " + selectedBranch + " ===");
              // Check for uncommitted changes before switching
              if (gitService.hasUncommittedChanges(repoDir)) {
                String errorMsg = "本地仓库有未提交的修改，无法切换到分支 " + selectedBranch + "。请先提交或暂存代码。";
                logLine(task, logWriter, "=== 构建终止: " + errorMsg + " ===");
                task.setStatus(BuildTaskStatus.FAILED);
                task.setErrorMessage(errorMsg);
                saveBuildRecord(task, "FAIL", "FAIL");
                return;
              }
              // Record original branch for restoration after build+deploy
              originalBranch = currentBranch;
              // Checkout the selected branch
              boolean checkoutOk = gitService.checkoutBranch(repoDir, selectedBranch);
              if (!checkoutOk) {
                String errorMsg = "切换到分支 " + selectedBranch + " 失败";
                logLine(task, logWriter, "=== 构建终止: " + errorMsg + " ===");
                task.setStatus(BuildTaskStatus.FAILED);
                task.setErrorMessage(errorMsg);
                saveBuildRecord(task, "FAIL", "FAIL");
                return;
              }
              // Override config's branch so cloneOrPull stays on selected branch
              config.setGitBranch(selectedBranch);
              logLine(
                  task,
                  logWriter,
                  "已切换到分支: " + selectedBranch + "（构建完成后将切回 " + originalBranch + "）");
            } else if (currentBranch != null && currentBranch.equals(selectedBranch)) {
              logLine(task, logWriter, "=== 当前分支与目标分支一致: " + currentBranch + " ===");
              // Ensure config branch matches
              config.setGitBranch(selectedBranch);
            }
          }

          if (repoExists) {
            logLine(task, logWriter, "=== Git pull (repository already exists) ===");
          } else {
            logLine(task, logWriter, "=== Git clone (repository not found, will clone) ===");
          }
          logLine(task, logWriter, "Git directory: " + userGitDir);
          repoDir = gitService.cloneOrPull(config, userGitDir);
        }
        logLine(task, logWriter, "Git repository ready: " + repoDir.getAbsolutePath());

        // Step 2: Build command with language version switching
        String buildCmd =
            runtimeService.buildFullCommand(
                LanguageType.fromString(config.getLanguageType()),
                config.getLanguageVersion(),
                config.getBuildCommand(),
                config.getCustomInstallDir());

        // Step 3: Determine working directory (resolve relative to git repo root)
        File workDir;
        if (config.getBuildWorkDir() != null && !config.getBuildWorkDir().trim().isEmpty()) {
          workDir = new File(repoDir, config.getBuildWorkDir().trim());
        } else {
          workDir = repoDir;
        }

        // Step 3.4: Build-cache check — if git HEAD and config match the last successful build
        // and the working directory is still present, skip npm install + build and go straight
        // to deploy. This avoids redundant compilations when code hasn't changed.
        String currentGitHash = gitService.getHeadHash(repoDir);
        BuildCacheEntry cacheEntry = buildCacheService.get(config.getId());
        boolean cacheHit =
            buildCacheService.shouldSkipBuild(
                cacheEntry,
                currentGitHash,
                task.getSelectedModules(),
                config.getBuildWorkDir(),
                config.getBuildCommand(),
                config.getLanguageVersion(),
                config.getDeploySourcePath(),
                task.getBuildMode(),
                workDir);
        if (cacheHit) {
          // Verify build artifacts still exist on disk before skipping the build
          java.util.List<String> modules =
              task.getSelectedModules() != null && !task.getSelectedModules().isEmpty()
                  ? task.getSelectedModules()
                  : java.util.Collections.singletonList(".");
          boolean artifactsExist = true;
          for (String modulePath : modules) {
            File moduleDir = ".".equals(modulePath) ? workDir : new File(workDir, modulePath);
            if (!moduleDir.exists()) {
              artifactsExist = false;
              break;
            }
            java.util.List<File> artifacts =
                com.autodeploy.util.ArtifactResolver.resolve(
                    moduleDir, config, line -> {});
            if (artifacts.isEmpty()) {
              artifactsExist = false;
              break;
            }
          }
          if (!artifactsExist) {
            cacheHit = false;
            buildCacheService.evict(config.getId());
            logLine(task, logWriter, "=== 构建缓存命中但产物已不存在，缓存失效，重新构建 ===");
          }
        }
        if (cacheHit) {
          logLine(task, logWriter, "=== 命中构建缓存 ===");
          logLine(task, logWriter, "Git HEAD: " + currentGitHash.substring(0, 8) + " 未变更");
          logLine(
              task,
              logWriter,
              "上次构建时间: "
                  + LocalDateTime.ofInstant(
                      java.time.Instant.ofEpochMilli(cacheEntry.getUpdatedAt()),
                      java.time.ZoneId.systemDefault()));
          logLine(task, logWriter, "跳过 npm install / 编译步骤，直接进入部署阶段");
          task.setStatus(BuildTaskStatus.SUCCESS);
        } else {
          // Cache miss — run npm install + build
          String cacheReason = diagnoseCacheMiss(cacheEntry, currentGitHash, task, config, workDir);
          logLine(task, logWriter, "=== 构建缓存未命中 (" + cacheReason + ")，执行完整构建 ===");

          // Step 3.5: For Node projects, check if npm install is needed
          if ("NODE".equalsIgnoreCase(config.getLanguageType())) {
            File packageJson = new File(workDir, "package.json");
            File nodeModules = new File(workDir, "node_modules");
            boolean needsInstall = false;

            if (packageJson.isFile()) {
              if (!nodeModules.isDirectory()) {
                needsInstall = true;
                logLine(task, logWriter, "node_modules not found, npm install required");
              } else if (packageJson.lastModified() > nodeModules.lastModified()) {
                needsInstall = true;
                logLine(task, logWriter, "package.json has changed, npm install required");
              } else {
                logLine(task, logWriter, "package.json unchanged, skipping npm install");
              }
            }

            if (needsInstall) {
              String npmInstallCmd =
                  runtimeService.buildFullCommand(
                      LanguageType.fromString(config.getLanguageType()),
                      config.getLanguageVersion(),
                      "npm install",
                      config.getCustomInstallDir());
              logLine(task, logWriter, "=== npm install ===");
              int installExit = executeProcess(task, logWriter, npmInstallCmd, workDir);
              if (installExit != 0) {
                logLine(
                    task, logWriter, "=== npm install FAILED (exit code: " + installExit + ") ===");
                task.setStatus(BuildTaskStatus.FAILED);
                task.setErrorMessage("npm install exit code: " + installExit);
                saveBuildRecord(task, "FAIL", "FAIL");
                return;
              }
              logLine(task, logWriter, "=== npm install completed ===");
            }
          }

          logLine(task, logWriter, "=== Build command: " + buildCmd + " ===");
          logLine(task, logWriter, "=== Working dir: " + workDir.getAbsolutePath() + " ===");

          // Step 4: Execute build
          int exitCode = executeProcess(task, logWriter, buildCmd, workDir);
          if (exitCode != 0) {
            logLine(task, logWriter, "=== Build FAILED (exit code: " + exitCode + ") ===");
            task.setStatus(BuildTaskStatus.FAILED);
            task.setErrorMessage("Build exit code: " + exitCode);
            saveBuildRecord(task, "FAIL", "FAIL");
            return;
          }
          logLine(task, logWriter, "=== Build SUCCESS ===");
          task.setStatus(BuildTaskStatus.SUCCESS);

          // Persist cache entry so the next build can skip the compile step
          if (currentGitHash != null) {
            BuildCacheEntry newEntry = new BuildCacheEntry();
            newEntry.setConfigId(config.getId());
            newEntry.setGitHash(currentGitHash);
            newEntry.setModulePaths(task.getSelectedModules());
            newEntry.setBuildWorkDir(config.getBuildWorkDir());
            newEntry.setBuildCommand(config.getBuildCommand());
            newEntry.setLanguageVersion(config.getLanguageVersion());
            newEntry.setDeploySourcePath(config.getDeploySourcePath());
            newEntry.setBuildMode(task.getBuildMode());
            newEntry.setUpdatedAt(System.currentTimeMillis());
            buildCacheService.put(newEntry);
          }
        }

        // Check if auto-deploy is enabled
        Boolean autoDeploy = task.getAutoDeploy();
        if (autoDeploy == null || autoDeploy) {
          // Step 5: Deploy artifact
          logLine(task, logWriter, "=== Starting deployment ===");
          task.setStatus(BuildTaskStatus.DEPLOYING);
          boolean hasRestart =
              (config.getStartCommand() != null && !config.getStartCommand().trim().isEmpty())
                  || (config.getRestartCommand() != null
                      && !config.getRestartCommand().trim().isEmpty());
          boolean deployOk =
              deployService.deploy(
                  config,
                  workDir.getAbsolutePath(),
                  task.getSelectedModules(),
                  task.getSelectedEnvIds(),
                  line -> logLine(task, logWriter, line));
          if (deployOk) {
            if (!hasRestart) {
              logLine(task, logWriter, "No restart required");
            }
            logLine(task, logWriter, "=== Deploy SUCCESS ===");
            task.setStatus(BuildTaskStatus.DEPLOY_SUCCESS);
            saveBuildRecord(task, "SUCCESS", "SUCCESS");
          } else {
            logLine(task, logWriter, "=== Deploy FAILED ===");
            task.setStatus(BuildTaskStatus.DEPLOY_FAILED);
            task.setErrorMessage("Deploy failed");
            saveBuildRecord(task, "SUCCESS", "FAIL");
          }
        } else {
          // Auto-deploy disabled: build only, user will download and deploy manually
          logLine(task, logWriter, "=== 构建完成（未自动部署）===");
          logLine(task, logWriter, "可下载产物或生成部署脚本进行手动部署");
          // Store artifact paths for download/script generation
          task.setStagingDir(workDir.getAbsolutePath());
          saveBuildRecord(task, "SUCCESS", "NONE");
        }
      } catch (Exception e) {
        log.error("Build task {} failed", task.getTaskId(), e);
        logLine(task, logWriter, "=== Build EXCEPTION ===");
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        for (String line : sw.toString().split("\\r?\\n")) {
          logLine(task, logWriter, line);
        }
        task.setStatus(BuildTaskStatus.FAILED);
        task.setErrorMessage(e.getMessage());
        saveBuildRecord(task, "FAIL", "FAIL");
      }
    } catch (Exception e) {
      // Log file creation failed
      log.error("Build task {} failed to create log file", task.getTaskId(), e);
      task.setStatus(BuildTaskStatus.FAILED);
      task.setErrorMessage(e.getMessage());
      task.bufferLogLine("Failed to create log file: " + e.getMessage());
      task.pushLog("Failed to create log file: " + e.getMessage());
      saveBuildRecord(task, "FAIL", "FAIL");
    } finally {
      // Mark task as complete FIRST, so queue polling and SSE clients see the finished state
      // even if the branch restoration below hangs (e.g. JGit blocked on a lock file).
      task.setEndTime(LocalDateTime.now());
      task.completeEmitters();

      // Restore original branch if it was changed for this build
      if (originalBranch != null && repoDir != null && repoDir.isDirectory()) {
        try {
          gitService.checkoutBranch(repoDir, originalBranch);
          log.info("Restored branch to {} after build", originalBranch);
        } catch (Exception e) {
          log.warn("Failed to restore branch to {}: {}", originalBranch, e.getMessage());
        }
      }
    }
  }

  /**
   * Start a build using a git worktree (isolated working directory). The worktree already has the
   * correct branch checked out, so no clone/pull or branch switching is needed.
   */
  public String startBuildFromWorktree(
      ProjectConfig snapshot,
      String buildMode,
      String username,
      java.util.List<String> modulePaths,
      java.util.List<Long> envIds,
      Boolean autoDeploy,
      String selectedBranch,
      String worktreePath,
      Long queueTaskId) {
    String taskId = UUID.randomUUID().toString().substring(0, 8);
    BuildTask task = new BuildTask(taskId, snapshot, buildMode, username);
    task.setStartTime(LocalDateTime.now());
    task.setSelectedModules(modulePaths);
    task.setSelectedEnvIds(envIds);
    task.setAutoDeploy("LOCAL".equals(buildMode) || autoDeploy == null || autoDeploy);
    task.setSelectedBranch(selectedBranch);
    task.setQueueTaskId(queueTaskId);

    String logFileName =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            + "_"
            + snapshot.getProjectName()
            + "_"
            + taskId
            + ".log";
    task.setLogFilePath(Paths.get(logsDir, logFileName).toString());

    taskMap.put(taskId, task);
    poolManager.submit(() -> executeBuildWithWorktree(task, worktreePath));

    return taskId;
  }

  /** Execute build using a worktree directory (no git clone/pull, no branch switching). */
  private void executeBuildWithWorktree(BuildTask task, String worktreePath) {
    ProjectConfig config = task.getConfigSnapshot();
    task.setStatus(BuildTaskStatus.BUILDING);

    try (PrintWriter logWriter =
        new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(task.getLogFilePath()), "UTF-8"), true)) {

      try {
        File repoDir = new File(worktreePath);
        logLine(task, logWriter, "=== 使用 worktree 构建 ===");
        logLine(task, logWriter, "Worktree: " + worktreePath);
        logLine(task, logWriter, "分支: " + task.getSelectedBranch());

        // Determine working directory
        File workDir;
        if (config.getBuildWorkDir() != null && !config.getBuildWorkDir().trim().isEmpty()) {
          workDir = new File(repoDir, config.getBuildWorkDir().trim());
        } else {
          workDir = repoDir;
        }

        // Build command
        String buildCmd =
            runtimeService.buildFullCommand(
                LanguageType.fromString(config.getLanguageType()),
                config.getLanguageVersion(),
                config.getBuildCommand(),
                config.getCustomInstallDir());

        // npm install for Node projects
        if ("NODE".equalsIgnoreCase(config.getLanguageType())) {
          File packageJson = new File(workDir, "package.json");
          File nodeModules = new File(workDir, "node_modules");
          boolean needsInstall = false;

          if (packageJson.isFile()) {
            if (!nodeModules.isDirectory()) {
              needsInstall = true;
              logLine(task, logWriter, "node_modules not found, npm install required");
            } else if (packageJson.lastModified() > nodeModules.lastModified()) {
              needsInstall = true;
              logLine(task, logWriter, "package.json has changed, npm install required");
            }
          }

          if (needsInstall) {
            String npmInstallCmd =
                runtimeService.buildFullCommand(
                    LanguageType.fromString(config.getLanguageType()),
                    config.getLanguageVersion(),
                    "npm install",
                    config.getCustomInstallDir());
            logLine(task, logWriter, "=== npm install ===");
            int installExit = executeProcess(task, logWriter, npmInstallCmd, workDir);
            if (installExit != 0) {
              logLine(
                  task, logWriter, "=== npm install FAILED (exit code: " + installExit + ") ===");
              task.setStatus(BuildTaskStatus.FAILED);
              task.setErrorMessage("npm install exit code: " + installExit);
              saveBuildRecord(task, "FAIL", "FAIL");
              return;
            }
            logLine(task, logWriter, "=== npm install completed ===");
          }
        }

        logLine(task, logWriter, "=== Build command: " + buildCmd + " ===");
        logLine(task, logWriter, "=== Working dir: " + workDir.getAbsolutePath() + " ===");

        int exitCode = executeProcess(task, logWriter, buildCmd, workDir);
        if (exitCode != 0) {
          logLine(task, logWriter, "=== Build FAILED (exit code: " + exitCode + ") ===");
          task.setStatus(BuildTaskStatus.FAILED);
          task.setErrorMessage("Build exit code: " + exitCode);
          saveBuildRecord(task, "FAIL", "FAIL");
          return;
        }
        logLine(task, logWriter, "=== Build SUCCESS ===");
        task.setStatus(BuildTaskStatus.SUCCESS);

        // Deploy
        Boolean autoDeploy = task.getAutoDeploy();
        if (autoDeploy == null || autoDeploy) {
          logLine(task, logWriter, "=== Starting deployment ===");
          task.setStatus(BuildTaskStatus.DEPLOYING);
          boolean hasRestart =
              (config.getStartCommand() != null && !config.getStartCommand().trim().isEmpty())
                  || (config.getRestartCommand() != null
                      && !config.getRestartCommand().trim().isEmpty());
          boolean deployOk =
              deployService.deploy(
                  config,
                  workDir.getAbsolutePath(),
                  task.getSelectedModules(),
                  task.getSelectedEnvIds(),
                  line -> logLine(task, logWriter, line));
          if (deployOk) {
            if (!hasRestart) {
              logLine(task, logWriter, "No restart required");
            }
            logLine(task, logWriter, "=== Deploy SUCCESS ===");
            task.setStatus(BuildTaskStatus.DEPLOY_SUCCESS);
            saveBuildRecord(task, "SUCCESS", "SUCCESS");
          } else {
            logLine(task, logWriter, "=== Deploy FAILED ===");
            task.setStatus(BuildTaskStatus.DEPLOY_FAILED);
            task.setErrorMessage("Deploy failed");
            saveBuildRecord(task, "SUCCESS", "FAIL");
          }
        } else {
          logLine(task, logWriter, "=== 构建完成（未自动部署）===");
          task.setStagingDir(workDir.getAbsolutePath());
          saveBuildRecord(task, "SUCCESS", "NONE");
        }
      } catch (InterruptedException e) {
        logLine(task, logWriter, "=== 构建已停止 ===");
        task.setStatus(BuildTaskStatus.FAILED);
        task.setErrorMessage("用户手动停止");
        saveBuildRecord(task, "FAIL", "FAIL");
      } catch (Exception e) {
        log.error("Build task {} failed", task.getTaskId(), e);
        logLine(task, logWriter, "=== Build EXCEPTION ===");
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        for (String line : sw.toString().split("\\r?\\n")) {
          logLine(task, logWriter, line);
        }
        task.setStatus(BuildTaskStatus.FAILED);
        task.setErrorMessage(e.getMessage());
        saveBuildRecord(task, "FAIL", "FAIL");
      }
    } catch (Exception e) {
      log.error("Build task {} failed to create log file", task.getTaskId(), e);
      task.setStatus(BuildTaskStatus.FAILED);
      task.setErrorMessage(e.getMessage());
      task.bufferLogLine("Failed to create log file: " + e.getMessage());
      task.pushLog("Failed to create log file: " + e.getMessage());
      saveBuildRecord(task, "FAIL", "FAIL");
    } finally {
      log.info("Build task {} (worktree) finished, setting endTime", task.getTaskId());
      task.setEndTime(LocalDateTime.now());
      task.completeEmitters();
    }
  }

  private void logLine(BuildTask task, PrintWriter writer, String line) {
    writer.println(line);
    task.bufferLogLine(line);
    task.pushLog(line);
  }

  private int executeProcess(BuildTask task, PrintWriter logWriter, String command, File workDir)
      throws Exception {
    ProcessBuilder pb;
    if (isWindows()) {
      pb = new ProcessBuilder("cmd", "/c", command);
    } else {
      String shell = LanguageRuntimeService.detectShell();
      String fullCommand = LanguageRuntimeService.buildShellPreamble(shell) + " && " + command;
      pb = new ProcessBuilder(shell, "-l", "-c", fullCommand);
      LanguageRuntimeService.appendMacPaths(pb);
    }
    pb.directory(workDir);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    task.setRunningProcess(process);

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                process.getInputStream(),
                isWindows() ? Charset.forName("GBK") : Charset.forName("UTF-8")))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (task.isStopRequested()) {
          process.destroyForcibly();
          logLine(task, logWriter, "=== 构建已被用户停止 ===");
          throw new InterruptedException("Build stopped by user");
        }
        logLine(task, logWriter, line);
      }
    }
    return process.waitFor();
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  /** Human-readable reason why the build cache was not hit. Used for log output only. */
  private String diagnoseCacheMiss(
      BuildCacheEntry entry,
      String currentGitHash,
      BuildTask task,
      ProjectConfig config,
      File workDir) {
    if (entry == null) return "首次构建，无缓存";
    if (currentGitHash == null) return "无法读取 Git HEAD";
    if (!currentGitHash.equals(entry.getGitHash())) {
      String prev = entry.getGitHash();
      String curr = currentGitHash;
      String reason;
      if (curr.endsWith("-DIRTY") && !prev.endsWith("-DIRTY")) {
        // Cache was clean at same commit; now working tree has local edits.
        String cleanCurr = curr.substring(0, curr.length() - "-DIRTY".length());
        if (cleanCurr.equals(prev)) {
          reason = "代码有未提交的本地修改";
        } else {
          reason =
              "Git HEAD 已变更且有未提交修改 ("
                  + prev.substring(0, Math.min(8, prev.length()))
                  + " → "
                  + cleanCurr.substring(0, Math.min(8, cleanCurr.length()))
                  + "*)";
        }
      } else if (!curr.endsWith("-DIRTY") && prev.endsWith("-DIRTY")) {
        String cleanPrev = prev.substring(0, prev.length() - "-DIRTY".length());
        reason =
            "上次构建时代码有未提交修改，本次已干净 ("
                + cleanPrev.substring(0, Math.min(8, cleanPrev.length()))
                + "* → "
                + curr.substring(0, Math.min(8, curr.length()))
                + ")";
      } else {
        reason =
            "Git HEAD 已变更 ("
                + prev.substring(0, Math.min(8, prev.length()))
                + " → "
                + curr.substring(0, Math.min(8, curr.length()))
                + ")";
      }
      return reason;
    }
    if (workDir == null || !workDir.isDirectory()) return "工作目录不存在";
    if (task.getSelectedModules() == null && entry.getModulePaths() != null) return "未选择模块";
    if (task.getSelectedModules() != null && entry.getModulePaths() == null) return "新增了模块选择";
    if (task.getSelectedModules() != null
        && !task.getSelectedModules().equals(entry.getModulePaths())) {
      return "模块选择已变更";
    }
    if (strDiffer(config.getBuildWorkDir(), entry.getBuildWorkDir())) return "buildWorkDir 已变更";
    if (strDiffer(config.getBuildCommand(), entry.getBuildCommand())) return "buildCommand 已变更";
    if (strDiffer(config.getLanguageVersion(), entry.getLanguageVersion())) {
      return "languageVersion 已变更";
    }
    if (strDiffer(config.getDeploySourcePath(), entry.getDeploySourcePath())) {
      return "deploySourcePath 已变更";
    }
    if (strDiffer(task.getBuildMode(), entry.getBuildMode())) return "buildMode 已变更";
    return "配置变更";
  }

  private static boolean strDiffer(String a, String b) {
    String x = a == null ? "" : a.trim();
    String y = b == null ? "" : b.trim();
    return !x.equals(y);
  }

  /** Save a build record to the database. */
  private BuildRecord saveBuildRecord(BuildTask task, String status, String deployStatus) {
    BuildRecord record = new BuildRecord();
    record.setProjectName(task.getConfigSnapshot().getProjectName());
    record.setVersion(task.getConfigSnapshot().getVersion());
    record.setRepoUrl(task.getConfigSnapshot().getGitRepoUrl());
    record.setBuildTime(task.getStartTime());
    record.setBuildUser(task.getCurrentUser());
    record.setStatus(status);
    record.setBuildMode(task.getBuildMode());
    record.setLogFilePath(task.getLogFilePath());
    record.setDeployStatus(deployStatus);
    record.setCreatedAt(LocalDateTime.now());

    // New fields: module/env selection and auto-deploy
    if (task.getSelectedModules() != null && !task.getSelectedModules().isEmpty()) {
      record.setSelectedModules(String.join(",", task.getSelectedModules()));
    }
    record.setAutoDeploy(task.getAutoDeploy());

    // Link to queue task if applicable
    if (task.getQueueTaskId() != null) {
      record.setQueueTaskId(task.getQueueTaskId());
    }

    buildRecordRepository.insert(record);
    return record;
  }

  /** Get a task by ID. */
  public BuildTask getTask(String taskId) {
    return taskMap.get(taskId);
  }

  /**
   * Subscribe to build log via SSE. Sends buffered (historical) log lines first, then streams live
   * lines.
   */
  public SseEmitter subscribeLog(String taskId) {
    BuildTask task = taskMap.get(taskId);
    if (task == null) return null;
    SseEmitter emitter = new SseEmitter(1800000L); // 30 minutes
    emitter.onTimeout(
        () -> {
          log.debug("SSE timeout for task {}", taskId);
          task.removeEmitter(emitter);
        });
    emitter.onCompletion(
        () -> {
          log.debug("SSE completed for task {}", taskId);
          task.removeEmitter(emitter);
        });
    emitter.onError(
        e -> {
          log.debug("SSE error for task {}: {}", taskId, e.getMessage());
          task.removeEmitter(emitter);
        });

    // Send only the last 200 buffered lines (not the full buffer) for fast reconnection
    try {
      java.util.List<String> tail = task.getLogBufferTail(200);
      int totalBuffered = task.getLogBuffer().size();
      if (totalBuffered > 200) {
        emitter.send("[仅显示最近 200 行，共 " + totalBuffered + " 行。构建完成后可查看完整日志]");
      }
      for (String line : tail) {
        emitter.send(line);
      }
    } catch (Exception e) {
      log.warn("Failed to send buffered logs for task {}", taskId, e);
      emitter.complete();
      return emitter;
    }

    // If the task is already complete, just send buffered logs and close
    if (task.getEndTime() != null) {
      emitter.complete();
      return emitter;
    }

    // Then add emitter for live streaming
    task.addEmitter(emitter);
    return emitter;
  }

  /** Get all active tasks, ordered by start time descending (newest first). */
  public java.util.List<BuildTask> listTasks() {
    java.util.List<BuildTask> list = new java.util.ArrayList<>(taskMap.values());
    list.sort(
        (a, b) -> {
          java.time.LocalDateTime ta = a.getStartTime();
          java.time.LocalDateTime tb = b.getStartTime();
          if (ta == null && tb == null) return 0;
          if (ta == null) return 1;
          if (tb == null) return -1;
          return tb.compareTo(ta);
        });
    return list;
  }

  /** Read the last tailLines of a log file. Returns content, hasMore, totalLines. */
  public Map<String, Object> readLogFileTail(String taskId, int tailLines) {
    Map<String, Object> result = new java.util.HashMap<>();
    try {
      Path logsPath = Paths.get(logsDir);
      if (!Files.isDirectory(logsPath)) {
        result.put("content", "日志目录不存在");
        result.put("hasMore", false);
        result.put("totalLines", 0);
        return result;
      }
      try (DirectoryStream<Path> stream =
          Files.newDirectoryStream(logsPath, "*" + taskId + "*.log")) {
        for (Path entry : stream) {
          return readTailFromFile(entry.toFile(), tailLines);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to read log file for task {}", taskId, e);
    }
    result.put("content", null);
    result.put("hasMore", false);
    result.put("totalLines", 0);
    return result;
  }

  /** Read the last tailLines from a file efficiently using buffered RandomAccessFile. */
  public Map<String, Object> readTailFromFile(File file, int tailLines) {
    Map<String, Object> result = new java.util.HashMap<>();
    if (!file.exists()) {
      result.put("content", "日志文件不存在");
      result.put("hasMore", false);
      result.put("totalLines", 0);
      return result;
    }
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      long fileLen = raf.length();
      if (fileLen == 0) {
        result.put("content", "");
        result.put("hasMore", false);
        result.put("totalLines", 0);
        return result;
      }

      int totalLines = 0;
      long tailStart = 0;
      int bufferSize = 8192;
      byte[] buf = new byte[bufferSize];

      // First pass: count total lines (forward scan, fast)
      long pos = 0;
      while (pos < fileLen) {
        raf.seek(pos);
        int bytesRead = raf.read(buf);
        for (int i = 0; i < bytesRead; i++) {
          if (buf[i] == '\n') totalLines++;
        }
        pos += bytesRead;
      }

      // Second pass: find tail start (backward buffered scan)
      int linesFound = 0;
      long chunkEnd = fileLen;
      while (chunkEnd > 0) {
        long chunkStart = Math.max(0, chunkEnd - bufferSize);
        int len = (int) (chunkEnd - chunkStart);
        raf.seek(chunkStart);
        raf.readFully(buf, 0, len);

        for (int i = len - 1; i >= 0; i--) {
          if (buf[i] == '\n') {
            linesFound++;
            if (linesFound > tailLines) {
              tailStart = chunkStart + i + 1;
              chunkEnd = 0;
              break;
            }
          }
        }
        if (linesFound <= tailLines && chunkStart == 0) {
          tailStart = 0;
          break;
        }
        chunkEnd = chunkStart;
      }

      // Read tail content
      raf.seek(tailStart);
      long remaining = fileLen - tailStart;
      byte[] tailBytes = new byte[(int) remaining];
      raf.readFully(tailBytes);
      String content = new String(tailBytes, "UTF-8");
      result.put("content", content);
      result.put("hasMore", tailStart > 0);
      result.put("totalLines", totalLines);
    } catch (Exception e) {
      result.put("content", "读取日志文件失败: " + e.getMessage());
      result.put("hasMore", false);
      result.put("totalLines", 0);
    }
    return result;
  }

  /** Check whether a project's local repository has uncommitted changes. */
  public boolean hasUncommittedChanges(Long configId) {
    ProjectConfig config = configService.getSnapshot(configId);
    if (config == null) {
      return false;
    }
    String projectDir = config.getProjectDir();
    if (projectDir == null || projectDir.trim().isEmpty()) {
      return false;
    }
    File repoDir = new File(projectDir.trim());
    return gitService.hasUncommittedChanges(repoDir);
  }

  /** Get the current branch name of a project's local repository. */
  public String getCurrentBranch(Long configId) {
    ProjectConfig config = configService.getSnapshot(configId);
    if (config == null) {
      return null;
    }
    String projectDir = config.getProjectDir();
    if (projectDir == null || projectDir.trim().isEmpty()) {
      return null;
    }
    File repoDir = new File(projectDir.trim());
    return gitService.getCurrentBranch(repoDir);
  }

  /** List available remote branches for a project. */
  public java.util.List<String> listBranches(Long configId) {
    ProjectConfig config = configService.getSnapshot(configId);
    if (config == null) {
      return java.util.Collections.emptyList();
    }
    try {
      String projectDir = config.getProjectDir();
      if (projectDir != null && !projectDir.trim().isEmpty()) {
        return gitService.listBranches(config, projectDir.trim());
      }
      // No local project dir — use ls-remote to list branches directly from the remote URL
      return gitService.listBranches(config, null);
    } catch (Exception e) {
      log.warn(
          "Failed to list branches for project {}: {}", config.getProjectName(), e.getMessage());
      return java.util.Collections.emptyList();
    }
  }
}
