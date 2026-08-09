package com.autodeploy.controller;

import com.autodeploy.model.BuildTask;
import com.autodeploy.service.BuildHistoryService;
import com.autodeploy.service.BuildService;
import com.autodeploy.service.ConfigService;
import com.autodeploy.service.DeployScriptService;
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
      Model model) {
    String username = (String) SecurityUtils.getSubject().getPrincipal();
    boolean auto = !"false".equalsIgnoreCase(autoDeploy);
    String taskId =
        buildService.startBuild(configId, buildMode, username, modulePaths, envIds, auto);
    if (taskId == null) {
      model.addAttribute("error", "项目配置不存在");
    } else {
      model.addAttribute("taskId", taskId);
    }
    return "redirect:/build";
  }

  @GetMapping("/build/log/{taskId}")
  public String logPage(@PathVariable String taskId, Model model) {
    BuildTask task = buildService.getTask(taskId);
    if (task != null) {
      model.addAttribute("task", task);
      model.addAttribute("isActive", true);
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
}
