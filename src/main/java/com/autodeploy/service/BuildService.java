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
      Boolean autoDeploy) {
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
        File repoDir = new File(userGitDir);
        boolean repoExists = repoDir.exists() && new File(repoDir, ".git").exists();
        if (repoExists) {
          logLine(task, logWriter, "=== Git pull (repository already exists) ===");
        } else {
          logLine(task, logWriter, "=== Git clone (repository not found, will clone) ===");
        }
        logLine(task, logWriter, "Git directory: " + userGitDir);
        repoDir = gitService.cloneOrPull(config, userGitDir);
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

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                process.getInputStream(),
                isWindows() ? Charset.forName("GBK") : Charset.forName("UTF-8")))) {
      String line;
      while ((line = reader.readLine()) != null) {
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
  private void saveBuildRecord(BuildTask task, String status, String deployStatus) {
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

    buildRecordRepository.insert(record);
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

  /** Get all active tasks. */
  public java.util.Collection<BuildTask> listTasks() {
    return taskMap.values();
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
}
