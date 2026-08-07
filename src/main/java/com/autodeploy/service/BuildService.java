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

  @Value("${autodeploy.builds-dir}")
  private String buildsDir;

  @Value("${autodeploy.logs-dir}")
  private String logsDir;

  private final ConcurrentHashMap<String, BuildTask> taskMap = new ConcurrentHashMap<>();

  /** Start a build for a project config. */
  public String startBuild(Long configId, String buildMode, String username) {
    ProjectConfig snapshot = configService.getSnapshot(configId);
    if (snapshot == null) {
      return null;
    }

    String taskId = UUID.randomUUID().toString().substring(0, 8);
    BuildTask task = new BuildTask(taskId, snapshot, buildMode, username);
    task.setStartTime(LocalDateTime.now());

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
            new OutputStreamWriter(
                new FileOutputStream(task.getLogFilePath()),
                isWindows() ? Charset.forName("GBK") : Charset.forName("UTF-8")),
            true)) {

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
        if (exitCode == 0) {
          logLine(task, logWriter, "=== Build SUCCESS ===");
          task.setStatus(BuildTaskStatus.SUCCESS);

          // Step 5: Deploy artifact
          logLine(task, logWriter, "=== Starting deployment ===");
          task.setStatus(BuildTaskStatus.DEPLOYING);
          boolean hasRestart =
              (config.getStartCommand() != null && !config.getStartCommand().trim().isEmpty())
                  || (config.getRestartCommand() != null
                      && !config.getRestartCommand().trim().isEmpty());
          boolean deployOk =
              deployService.deploy(
                  config, workDir.getAbsolutePath(), line -> logLine(task, logWriter, line));
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
          logLine(task, logWriter, "=== Build FAILED (exit code: " + exitCode + ") ===");
          task.setStatus(BuildTaskStatus.FAILED);
          task.setErrorMessage("Build exit code: " + exitCode);
          saveBuildRecord(task, "FAIL", "FAIL");
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
