package com.autodeploy.controller;

import com.autodeploy.model.BuildQueueTask;
import com.autodeploy.model.BuildTask;
import com.autodeploy.service.BuildHistoryService;
import com.autodeploy.service.BuildQueueService;
import com.autodeploy.service.BuildService;
import com.autodeploy.service.ConfigService;
import com.autodeploy.service.DeployScriptService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class BuildController {

  @Autowired private BuildService buildService;
  @Autowired private BuildHistoryService buildHistoryService;
  @Autowired private ConfigService configService;
  @Autowired private DeployScriptService deployScriptService;
  @Autowired private BuildQueueService buildQueueService;

  @GetMapping("/build")
  public String buildPage(Model model) {
    model.addAttribute("configs", configService.listAll());
    model.addAttribute("tasks", buildService.listTasks());
    return "build/index";
  }

  @PostMapping("/build/start")
  public String startBuild(
      @RequestParam Long configId,
      @RequestParam(defaultValue = "LOCAL") String buildMode,
      @RequestParam(required = false) List<String> modulePaths,
      @RequestParam(required = false) List<Long> envIds,
      @RequestParam(required = false) String autoDeploy,
      @RequestParam(required = false) String selectedBranch,
      @RequestParam(required = false) String forceStart,
      @RequestParam(required = false) String skipGitPull,
      Model model) {
    String username = (String) SecurityUtils.getSubject().getPrincipal();
    boolean auto = !"false".equalsIgnoreCase(autoDeploy);
    boolean force = "true".equalsIgnoreCase(forceStart);
    boolean skipPull = "true".equalsIgnoreCase(skipGitPull);

    Map<String, Object> result =
        buildQueueService.submitTask(
            configId,
            buildMode,
            username,
            modulePaths,
            envIds,
            auto,
            selectedBranch,
            force,
            skipPull);

    if (result.containsKey("error")) {
      model.addAttribute("error", result.get("error"));
    }
    return "redirect:/build";
  }

  @GetMapping("/build/log/{taskId}")
  public String logPage(@PathVariable String taskId, Model model) {
    BuildTask task = buildService.getTask(taskId);
    if (task != null) {
      model.addAttribute("task", task);
      model.addAttribute("isActive", true);
      model.addAttribute("taskId", taskId);
    } else {
      // Task no longer in memory, try to find log from build records
      model.addAttribute("isActive", false);
      model.addAttribute("taskId", taskId);
    }
    return "build/log";
  }

  @GetMapping("/api/build/sse/{taskId}")
  public SseEmitter streamLog(@PathVariable String taskId) {
    SseEmitter emitter = buildService.subscribeLog(taskId);
    if (emitter == null) {
      SseEmitter empty = new SseEmitter();
      empty.complete();
      return empty;
    }
    return emitter;
  }

  @GetMapping("/api/build/tasks")
  @ResponseBody
  public List<Map<String, Object>> listTasks() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (BuildTask task : buildService.listTasks()) {
      Map<String, Object> map = new HashMap<>();
      map.put("taskId", task.getTaskId());
      map.put("projectName", task.getConfigSnapshot().getProjectName());
      map.put("status", task.getStatus().getLabel());
      map.put("buildMode", task.getBuildMode());
      map.put("startTime", task.getStartTime());
      map.put("endTime", task.getEndTime());
      map.put("user", task.getCurrentUser());
      result.add(map);
    }
    return result;
  }

  @GetMapping("/api/build/git-status/{configId}")
  @ResponseBody
  public Map<String, Object> gitStatus(@PathVariable Long configId) {
    Map<String, Object> result = new HashMap<>();
    result.put("hasUncommittedChanges", buildService.hasUncommittedChanges(configId));
    result.put("currentBranch", buildService.getCurrentBranch(configId));
    return result;
  }

  @GetMapping("/api/build/check-duplicate")
  @ResponseBody
  public Map<String, Object> checkDuplicate(
      @RequestParam Long configId,
      @RequestParam(required = false) String selectedBranch,
      @RequestParam(required = false) List<Long> envIds) {
    String username = (String) SecurityUtils.getSubject().getPrincipal();
    boolean duplicate = buildQueueService.isDuplicate(username, configId, selectedBranch, envIds);
    boolean hasSameUserProject = buildQueueService.hasSameUserProjectTasks(username, configId);
    Map<String, Object> result = new HashMap<>();
    result.put("duplicate", duplicate);
    result.put("hasSameUserProject", hasSameUserProject);
    return result;
  }

  @GetMapping("/api/build/branches/{configId}")
  @ResponseBody
  public Map<String, Object> listBranches(@PathVariable Long configId) {
    Map<String, Object> result = new HashMap<>();
    result.put("currentBranch", buildService.getCurrentBranch(configId));
    result.put("branches", buildService.listBranches(configId));
    return result;
  }

  @GetMapping("/api/build/log-content/{taskId}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> getLogContent(
      @PathVariable String taskId, @RequestParam(defaultValue = "500") int tailLines) {
    Map<String, Object> result = buildService.readLogFileTail(taskId, tailLines);
    if (result.get("content") == null) {
      result.put("content", "日志文件未找到");
      result.put("hasMore", false);
      result.put("totalLines", 0);
    }
    return ResponseEntity.ok(result);
  }

  /** Generate deployment script for manual deployment. */
  @GetMapping("/api/build/deploy-script/{taskId}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> getDeployScript(
      @PathVariable String taskId, @RequestParam(defaultValue = "windows") String os) {
    BuildTask task = buildService.getTask(taskId);
    Map<String, Object> result = new HashMap<>();
    if (task == null) {
      result.put("success", false);
      result.put("message", "任务不存在");
      return ResponseEntity.badRequest().body(result);
    }
    if (!"REMOTE".equals(task.getBuildMode())
        || task.getAutoDeploy() == null
        || task.getAutoDeploy()) {
      result.put("success", false);
      result.put("message", "仅远程构建且未自动部署的任务可生成部署脚本");
      return ResponseEntity.badRequest().body(result);
    }
    String script = deployScriptService.generate(task, os);
    result.put("success", true);
    result.put("script", script);
    result.put(
        "filename",
        "deploy_"
            + task.getConfigSnapshot().getProjectName()
            + "_"
            + taskId
            + ("windows".equals(os) ? ".bat" : ".sh"));
    return ResponseEntity.ok(result);
  }

  @GetMapping("/api/queue/tasks")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> listQueueTasks(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String searchUser,
      @RequestParam(required = false) String searchProject,
      @RequestParam(required = false) String searchBranch,
      @RequestParam(required = false) String searchStatus,
      @RequestParam(required = false) String searchBuildMode,
      @RequestParam(required = false) String sortField,
      @RequestParam(required = false) String sortOrder) {
    IPage<BuildQueueTask> result =
        buildQueueService.listTasks(
            page,
            size,
            searchUser,
            searchProject,
            searchBranch,
            searchStatus,
            searchBuildMode,
            sortField,
            sortOrder);
    Map<String, Object> response = new HashMap<>();
    response.put("records", result.getRecords());
    response.put("total", result.getTotal());
    response.put("pages", result.getPages());
    response.put("current", result.getCurrent());
    return ResponseEntity.ok(response);
  }

  @GetMapping("/api/queue/task/{id}")
  @ResponseBody
  public ResponseEntity<BuildQueueTask> getQueueTask(@PathVariable Long id) {
    BuildQueueTask task = buildQueueService.getTask(id);
    if (task == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(task);
  }

  @GetMapping("/api/queue/task/{id}/page")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> getTaskPage(
      @PathVariable Long id, @RequestParam(defaultValue = "50") int size) {
    Map<String, Object> result = new HashMap<>();
    int page = buildQueueService.getPageForTask(id, size);
    result.put("page", page);
    return ResponseEntity.ok(result);
  }

  @PostMapping("/api/queue/cancel/{id}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> cancelQueueTask(@PathVariable Long id) {
    Map<String, Object> result = new HashMap<>();
    String error = buildQueueService.cancelTask(id);
    if (error != null) {
      result.put("success", false);
      result.put("message", error);
      return ResponseEntity.badRequest().body(result);
    }
    result.put("success", true);
    return ResponseEntity.ok(result);
  }

  @PostMapping("/api/build/stop/{taskId}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> stopBuild(@PathVariable String taskId) {
    Map<String, Object> result = new HashMap<>();
    String error = buildQueueService.stopBuild(taskId);
    if (error != null) {
      result.put("success", false);
      result.put("message", error);
      return ResponseEntity.badRequest().body(result);
    }
    result.put("success", true);
    return ResponseEntity.ok(result);
  }
}
