package com.autodeploy.service;

import com.autodeploy.config.BuildThreadPoolManager;
import com.autodeploy.model.*;
import com.autodeploy.repository.BuildRecordRepository;
import com.autodeploy.util.EnvVarUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Start a build for a project config.
     */
    public String startBuild(Long configId, String buildMode, String username) {
        ProjectConfig snapshot = configService.getSnapshot(configId);
        if (snapshot == null) {
            return null;
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        BuildTask task = new BuildTask(taskId, snapshot, buildMode, username);
        task.setStartTime(LocalDateTime.now());

        // Create log file
        String logFileName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + snapshot.getProjectName() + "_" + taskId + ".log";
        String logFilePath = Paths.get(logsDir, logFileName).toString();
        task.setLogFilePath(logFilePath);

        taskMap.put(taskId, task);
        poolManager.submit(() -> executeBuild(task));

        return taskId;
    }

    /**
     * Execute the build task (runs in thread pool).
     */
    private void executeBuild(BuildTask task) {
        ProjectConfig config = task.getConfigSnapshot();
        task.setStatus(BuildTaskStatus.BUILDING);

        try (PrintWriter logWriter = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(task.getLogFilePath()),
                        isWindows() ? Charset.forName("GBK") : Charset.forName("UTF-8")), true)) {

            try {
                // Step 1: Git clone/pull (use configured projectDir, or per-user temp directory)
                String userGitDir;
                if (config.getProjectDir() != null && !config.getProjectDir().trim().isEmpty()) {
                    userGitDir = config.getProjectDir().trim();
                } else {
                    String tempBase = System.getProperty("java.io.tmpdir");
                    userGitDir = java.nio.file.Paths.get(tempBase, "autodeploy", task.getCurrentUser(), config.getProjectName()).toString();
                }
                logLine(task, logWriter, "=== Git clone/pull ===");
                logLine(task, logWriter, "Git download directory: " + userGitDir);
                File repoDir = gitService.cloneOrPull(config, userGitDir);
                logLine(task, logWriter, "Git repository ready: " + repoDir.getAbsolutePath());

                // Step 2: Build command with language version switching
                String buildCmd = runtimeService.buildFullCommand(
                        LanguageType.fromString(config.getLanguageType()),
                        config.getLanguageVersion(),
                        config.getBuildCommand(),
                        config.getCustomInstallDir()
                );

                // Step 3: Determine working directory (resolve relative to git repo root)
                File workDir;
                if (config.getBuildWorkDir() != null && !config.getBuildWorkDir().trim().isEmpty()) {
                    workDir = new File(repoDir, config.getBuildWorkDir().trim());
                } else {
                    workDir = repoDir;
                }

                logLine(task, logWriter, "=== Build command: " + buildCmd + " ===");
                logLine(task, logWriter, "=== Working dir: " + workDir.getAbsolutePath() + " ===");

                // Step 4: Execute build
                ProcessBuilder pb;
                if (isWindows()) {
                    pb = new ProcessBuilder("cmd", "/c", buildCmd);
                } else {
                    pb = new ProcessBuilder("bash", "-c", buildCmd);
                }
                pb.directory(workDir);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(),
                                isWindows() ? Charset.forName("GBK") : Charset.forName("UTF-8")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logLine(task, logWriter, line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    logLine(task, logWriter, "=== Build SUCCESS ===");
                    task.setStatus(BuildTaskStatus.SUCCESS);

                    // Step 5: Deploy artifact
                    logLine(task, logWriter, "=== Starting deployment ===");
                    task.setStatus(BuildTaskStatus.DEPLOYING);
                    boolean hasRestart = (config.getStartCommand() != null && !config.getStartCommand().trim().isEmpty())
                            || (config.getRestartCommand() != null && !config.getRestartCommand().trim().isEmpty());
                    boolean deployOk = deployService.deploy(config, workDir.getAbsolutePath());
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

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Save a build record to the database.
     */
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

    /**
     * Get a task by ID.
     */
    public BuildTask getTask(String taskId) {
        return taskMap.get(taskId);
    }

    /**
     * Subscribe to build log via SSE.
     * Sends buffered (historical) log lines first, then streams live lines.
     */
    public SseEmitter subscribeLog(String taskId) {
        BuildTask task = taskMap.get(taskId);
        if (task == null) return null;
        SseEmitter emitter = new SseEmitter(300000L);

        // Send buffered log lines first (for late subscribers)
        try {
            for (String line : task.getLogBuffer()) {
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

    /**
     * Get all active tasks.
     */
    public java.util.Collection<BuildTask> listTasks() {
        return taskMap.values();
    }

    /**
     * Read log file content for a given taskId by searching the logs directory.
     */
    public String readLogFile(String taskId) {
        try {
            Path logsPath = Paths.get(logsDir);
            if (!Files.isDirectory(logsPath)) return null;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsPath, "*" + taskId + "*.log")) {
                for (Path entry : stream) {
                    byte[] bytes = Files.readAllBytes(entry);
                    return new String(bytes, Charset.forName("UTF-8"));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read log file for task {}", taskId, e);
        }
        return null;
    }
}
