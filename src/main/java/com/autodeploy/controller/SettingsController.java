package com.autodeploy.controller;

import com.autodeploy.service.BuildQueueService;
import com.autodeploy.service.DeployService;
import com.autodeploy.service.SystemSettingsService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SettingsController {

  @Autowired private SystemSettingsService settingsService;

  @Autowired private DeployService deployService;
  @Autowired private BuildQueueService buildQueueService;

  // Simple cache for env var listing (60-second TTL)
  private List<String> cachedEnvVars;
  private long cacheTime;
  private static final long CACHE_TTL_MS = 60000;

  @GetMapping("/settings")
  public String settingsPage(Model model) {
    model.addAttribute(
        "maxConcurrent", settingsService.getInt(SystemSettingsService.KEY_MAX_CONCURRENT, 20));
    model.addAttribute(
        "logRetentionDays", settingsService.getInt(SystemSettingsService.KEY_LOG_RETENTION, 7));
    model.addAttribute(
        "historyRetentionDays",
        settingsService.getInt(SystemSettingsService.KEY_HISTORY_RETENTION, 90));
    model.addAttribute(
        "buildServerHost", settingsService.get(SystemSettingsService.KEY_BUILD_SERVER_HOST));
    model.addAttribute(
        "buildServerPort", settingsService.getInt(SystemSettingsService.KEY_BUILD_SERVER_PORT, 22));
    model.addAttribute(
        "buildServerUser", settingsService.get(SystemSettingsService.KEY_BUILD_SERVER_USER));
    model.addAttribute(
        "buildServerAuthEnv", settingsService.get(SystemSettingsService.KEY_BUILD_SERVER_AUTH_ENV));
    model.addAttribute("envVarRefs", settingsService.listEnvVarRefs());
    model.addAttribute("envVarStatuses", settingsService.checkEnvVarStatus());
    model.addAttribute("schedulerActive", buildQueueService.isSchedulingActive());
    model.addAttribute("schedulerAlwaysOn", settingsService.isQueueSchedulerAlwaysOn());
    return "settings/index";
  }

  @PostMapping("/settings/concurrent")
  public String saveConcurrent(@RequestParam int maxConcurrent, Model model) {
    String error = settingsService.saveMaxConcurrent(maxConcurrent);
    if (error != null) {
      model.addAttribute("error", error);
    } else {
      model.addAttribute("success", "并发数已更新");
    }
    return "redirect:/settings";
  }

  @PostMapping("/settings/retention")
  public String saveRetention(
      @RequestParam int logRetentionDays, @RequestParam int historyRetentionDays) {
    settingsService.saveLogRetentionDays(logRetentionDays);
    settingsService.saveHistoryRetentionDays(historyRetentionDays);
    return "redirect:/settings";
  }

  @PostMapping("/settings/build-server")
  public String saveBuildServer(
      @RequestParam String host,
      @RequestParam int port,
      @RequestParam String user,
      @RequestParam String authEnvKey) {
    settingsService.saveBuildServerConfig(host, port, user, authEnvKey);
    return "redirect:/settings";
  }

  @PostMapping("/settings/env-var")
  public String addEnvVar(
      @RequestParam String varKey, @RequestParam(required = false) String description) {
    settingsService.addEnvVarRef(varKey, description);
    return "redirect:/settings";
  }

  @GetMapping("/settings/env-var/delete/{id}")
  public String deleteEnvVar(@PathVariable Long id) {
    settingsService.deleteEnvVarRef(id);
    return "redirect:/settings";
  }

  @GetMapping("/api/settings/env-vars")
  @ResponseBody
  public List<String> listSystemEnvVars() {
    long now = System.currentTimeMillis();
    if (cachedEnvVars == null || (now - cacheTime) > CACHE_TTL_MS) {
      cachedEnvVars = System.getenv().keySet().stream().sorted().collect(Collectors.toList());
      cacheTime = now;
    }
    return cachedEnvVars;
  }

  @PostMapping("/settings/scheduler/start")
  public String startScheduler() {
    buildQueueService.startScheduling();
    return "redirect:/settings";
  }

  @PostMapping("/settings/scheduler/stop")
  public String stopScheduler() {
    buildQueueService.stopScheduling();
    return "redirect:/settings";
  }

  @PostMapping("/settings/scheduler/always-on")
  public String saveSchedulerAlwaysOn(
      @RequestParam(required = false, defaultValue = "false") boolean alwaysOn) {
    settingsService.saveQueueSchedulerAlwaysOn(alwaysOn);
    if (alwaysOn) {
      buildQueueService.startScheduling();
    }
    return "redirect:/settings";
  }

  @PostMapping("/api/settings/test-build-server")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> testBuildServer() {
    Map<String, Object> result = new HashMap<>();
    try {
      String host = settingsService.get(SystemSettingsService.KEY_BUILD_SERVER_HOST);
      int port = settingsService.getInt(SystemSettingsService.KEY_BUILD_SERVER_PORT, 22);
      String user = settingsService.get(SystemSettingsService.KEY_BUILD_SERVER_USER);
      String authEnvKey = settingsService.get(SystemSettingsService.KEY_BUILD_SERVER_AUTH_ENV);
      if (host == null || host.trim().isEmpty()) {
        result.put("success", false);
        result.put("message", "未配置构建服务器地址");
        return ResponseEntity.ok(result);
      }
      boolean ok = deployService.testConnection(host, port, user, authEnvKey);
      result.put("success", ok);
      result.put("message", ok ? "连接成功" : "连接失败，请检查配置和环境变量");
    } catch (Exception e) {
      result.put("success", false);
      result.put("message", "连接异常: " + e.getMessage());
    }
    return ResponseEntity.ok(result);
  }
}
