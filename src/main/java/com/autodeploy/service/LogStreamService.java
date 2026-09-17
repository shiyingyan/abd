package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ServerInfo;
import com.autodeploy.util.EnvVarUtil;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LogStreamService {

  private static final Logger log = LoggerFactory.getLogger(LogStreamService.class);

  private static final int MAX_PER_SERVER = 5;
  private static final int MAX_GLOBAL = 30;
  private static final long SSE_TIMEOUT_MS = 1800000L; // 30 minutes
  private static final long KEEPALIVE_INTERVAL_SECONDS = 15;

  @Autowired private SshService sshService;
  @Autowired private ConfigService configService;
  @Autowired private ServerInfoService serverInfoService;

  // serverHost -> list of active streams
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<LogStreamSession>> serverStreams =
      new ConcurrentHashMap<>();
  // Global counter
  private final AtomicInteger globalCount = new AtomicInteger(0);

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  @PreDestroy
  public void shutdown() {
    for (CopyOnWriteArrayList<LogStreamSession> sessions : serverStreams.values()) {
      for (LogStreamSession s : sessions) {
        s.close();
      }
    }
    serverStreams.clear();
    globalCount.set(0);
    scheduler.shutdown();
  }

  /**
   * Subscribe to server log via SSE. Returns SseEmitter on success, or throws LogStreamException on
   * failure.
   */
  public SseEmitter subscribe(Long configId, Long serverId) {
    ProjectConfig config = configService.getById(configId);
    if (config == null) {
      throw new LogStreamException("项目配置不存在");
    }

    ServerInfo server = serverInfoService.getById(serverId);
    if (server == null) {
      throw new LogStreamException("服务器不存在");
    }

    String[] logPaths = resolveLogPaths(config);

    String serverHost = server.getHost();

    // Check connection limits
    int perServer = getServerStreamCount(serverHost);
    if (perServer >= MAX_PER_SERVER) {
      throw new LogStreamException("当前操作人数多，稍等会儿");
    }
    if (globalCount.get() >= MAX_GLOBAL) {
      throw new LogStreamException("当前操作人数多，稍等会儿");
    }

    // Resolve password from env var
    String password = EnvVarUtil.getValue(server.getAuthEnvKey());
    if (password == null || password.isEmpty()) {
      throw new LogStreamException("SSH 认证环境变量未配置: " + server.getAuthEnvKey());
    }

    int port = server.getPort() != null ? server.getPort() : 22;

    // Build tail command
    String tailCommand = buildTailCommand(logPaths);

    try {
      // Open SSH streaming connection
      SshService.StreamingExecHandle handle =
          sshService.executeStreamingCommand(
              serverHost, port, server.getUser(), password, tailCommand);

      // Create SSE emitter
      SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
      String sessionId = UUID.randomUUID().toString();

      // Register session
      LogStreamSession session = new LogStreamSession(sessionId, serverHost, handle, emitter);
      registerSession(serverHost, session);
      globalCount.incrementAndGet();

      // Set up cleanup callbacks
      Runnable cleanup =
          () -> {
            if (!session.isClosed()) {
              session.close();
              unregisterSession(serverHost, session);
              globalCount.decrementAndGet();
            }
          };
      emitter.onTimeout(cleanup);
      emitter.onCompletion(cleanup);
      emitter.onError(e -> cleanup.run());

      // Start reader thread
      Thread reader = new Thread(() -> readSshOutput(session, handle), "log-stream-" + sessionId);
      reader.setDaemon(true);
      reader.start();
      session.setReaderThread(reader);

      // Send initial connection message
      try {
        String[] paths = logPaths;
        StringBuilder pathInfo = new StringBuilder();
        for (String p : paths) {
          if (pathInfo.length() > 0) pathInfo.append(", ");
          pathInfo.append(p);
        }
        emitter.send("已连接到 " + serverHost + "，正在跟踪: " + pathInfo.toString());
      } catch (Exception e) {
        // ignore
      }

      // Start keepalive
      ScheduledFuture<?> keepaliveFuture =
          scheduler.scheduleAtFixedRate(
              () -> {
                try {
                  emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception e) {
                  cleanup.run();
                }
              },
              KEEPALIVE_INTERVAL_SECONDS,
              KEEPALIVE_INTERVAL_SECONDS,
              TimeUnit.SECONDS);
      session.setKeepaliveFuture(keepaliveFuture);

      log.info(
          "Started log stream session {} to {}@{} for config {}",
          sessionId,
          server.getUser(),
          serverHost,
          configId);

      return emitter;

    } catch (Exception e) {
      log.error("Failed to start log stream to {}", serverHost, e);
      throw new LogStreamException("SSH 连接失败: " + e.getMessage());
    }
  }

  /** Get the number of active connections for a server. */
  public int getActiveCount(String serverHost) {
    return getServerStreamCount(serverHost);
  }

  /** Get total active connections globally. */
  public int getGlobalActiveCount() {
    return globalCount.get();
  }

  /**
   * Resolve the log file path to tail on the remote server.
   *
   * <p>Rules: 1. logDirectory + logFileName both specified -> logDirectory/logFileName 2.
   * logDirectory specified, logFileName empty -> try logDirectory/parentDirName.log 3. Fallback ->
   * logDirectory/info.log AND logDirectory/error.log
   *
   * <p>If logDirectory is not specified, defaults to {deployDir}/logs. User-specified logDirectory
   * is always treated as an absolute path.
   */
  public String[] resolveLogPaths(ProjectConfig config) {
    String logDir = config.getLogDirectory();
    String logFile = config.getLogFileName();

    // Get the deployment directory
    String deployDir = getDeployDir(config);

    // Resolve log directory
    if (logDir == null || logDir.trim().isEmpty()) {
      // Default to {deployDir}/logs
      logDir = deployDir != null ? deployDir + "/logs" : "/logs";
    } else {
      // User-specified directory is always absolute
      logDir = logDir.trim();
    }

    // Ensure trailing slash is removed
    if (logDir.endsWith("/")) {
      logDir = logDir.substring(0, logDir.length() - 1);
    }

    if (logFile != null && !logFile.trim().isEmpty()) {
      // Rule 1: explicit file
      return new String[] {logDir + "/" + logFile.trim()};
    }

    // Rule 2: try to infer from project path
    String parentDirName = inferParentDirName(config);
    if (parentDirName != null) {
      String inferred = logDir + "/" + parentDirName + ".log";
      // Return the inferred path; the service will check existence on the remote
      return new String[] {inferred};
    }

    // Rule 3: fallback to info.log + error.log
    return new String[] {logDir + "/info.log", logDir + "/error.log"};
  }

  private String inferParentDirName(ProjectConfig config) {
    // Use installDir or deployTargetPath to extract the last path segment
    String path = config.getInstallDir();
    if (path == null || path.trim().isEmpty()) {
      path = config.getDeployTargetPath();
    }
    if (path == null || path.trim().isEmpty()) {
      return null;
    }
    // Extract last segment
    String trimmed = path.trim();
    if (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    int lastSlash = trimmed.lastIndexOf('/');
    return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
  }

  /**
   * Get the deployment directory from the project config. Uses installDir if set, otherwise
   * deployTargetPath.
   */
  private String getDeployDir(ProjectConfig config) {
    String installDir = config.getInstallDir();
    if (installDir != null && !installDir.trim().isEmpty()) {
      String dir = installDir.trim();
      if (dir.endsWith("/")) {
        dir = dir.substring(0, dir.length() - 1);
      }
      return dir;
    }
    String deployTargetPath = config.getDeployTargetPath();
    if (deployTargetPath != null && !deployTargetPath.trim().isEmpty()) {
      String dir = deployTargetPath.trim();
      if (dir.endsWith("/")) {
        dir = dir.substring(0, dir.length() - 1);
      }
      // If it's an absolute path, use it directly
      if (dir.startsWith("/")) {
        return dir;
      }
    }
    return null;
  }

  private String buildTailCommand(String[] logPaths) {
    StringBuilder sb = new StringBuilder("tail -F");
    for (String p : logPaths) {
      sb.append(" ").append(p);
    }
    // Redirect stderr to stdout so we can see errors like "file not found"
    sb.append(" 2>&1");
    return sb.toString();
  }

  private void readSshOutput(LogStreamSession session, SshService.StreamingExecHandle handle) {
    log.debug("Starting to read SSH output for session {}", session.getServerHost());
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(handle.getStdout(), StandardCharsets.UTF_8))) {
      String line;
      while (!session.isClosed() && (line = reader.readLine()) != null) {
        log.debug("Read line from SSH: {}", line);
        try {
          session.getEmitter().send(line);
        } catch (Exception e) {
          log.debug("Failed to send line via SSE: {}", e.getMessage());
          // SSE emitter broken, stop reading
          break;
        }
      }
      log.debug("SSH output stream ended for session {}", session.getServerHost());
    } catch (Exception e) {
      if (!session.isClosed()) {
        log.debug("SSH output read ended: {}", e.getMessage());
      }
    } finally {
      // Stream ended (remote process died or SSH disconnected)
      log.debug("Cleaning up session {}", session.getServerHost());
      session.close();
      unregisterSession(session.getServerHost(), session);
      globalCount.decrementAndGet();
    }
  }

  private void registerSession(String serverHost, LogStreamSession session) {
    serverStreams.compute(
        serverHost,
        (k, existing) -> {
          if (existing == null) {
            existing = new CopyOnWriteArrayList<>();
          }
          existing.add(session);
          return existing;
        });
  }

  private void unregisterSession(String serverHost, LogStreamSession session) {
    serverStreams.computeIfPresent(
        serverHost,
        (k, list) -> {
          list.remove(session);
          return list.isEmpty() ? null : list;
        });
  }

  private int getServerStreamCount(String serverHost) {
    CopyOnWriteArrayList<LogStreamSession> list = serverStreams.get(serverHost);
    return list == null ? 0 : list.size();
  }

  /** Represents one active log streaming session. */
  public static class LogStreamSession {
    private final String sessionId;
    private final String serverHost;
    private final SshService.StreamingExecHandle sshHandle;
    private final SseEmitter emitter;
    private Thread readerThread;
    private ScheduledFuture<?> keepaliveFuture;
    private volatile boolean closed = false;

    public LogStreamSession(
        String sessionId,
        String serverHost,
        SshService.StreamingExecHandle sshHandle,
        SseEmitter emitter) {
      this.sessionId = sessionId;
      this.serverHost = serverHost;
      this.sshHandle = sshHandle;
      this.emitter = emitter;
    }

    public String getServerHost() {
      return serverHost;
    }

    public SseEmitter getEmitter() {
      return emitter;
    }

    public boolean isClosed() {
      return closed;
    }

    public void setReaderThread(Thread readerThread) {
      this.readerThread = readerThread;
    }

    public void setKeepaliveFuture(ScheduledFuture<?> keepaliveFuture) {
      this.keepaliveFuture = keepaliveFuture;
    }

    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      // 1. Close SSH handle (kills tail -F process on remote)
      sshHandle.close();
      // 2. Complete SSE emitter
      try {
        emitter.complete();
      } catch (Exception e) {
        // ignore
      }
      // 3. Cancel keepalive
      if (keepaliveFuture != null) {
        keepaliveFuture.cancel(false);
      }
      // 4. Interrupt reader thread if still alive
      if (readerThread != null && readerThread.isAlive()) {
        readerThread.interrupt();
      }
    }
  }

  /** Exception for log stream errors. */
  public static class LogStreamException extends RuntimeException {
    public LogStreamException(String message) {
      super(message);
    }
  }
}
