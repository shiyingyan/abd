package com.autodeploy.service;

import com.autodeploy.config.BuildThreadPoolManager;
import com.autodeploy.model.BuildQueueTask;
import com.autodeploy.model.BuildRecord;
import com.autodeploy.model.BuildTask;
import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.repository.BuildQueueRepository;
import com.autodeploy.repository.BuildRecordRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BuildQueueService {

  private static final Logger log = LoggerFactory.getLogger(BuildQueueService.class);

  private final java.util.Set<Long> preparingTasks =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  @Autowired private BuildQueueRepository buildQueueRepository;
  @Autowired private BuildRecordRepository buildRecordRepository;
  @Autowired private BuildService buildService;
  @Autowired private BuildThreadPoolManager poolManager;
  @Autowired private ConfigService configService;
  @Autowired private ServerInfoService serverInfoService;
  @Autowired private GitService gitService;
  @Autowired private SystemSettingsService systemSettingsService;

  private volatile boolean schedulingActive = true;
  private final java.util.concurrent.atomic.AtomicInteger emptyCycleCount =
      new java.util.concurrent.atomic.AtomicInteger(0);
  private static final int EMPTY_CYCLE_THRESHOLD = 3;

  @PostConstruct
  public void recoverOrphanedTasks() {
    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    wrapper.eq("status", BuildQueueTask.STATUS_EXECUTING);
    List<BuildQueueTask> orphaned = buildQueueRepository.selectList(wrapper);
    for (BuildQueueTask task : orphaned) {
      task.setStatus(BuildQueueTask.STATUS_QUEUED);
      task.setStartTime(null);
      task.setUpdatedAt(LocalDateTime.now());
      buildQueueRepository.updateById(task);
    }
    if (!orphaned.isEmpty()) {
      log.info("Recovered {} orphaned queue tasks after restart", orphaned.size());
      startScheduling();
    }
  }

  /**
   * Entry point when user submits a build. Returns a map with either an error, an immediate taskId,
   * or a queued queueTaskId.
   */
  public Map<String, Object> submitTask(
      Long configId,
      String buildMode,
      String username,
      List<String> modulePaths,
      List<Long> envIds,
      Boolean autoDeploy,
      String selectedBranch,
      boolean forceStart,
      boolean skipGitPull) {

    ProjectConfig snapshot = configService.getSnapshot(configId);
    if (snapshot == null) {
      Map<String, Object> result = new HashMap<>();
      result.put("error", "项目配置不存在");
      return result;
    }

    // Resolve deploy server/env IDs for duplicate comparison
    String deployServersKey = resolveDeployServersKey(configId, envIds);
    String deployEnvsKey = resolveDeployEnvsKey(envIds);

    // Check for duplicate (same user + same project + same branch/servers/envs)
    boolean isDuplicate =
        checkDuplicate(username, configId, selectedBranch, deployServersKey, deployEnvsKey);

    if (isDuplicate) {
      Map<String, Object> result = new HashMap<>();
      result.put("error", "有相同任务在排队或执行中，无需重复提交");
      return result;
    }

    // skipGitPull → build directly in project directory, no git pull, no uncommitted check
    if (skipGitPull) {
      return startDirectBuild(
          snapshot,
          buildMode,
          username,
          modulePaths,
          envIds,
          autoDeploy,
          selectedBranch,
          deployServersKey,
          true);
    }

    // Non-duplicate
    boolean hasSameUserProject = hasSameUserProjectTasks(username, configId);

    if (hasSameUserProject) {
      // Same user + same project: apply force/queue logic
      if (forceStart) {
        return startImmediateBuild(
            snapshot,
            buildMode,
            username,
            modulePaths,
            envIds,
            autoDeploy,
            selectedBranch,
            deployServersKey);
      } else {
        return enqueueTask(
            snapshot,
            buildMode,
            username,
            modulePaths,
            envIds,
            autoDeploy,
            selectedBranch,
            deployServersKey);
      }
    } else {
      // Different user or different project: build directly in project directory (no worktree)
      return startDirectBuild(
          snapshot,
          buildMode,
          username,
          modulePaths,
          envIds,
          autoDeploy,
          selectedBranch,
          deployServersKey,
          false);
    }
  }

  /** Check if a duplicate task exists (same user, project, branch, servers, environments). */
  public boolean isDuplicate(String username, Long configId, String branch, List<Long> envIds) {
    String deployServersKey = resolveDeployServersKey(configId, envIds);
    String deployEnvsKey = resolveDeployEnvsKey(envIds);
    return checkDuplicate(username, configId, branch, deployServersKey, deployEnvsKey);
  }

  /** Check if there are any QUEUED or EXECUTING tasks for the same user + same project. */
  public boolean hasSameUserProjectTasks(String username, Long configId) {
    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    wrapper
        .eq("username", username)
        .eq("config_id", configId)
        .in("status", Arrays.asList(BuildQueueTask.STATUS_EXECUTING, BuildQueueTask.STATUS_QUEUED));
    return buildQueueRepository.selectCount(wrapper) > 0;
  }

  private String resolveDeployServersKey(Long configId, List<Long> envIds) {
    if (envIds == null || envIds.isEmpty()) {
      return "";
    }
    List<ProjectEnvServer> associations = serverInfoService.listProjectAssociations(configId);
    List<Long> serverIds = new ArrayList<>();
    for (ProjectEnvServer assoc : associations) {
      if (envIds.contains(assoc.getEnvironmentId())) {
        serverIds.add(assoc.getServerId());
      }
    }
    serverIds.sort(Long::compareTo);
    return serverIds.stream().map(String::valueOf).collect(Collectors.joining(","));
  }

  private String resolveDeployEnvsKey(List<Long> envIds) {
    if (envIds == null || envIds.isEmpty()) {
      return "";
    }
    List<Long> sorted = new ArrayList<>(envIds);
    sorted.sort(Long::compareTo);
    return sorted.stream().map(String::valueOf).collect(Collectors.joining(","));
  }

  private boolean checkDuplicate(
      String username,
      Long configId,
      String branch,
      String deployServersKey,
      String deployEnvsKey) {
    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    wrapper
        .eq("username", username)
        .eq("config_id", configId)
        .eq("target_branch", branch)
        .in("status", Arrays.asList(BuildQueueTask.STATUS_EXECUTING, BuildQueueTask.STATUS_QUEUED));
    List<BuildQueueTask> existing = buildQueueRepository.selectList(wrapper);
    for (BuildQueueTask task : existing) {
      if (serversMatch(task.getDeployServers(), deployServersKey)
          && envsMatch(task.getDeployEnvironments(), deployEnvsKey)) {
        return true;
      }
    }
    return false;
  }

  private boolean envsMatch(String existing, String current) {
    if (existing == null && current == null) return true;
    if (existing == null || current == null) return false;
    List<String> a = Arrays.asList(existing.split(","));
    List<String> b = Arrays.asList(current.split(","));
    a.sort(String::compareTo);
    b.sort(String::compareTo);
    return a.equals(b);
  }

  private boolean serversMatch(String existing, String current) {
    if (existing == null && current == null) return true;
    if (existing == null || current == null) return false;
    // Compare as sorted sets of IDs
    List<String> a = Arrays.asList(existing.split(","));
    List<String> b = Arrays.asList(current.split(","));
    a.sort(String::compareTo);
    b.sort(String::compareTo);
    return a.equals(b);
  }

  private Map<String, Object> startImmediateBuild(
      ProjectConfig snapshot,
      String buildMode,
      String username,
      List<String> modulePaths,
      List<Long> envIds,
      Boolean autoDeploy,
      String selectedBranch,
      String deployServersKey) {

    Map<String, Object> result = new HashMap<>();
    String worktreePath = null;
    try {
      // Create worktree and start build BEFORE inserting DB record,
      // so the frontend never sees EXECUTING without a buildTaskId.
      worktreePath = gitService.createWorktree(snapshot, selectedBranch);

      BuildQueueTask queueTask =
          createQueueTask(
              snapshot,
              buildMode,
              username,
              modulePaths,
              envIds,
              autoDeploy,
              selectedBranch,
              deployServersKey);

      String taskId =
          buildService.startBuildFromWorktree(
              snapshot,
              buildMode,
              username,
              modulePaths,
              envIds,
              autoDeploy,
              selectedBranch,
              worktreePath,
              null);

      // Insert with all fields populated at once
      queueTask.setStatus(BuildQueueTask.STATUS_EXECUTING);
      queueTask.setStartTime(LocalDateTime.now());
      queueTask.setWorktreePath(worktreePath);
      queueTask.setBuildTaskId(taskId);
      buildQueueRepository.insert(queueTask);

      // Update build task with queue task ID for linkage
      BuildTask buildTask = buildService.getTask(taskId);
      if (buildTask != null) {
        buildTask.setQueueTaskId(queueTask.getId());
      }

      result.put("taskId", taskId);
      result.put("queueTaskId", queueTask.getId());
      result.put("type", "immediate");

      // Poll build completion in background and update queue task status
      pollBuildCompletion(queueTask, taskId, worktreePath);
    } catch (Exception e) {
      log.error("Failed to start immediate build", e);
      if (worktreePath != null) {
        gitService.removeWorktree(worktreePath);
      }
      result.put("error", "启动构建失败: " + e.getMessage());
    }
    return result;
  }

  private Map<String, Object> startDirectBuild(
      ProjectConfig snapshot,
      String buildMode,
      String username,
      List<String> modulePaths,
      List<Long> envIds,
      Boolean autoDeploy,
      String selectedBranch,
      String deployServersKey,
      boolean skipGitPull) {

    Map<String, Object> result = new HashMap<>();
    try {
      String taskId =
          buildService.startBuild(
              snapshot.getId(),
              buildMode,
              username,
              modulePaths,
              envIds,
              autoDeploy,
              skipGitPull,
              selectedBranch);

      if (taskId == null) {
        result.put("error", "启动构建失败");
        return result;
      }

      BuildQueueTask queueTask =
          createQueueTask(
              snapshot,
              buildMode,
              username,
              modulePaths,
              envIds,
              autoDeploy,
              selectedBranch,
              deployServersKey);
      queueTask.setStatus(BuildQueueTask.STATUS_EXECUTING);
      queueTask.setStartTime(LocalDateTime.now());
      queueTask.setBuildTaskId(taskId);
      buildQueueRepository.insert(queueTask);

      BuildTask buildTask = buildService.getTask(taskId);
      if (buildTask != null) {
        buildTask.setQueueTaskId(queueTask.getId());
      }

      result.put("taskId", taskId);
      result.put("queueTaskId", queueTask.getId());
      result.put("type", "direct");

      // Poll build completion in background and update queue task status
      pollBuildCompletion(queueTask, taskId, null);
    } catch (Exception e) {
      log.error("Failed to start direct build", e);
      result.put("error", "启动构建失败: " + e.getMessage());
    }
    return result;
  }

  /**
   * Start a background thread that polls for build completion and updates the queue task status.
   * Used by startDirectBuild and startImmediateBuild which don't have inline polling (unlike
   * executeQueuedTask which polls inline).
   */
  private void pollBuildCompletion(BuildQueueTask queueTask, String taskId, String worktreePath) {
    poolManager.submit(
        () -> {
          try {
            BuildTask buildTask = buildService.getTask(taskId);
            while (buildTask != null && buildTask.getEndTime() == null) {
              Thread.sleep(2000);
              buildTask = buildService.getTask(taskId);
            }

            if (buildTask != null) {
              QueryWrapper<BuildRecord> rw = new QueryWrapper<>();
              rw.eq("queue_task_id", queueTask.getId()).last("LIMIT 1");
              BuildRecord record = buildRecordRepository.selectOne(rw);
              if (record != null) {
                queueTask.setBuildRecordId(record.getId());
              }
              queueTask.setLogFilePath(buildTask.getLogFilePath());
              if (buildTask.getStatus().name().contains("SUCCESS")
                  || buildTask.getStatus().name().contains("DEPLOY_SUCCESS")) {
                queueTask.setStatus(BuildQueueTask.STATUS_SUCCESS);
              } else {
                queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
                queueTask.setErrorMessage(buildTask.getErrorMessage());
              }
              queueTask.setCompletionTime(buildTask.getEndTime());
            } else {
              // Build task lost from memory (evicted) — check build record
              QueryWrapper<BuildRecord> rw = new QueryWrapper<>();
              rw.eq("queue_task_id", queueTask.getId()).last("LIMIT 1");
              BuildRecord record = buildRecordRepository.selectOne(rw);
              if (record != null) {
                queueTask.setBuildRecordId(record.getId());
                queueTask.setLogFilePath(record.getLogFilePath());
                if ("SUCCESS".equals(record.getStatus())) {
                  queueTask.setStatus(BuildQueueTask.STATUS_SUCCESS);
                } else {
                  queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
                  queueTask.setErrorMessage("构建失败，详见构建日志");
                }
                queueTask.setCompletionTime(record.getBuildTime());
              } else {
                queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
                queueTask.setErrorMessage("构建任务丢失（可能服务器重启）");
                queueTask.setCompletionTime(LocalDateTime.now());
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            log.error("Error polling build completion for queue task {}", queueTask.getId(), e);
            queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
            queueTask.setErrorMessage("状态更新异常: " + e.getMessage());
            queueTask.setCompletionTime(LocalDateTime.now());
          } finally {
            queueTask.setUpdatedAt(LocalDateTime.now());
            buildQueueRepository.updateById(queueTask);
            if (worktreePath != null) {
              try {
                gitService.removeWorktree(worktreePath);
              } catch (Exception e) {
                log.warn("Failed to remove worktree {}: {}", worktreePath, e.getMessage());
              }
            }
          }
        });
  }

  private Map<String, Object> enqueueTask(
      ProjectConfig snapshot,
      String buildMode,
      String username,
      List<String> modulePaths,
      List<Long> envIds,
      Boolean autoDeploy,
      String selectedBranch,
      String deployServersKey) {

    BuildQueueTask queueTask =
        createQueueTask(
            snapshot,
            buildMode,
            username,
            modulePaths,
            envIds,
            autoDeploy,
            selectedBranch,
            deployServersKey);
    queueTask.setStatus(BuildQueueTask.STATUS_QUEUED);
    buildQueueRepository.insert(queueTask);

    // Activate scheduling when a task enters the queue
    startScheduling();

    Map<String, Object> result = new HashMap<>();
    result.put("queueTaskId", queueTask.getId());
    result.put("type", "queued");
    return result;
  }

  private BuildQueueTask createQueueTask(
      ProjectConfig snapshot,
      String buildMode,
      String username,
      List<String> modulePaths,
      List<Long> envIds,
      Boolean autoDeploy,
      String selectedBranch,
      String deployServersKey) {

    BuildQueueTask task = new BuildQueueTask();
    task.setUsername(username);
    task.setConfigId(snapshot.getId());
    task.setProjectName(snapshot.getProjectName());
    task.setTargetBranch(selectedBranch != null ? selectedBranch : snapshot.getGitBranch());
    task.setDeployEnvironments(
        envIds != null
            ? envIds.stream().map(String::valueOf).collect(Collectors.joining(","))
            : null);
    task.setDeployServers(deployServersKey);
    task.setBuildMode(buildMode);
    task.setSelectedModules(modulePaths != null ? String.join(",", modulePaths) : null);
    task.setAutoDeploy(autoDeploy);
    task.setPriority(0);
    task.setSubmitTime(LocalDateTime.now());
    task.setCreatedAt(LocalDateTime.now());
    task.setUpdatedAt(LocalDateTime.now());
    return task;
  }

  /** Scheduled queue processor — picks next queued task every 5 seconds. */
  @Scheduled(fixedDelay = 5000)
  public void processQueue() {
    boolean alwaysOn = systemSettingsService.isQueueSchedulerAlwaysOn();
    if (!alwaysOn && !schedulingActive) {
      return;
    }

    if (poolManager.getActiveCount() >= poolManager.getMaxSize()) {
      return;
    }

    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    wrapper
        .eq("status", BuildQueueTask.STATUS_QUEUED)
        .orderByDesc("priority")
        .orderByDesc("submit_time")
        .last("LIMIT 5");

    List<BuildQueueTask> candidates = buildQueueRepository.selectList(wrapper);
    BuildQueueTask nextTask = null;
    for (BuildQueueTask candidate : candidates) {
      if (preparingTasks.add(candidate.getId())) {
        nextTask = candidate;
        break;
      }
    }
    if (nextTask == null) {
      // No task picked — count as empty cycle
      if (!alwaysOn) {
        int count = emptyCycleCount.incrementAndGet();
        if (count >= EMPTY_CYCLE_THRESHOLD) {
          stopScheduling();
          log.info(
              "Queue scheduler stopped after {} consecutive empty cycles", EMPTY_CYCLE_THRESHOLD);
        }
      }
      return;
    }

    // Got a task — reset empty cycle counter
    emptyCycleCount.set(0);
    final BuildQueueTask fNextTask = nextTask;
    poolManager.submit(() -> executeQueuedTask(fNextTask));
  }

  private void executeQueuedTask(BuildQueueTask queueTask) {
    String worktreePath = null;
    boolean useWorktree = false;
    try {
      ProjectConfig snapshot = configService.getSnapshot(queueTask.getConfigId());
      if (snapshot == null) {
        preparingTasks.remove(queueTask.getId());
        queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
        queueTask.setErrorMessage("项目配置不存在");
        queueTask.setCompletionTime(LocalDateTime.now());
        buildQueueRepository.updateById(queueTask);
        return;
      }

      List<String> modules = parseList(queueTask.getSelectedModules());
      List<Long> envIds = parseLongList(queueTask.getDeployEnvironments());

      // Check if there are other EXECUTING tasks for the same project
      useWorktree = hasOtherExecutingTasks(queueTask.getId(), queueTask.getConfigId());

      String taskId;
      if (useWorktree) {
        // Create worktree and start build BEFORE updating DB status,
        // so the frontend never sees EXECUTING without a buildTaskId.
        worktreePath = gitService.createWorktree(snapshot, queueTask.getTargetBranch());

        taskId =
            buildService.startBuildFromWorktree(
                snapshot,
                queueTask.getBuildMode(),
                queueTask.getUsername(),
                modules,
                envIds,
                queueTask.getAutoDeploy(),
                queueTask.getTargetBranch(),
                worktreePath,
                queueTask.getId());
      } else {
        // No other tasks running for this project, build directly in project directory
        taskId =
            buildService.startBuild(
                snapshot.getId(),
                queueTask.getBuildMode(),
                queueTask.getUsername(),
                modules,
                envIds,
                queueTask.getAutoDeploy(),
                false,
                queueTask.getTargetBranch());
      }

      // Atomically update: QUEUED → EXECUTING with buildTaskId + worktreePath
      queueTask.setBuildTaskId(taskId);
      queueTask.setWorktreePath(worktreePath);
      queueTask.setStatus(BuildQueueTask.STATUS_EXECUTING);
      queueTask.setStartTime(LocalDateTime.now());
      queueTask.setUpdatedAt(LocalDateTime.now());
      int updated =
          buildQueueRepository.update(
              queueTask,
              new QueryWrapper<BuildQueueTask>()
                  .eq("id", queueTask.getId())
                  .eq("status", BuildQueueTask.STATUS_QUEUED));
      preparingTasks.remove(queueTask.getId());

      if (updated == 0) {
        // Task was cancelled or modified while we were preparing
        if (worktreePath != null) {
          gitService.removeWorktree(worktreePath);
        }
        return;
      }

      // Poll until build completes
      BuildTask buildTask = buildService.getTask(taskId);
      while (buildTask != null && buildTask.getEndTime() == null) {
        Thread.sleep(2000);
        buildTask = buildService.getTask(taskId);
      }

      // Update queue task with results
      if (buildTask != null) {
        // Find the build record
        QueryWrapper<BuildRecord> rw = new QueryWrapper<>();
        rw.eq("queue_task_id", queueTask.getId()).last("LIMIT 1");
        BuildRecord record = buildRecordRepository.selectOne(rw);
        if (record != null) {
          queueTask.setBuildRecordId(record.getId());
        }
        queueTask.setLogFilePath(buildTask.getLogFilePath());

        if (buildTask.getStatus().name().contains("SUCCESS")
            || buildTask.getStatus().name().contains("DEPLOY_SUCCESS")) {
          queueTask.setStatus(BuildQueueTask.STATUS_SUCCESS);
        } else {
          queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
          queueTask.setErrorMessage(buildTask.getErrorMessage());
        }
      } else {
        queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
        queueTask.setErrorMessage("构建任务丢失（可能服务器重启）");
      }
    } catch (Exception e) {
      log.error("Queue task {} failed", queueTask.getId(), e);
      preparingTasks.remove(queueTask.getId());
      queueTask.setStatus(BuildQueueTask.STATUS_FAILURE);
      queueTask.setErrorMessage(e.getMessage());
    } finally {
      queueTask.setCompletionTime(LocalDateTime.now());
      queueTask.setUpdatedAt(LocalDateTime.now());
      buildQueueRepository.updateById(queueTask);

      if (worktreePath != null) {
        gitService.removeWorktree(worktreePath);
      }
    }
  }

  /** Check if there are other EXECUTING tasks for the same project (excluding current task). */
  private boolean hasOtherExecutingTasks(Long currentTaskId, Long configId) {
    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    wrapper
        .eq("config_id", configId)
        .eq("status", BuildQueueTask.STATUS_EXECUTING)
        .ne("id", currentTaskId);
    return buildQueueRepository.selectCount(wrapper) > 0;
  }

  /** Cancel a queued task. */
  public String cancelTask(Long queueTaskId) {
    BuildQueueTask task = buildQueueRepository.selectById(queueTaskId);
    if (task == null) return "任务不存在";
    if (task.getStatus() != BuildQueueTask.STATUS_QUEUED) {
      return "只能取消排队中的任务";
    }
    task.setStatus(BuildQueueTask.STATUS_CANCELLED);
    task.setCompletionTime(LocalDateTime.now());
    task.setUpdatedAt(LocalDateTime.now());
    buildQueueRepository.updateById(task);
    return null;
  }

  /** Stop a running build task. Only for BUILD phase, not DEPLOY. */
  public String stopBuild(String taskId) {
    BuildTask task = buildService.getTask(taskId);
    if (task == null) return "任务不存在";
    String statusName = task.getStatus().name();
    if ("DEPLOYING".equals(statusName)
        || "DEPLOY_SUCCESS".equals(statusName)
        || "DEPLOY_FAILED".equals(statusName)) {
      return "部署中的任务不能停止";
    }
    if (task.getStatus().name().contains("SUCCESS") || task.getStatus().name().contains("FAIL")) {
      return "任务已完成";
    }
    task.requestStop();
    Process proc = task.getRunningProcess();
    if (proc != null) {
      proc.destroyForcibly();
    }
    return null;
  }

  /** Query queue tasks with search and sort support. */
  public IPage<BuildQueueTask> listTasks(
      int pageNum,
      int pageSize,
      String searchUser,
      String searchProject,
      String searchBranch,
      String searchStatus,
      String searchBuildMode,
      String sortField,
      String sortOrder) {

    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    if (searchUser != null && !searchUser.isEmpty()) {
      wrapper.like("username", searchUser);
    }
    if (searchProject != null && !searchProject.isEmpty()) {
      wrapper.like("project_name", searchProject);
    }
    if (searchBranch != null && !searchBranch.isEmpty()) {
      wrapper.like("target_branch", searchBranch);
    }
    if (searchStatus != null && !searchStatus.isEmpty()) {
      wrapper.eq("status", Integer.parseInt(searchStatus));
    }
    if (searchBuildMode != null && !searchBuildMode.isEmpty()) {
      wrapper.eq("build_mode", searchBuildMode);
    }

    // Sorting
    if (sortField != null && !sortField.isEmpty()) {
      boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
      switch (sortField) {
        case "submit_time":
          wrapper.orderBy(true, isAsc, "submit_time");
          break;
        case "completion_time":
          wrapper.orderBy(true, isAsc, "completion_time");
          break;
        case "priority":
          wrapper.orderBy(true, isAsc, "priority");
          break;
        default:
          wrapper.orderByDesc("id");
      }
    } else {
      wrapper.orderByDesc("id");
    }

    return buildQueueRepository.selectPage(new Page<>(pageNum, pageSize), wrapper);
  }

  /** Get a single queue task by ID. */
  public BuildQueueTask getTask(Long id) {
    return buildQueueRepository.selectById(id);
  }

  /** Also clean up worktree for immediate builds after they complete. */
  @Scheduled(fixedDelay = 3600000)
  public void cleanupCompletedWorktrees() {
    // Check immediate builds (EXECUTING or completed) that have a worktree path
    // and whose build task has finished
    QueryWrapper<BuildQueueTask> wrapper = new QueryWrapper<>();
    wrapper
        .isNotNull("worktree_path")
        .isNotNull("build_task_id")
        .in(
            "status",
            Arrays.asList(
                BuildQueueTask.STATUS_EXECUTING,
                BuildQueueTask.STATUS_SUCCESS,
                BuildQueueTask.STATUS_FAILURE));
    List<BuildQueueTask> tasks = buildQueueRepository.selectList(wrapper);
    for (BuildQueueTask qt : tasks) {
      BuildTask bt = buildService.getTask(qt.getBuildTaskId());
      if (bt != null && bt.getEndTime() != null) {
        // Build finished, clean up worktree
        gitService.removeWorktree(qt.getWorktreePath());
        qt.setWorktreePath(null);

        // Update status from build task if still EXECUTING
        if (qt.getStatus() == BuildQueueTask.STATUS_EXECUTING) {
          // Find build record
          QueryWrapper<BuildRecord> rw = new QueryWrapper<>();
          rw.eq("queue_task_id", qt.getId()).last("LIMIT 1");
          BuildRecord record = buildRecordRepository.selectOne(rw);
          if (record != null) {
            qt.setBuildRecordId(record.getId());
          }
          qt.setLogFilePath(bt.getLogFilePath());
          if (bt.getStatus().name().contains("SUCCESS")
              || bt.getStatus().name().contains("DEPLOY_SUCCESS")) {
            qt.setStatus(BuildQueueTask.STATUS_SUCCESS);
          } else {
            qt.setStatus(BuildQueueTask.STATUS_FAILURE);
            qt.setErrorMessage(bt.getErrorMessage());
          }
          qt.setCompletionTime(bt.getEndTime());
        }
        qt.setUpdatedAt(LocalDateTime.now());
        buildQueueRepository.updateById(qt);
      } else if (bt == null && qt.getStatus() == BuildQueueTask.STATUS_EXECUTING) {
        // Build task no longer in memory (server restart or eviction)
        // Check if there's a build record
        QueryWrapper<BuildRecord> rw = new QueryWrapper<>();
        rw.eq("queue_task_id", qt.getId()).last("LIMIT 1");
        BuildRecord record = buildRecordRepository.selectOne(rw);
        if (record != null) {
          // Build record exists, use its status
          qt.setBuildRecordId(record.getId());
          qt.setLogFilePath(record.getLogFilePath() != null ? record.getLogFilePath() : null);
          if ("SUCCESS".equals(record.getStatus())) {
            qt.setStatus(BuildQueueTask.STATUS_SUCCESS);
          } else {
            qt.setStatus(BuildQueueTask.STATUS_FAILURE);
            qt.setErrorMessage("构建失败，详见构建日志");
          }
          qt.setCompletionTime(record.getBuildTime());
        } else {
          // No build record, check if it's been stuck for more than 5 minutes
          if (qt.getStartTime() != null
              && qt.getStartTime().isBefore(LocalDateTime.now().minusMinutes(5))) {
            qt.setStatus(BuildQueueTask.STATUS_FAILURE);
            qt.setErrorMessage("构建任务丢失（服务器重启或内存清除）");
            qt.setCompletionTime(LocalDateTime.now());
          }
        }
        // Clean up worktree
        if (qt.getWorktreePath() != null) {
          gitService.removeWorktree(qt.getWorktreePath());
          qt.setWorktreePath(null);
        }
        qt.setUpdatedAt(LocalDateTime.now());
        buildQueueRepository.updateById(qt);
      }
    }

    // Also check direct builds (no worktree) whose build task has finished
    QueryWrapper<BuildQueueTask> directWrapper = new QueryWrapper<>();
    directWrapper
        .isNull("worktree_path")
        .isNotNull("build_task_id")
        .eq("status", BuildQueueTask.STATUS_EXECUTING);
    List<BuildQueueTask> directTasks = buildQueueRepository.selectList(directWrapper);
    for (BuildQueueTask qt : directTasks) {
      BuildTask bt = buildService.getTask(qt.getBuildTaskId());
      if (bt != null && bt.getEndTime() != null) {
        QueryWrapper<BuildRecord> rw = new QueryWrapper<>();
        rw.eq("queue_task_id", qt.getId()).last("LIMIT 1");
        BuildRecord record = buildRecordRepository.selectOne(rw);
        if (record != null) {
          qt.setBuildRecordId(record.getId());
        }
        qt.setLogFilePath(bt.getLogFilePath());
        if (bt.getStatus().name().contains("SUCCESS")
            || bt.getStatus().name().contains("DEPLOY_SUCCESS")) {
          qt.setStatus(BuildQueueTask.STATUS_SUCCESS);
        } else {
          qt.setStatus(BuildQueueTask.STATUS_FAILURE);
          qt.setErrorMessage(bt.getErrorMessage());
        }
        qt.setCompletionTime(bt.getEndTime());
        qt.setUpdatedAt(LocalDateTime.now());
        buildQueueRepository.updateById(qt);
      } else if (bt == null) {
        QueryWrapper<BuildRecord> rw = new QueryWrapper<>();
        rw.eq("queue_task_id", qt.getId()).last("LIMIT 1");
        BuildRecord record = buildRecordRepository.selectOne(rw);
        if (record != null) {
          qt.setBuildRecordId(record.getId());
          qt.setLogFilePath(record.getLogFilePath());
          if ("SUCCESS".equals(record.getStatus())) {
            qt.setStatus(BuildQueueTask.STATUS_SUCCESS);
          } else {
            qt.setStatus(BuildQueueTask.STATUS_FAILURE);
            qt.setErrorMessage("构建失败，详见构建日志");
          }
          qt.setCompletionTime(record.getBuildTime());
        } else if (qt.getStartTime() != null
            && qt.getStartTime().isBefore(LocalDateTime.now().minusMinutes(5))) {
          qt.setStatus(BuildQueueTask.STATUS_FAILURE);
          qt.setErrorMessage("构建任务丢失（服务器重启或内存清除）");
          qt.setCompletionTime(LocalDateTime.now());
        }
        qt.setUpdatedAt(LocalDateTime.now());
        buildQueueRepository.updateById(qt);
      }
    }

    // Also check for tasks stuck in EXECUTING without a buildTaskId (failed during startup)
    // Mark them as FAILURE if they've been stuck for more than 10 minutes
    QueryWrapper<BuildQueueTask> stuckWrapper = new QueryWrapper<>();
    stuckWrapper
        .eq("status", BuildQueueTask.STATUS_EXECUTING)
        .isNull("build_task_id")
        .lt("start_time", LocalDateTime.now().minusMinutes(10));
    List<BuildQueueTask> stuckTasks = buildQueueRepository.selectList(stuckWrapper);
    for (BuildQueueTask qt : stuckTasks) {
      qt.setStatus(BuildQueueTask.STATUS_FAILURE);
      qt.setErrorMessage("构建启动超时或失败");
      qt.setCompletionTime(LocalDateTime.now());
      qt.setUpdatedAt(LocalDateTime.now());
      buildQueueRepository.updateById(qt);
      if (qt.getWorktreePath() != null) {
        gitService.removeWorktree(qt.getWorktreePath());
      }
    }
    if (!stuckTasks.isEmpty()) {
      log.info("Marked {} stuck queue tasks as FAILURE", stuckTasks.size());
    }
  }

  public void startScheduling() {
    schedulingActive = true;
    emptyCycleCount.set(0);
  }

  public void stopScheduling() {
    schedulingActive = false;
  }

  public boolean isSchedulingActive() {
    return schedulingActive || systemSettingsService.isQueueSchedulerAlwaysOn();
  }

  private List<String> parseList(String commaSeparated) {
    if (commaSeparated == null || commaSeparated.isEmpty()) return null;
    return Arrays.asList(commaSeparated.split(","));
  }

  private List<Long> parseLongList(String commaSeparated) {
    if (commaSeparated == null || commaSeparated.isEmpty()) return null;
    List<Long> result = new ArrayList<>();
    for (String s : commaSeparated.split(",")) {
      try {
        result.add(Long.parseLong(s.trim()));
      } catch (NumberFormatException e) {
        // skip
      }
    }
    return result;
  }
}
