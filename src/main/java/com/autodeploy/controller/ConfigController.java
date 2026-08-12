package com.autodeploy.controller;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.service.ConfigService;
import com.autodeploy.service.DeployEnvironmentService;
import com.autodeploy.service.DeployService;
import com.autodeploy.service.ModuleScanService;
import com.autodeploy.service.ServerInfoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ConfigController {

  private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

  @Autowired private ConfigService configService;

  @Autowired private DeployService deployService;

  @Autowired private ServerInfoService serverInfoService;

  @Autowired private DeployEnvironmentService environmentService;

  @Autowired private ModuleScanService moduleScanService;

  @Autowired private ObjectMapper objectMapper;

  // ===== Page routes =====

  @GetMapping("/config")
  public String listPage(Model model) {
    List<ProjectConfig> configs = configService.listAll();
    model.addAttribute("configs", configs);
    Map<Long, List<ProjectEnvServer>> envServerMap = new HashMap<>();
    for (ProjectConfig c : configs) {
      envServerMap.put(c.getId(), serverInfoService.listProjectAssociations(c.getId()));
    }
    model.addAttribute("envServerMap", envServerMap);
    return "config/list";
  }

  @GetMapping("/config/new")
  public String newPage(Model model) {
    model.addAttribute("config", new ProjectConfig());
    model.addAttribute("isNew", true);
    model.addAttribute("environments", environmentService.listAll());
    model.addAttribute("projectEnvServers", java.util.Collections.emptyList());
    return "config/edit";
  }

  @GetMapping("/config/edit/{id}")
  public String editPage(@PathVariable Long id, Model model) {
    ProjectConfig config = configService.getById(id);
    if (config == null) {
      return "redirect:/config";
    }
    model.addAttribute("config", config);
    model.addAttribute("isNew", false);
    model.addAttribute("environments", environmentService.listAll());
    model.addAttribute("projectEnvServers", serverInfoService.listProjectAssociations(id));
    return "config/edit";
  }

  @PostMapping("/config/save")
  public String save(
      @RequestParam(value = "envServers", required = false) String envServersJson,
      ProjectConfig config,
      Model model) {
    List<long[]> envServerPairs = parseEnvServersJson(envServersJson);
    String error = configService.saveWithEnvServers(config, envServerPairs);
    if (error != null) {
      model.addAttribute("error", error);
      model.addAttribute("config", config);
      boolean isNew = config.getId() == null;
      model.addAttribute("isNew", isNew);
      model.addAttribute("environments", environmentService.listAll());
      if (!isNew) {
        model.addAttribute(
            "projectEnvServers", serverInfoService.listProjectAssociations(config.getId()));
      } else {
        model.addAttribute("projectEnvServers", java.util.Collections.emptyList());
      }
      return "config/edit";
    }
    // Auto-trigger module scan after successful save (for non-NODE projects with git repo)
    if (config.getId() != null
        && !"NODE".equals(config.getLanguageType())
        && config.getGitRepoUrl() != null
        && !config.getGitRepoUrl().trim().isEmpty()) {
      moduleScanService.startScan(config.getId());
    }
    return "redirect:/config/edit/" + config.getId();
  }

  @GetMapping("/config/delete/{id}")
  public String delete(@PathVariable Long id) {
    configService.delete(id);
    return "redirect:/config";
  }

  @GetMapping("/config/copy/{id}")
  public String copy(@PathVariable Long id) {
    Long newId = configService.copyConfig(id);
    if (newId == null) {
      return "redirect:/config";
    }
    return "redirect:/config/edit/" + newId;
  }

  @GetMapping("/config/guide")
  public String guidePage() {
    return "config/guide";
  }

  // ===== HTTP API routes =====

  @GetMapping("/api/configs")
  @ResponseBody
  public ResponseEntity<List<ProjectConfig>> listApi() {
    return ResponseEntity.ok(configService.listAll());
  }

  @GetMapping("/api/configs/{id}")
  @ResponseBody
  public ResponseEntity<ProjectConfig> getApi(@PathVariable Long id) {
    ProjectConfig config = configService.getById(id);
    if (config == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(config);
  }

  @PostMapping("/api/configs")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> saveApi(@RequestBody ProjectConfig config) {
    Map<String, Object> result = new HashMap<>();
    String error = configService.save(config);
    if (error != null) {
      result.put("success", false);
      result.put("error", error);
      return ResponseEntity.badRequest().body(result);
    }
    result.put("success", true);
    result.put("config", config);
    return ResponseEntity.ok(result);
  }

  @DeleteMapping("/api/configs/{id}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> deleteApi(@PathVariable Long id) {
    configService.delete(id);
    Map<String, Object> result = new HashMap<>();
    result.put("success", true);
    return ResponseEntity.ok(result);
  }

  @PostMapping("/api/config/test-deploy-server")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> testDeployServer(
      @RequestParam String host,
      @RequestParam int port,
      @RequestParam String user,
      @RequestParam String authEnvKey) {
    Map<String, Object> result = new HashMap<>();
    try {
      boolean ok = deployService.testConnection(host, port, user, authEnvKey);
      result.put("success", ok);
      result.put("message", ok ? "连接成功" : "连接失败，请检查配置和环境变量");
    } catch (Exception e) {
      result.put("success", false);
      result.put("message", "连接异常: " + e.getMessage());
    }
    return ResponseEntity.ok(result);
  }

  // ===== Project-Environment-Server association API =====

  @GetMapping("/api/config/{projectId}/env-servers")
  @ResponseBody
  public ResponseEntity<List<ProjectEnvServer>> listProjectEnvServers(
      @PathVariable Long projectId) {
    return ResponseEntity.ok(serverInfoService.listProjectAssociations(projectId));
  }

  @PostMapping("/api/config/{projectId}/env-servers")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> addProjectEnvServer(
      @PathVariable Long projectId, @RequestParam Long environmentId, @RequestParam Long serverId) {
    Map<String, Object> result = new HashMap<>();
    serverInfoService.addProjectServer(projectId, environmentId, serverId);
    result.put("success", true);
    return ResponseEntity.ok(result);
  }

  @PatchMapping("/api/config/env-servers/{id}/toggle-deploy")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> toggleDeploy(
      @PathVariable Long id, @RequestParam Boolean enabled) {
    Map<String, Object> result = new HashMap<>();
    serverInfoService.toggleDeploy(id, enabled);
    result.put("success", true);
    return ResponseEntity.ok(result);
  }

  @DeleteMapping("/api/config/env-servers/{id}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> removeProjectEnvServer(@PathVariable Long id) {
    serverInfoService.removeProjectServer(id);
    Map<String, Object> result = new HashMap<>();
    result.put("success", true);
    return ResponseEntity.ok(result);
  }

  // ===== Module scan API =====

  @PostMapping("/api/config/{projectId}/scan-modules")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> scanModules(@PathVariable Long projectId) {
    Map<String, Object> result = new HashMap<>();
    String error = moduleScanService.startScan(projectId);
    if (error != null) {
      result.put("success", false);
      result.put("message", error);
    } else {
      result.put("success", true);
      result.put("message", "扫描已启动");
    }
    return ResponseEntity.ok(result);
  }

  @GetMapping("/api/config/{projectId}/modules")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> getModules(@PathVariable Long projectId) {
    Map<String, Object> result = moduleScanService.getScanStatus(projectId);
    result.put("modules", moduleScanService.listModules(projectId));
    return ResponseEntity.ok(result);
  }

  private List<long[]> parseEnvServersJson(String json) {
    List<long[]> pairs = new ArrayList<>();
    if (json == null || json.trim().isEmpty()) {
      return pairs;
    }
    try {
      List<List<Long>> parsed =
          objectMapper.readValue(json, new TypeReference<List<List<Long>>>() {});
      for (List<Long> pair : parsed) {
        if (pair.size() >= 2 && pair.get(0) != null && pair.get(1) != null) {
          pairs.add(new long[] {pair.get(0), pair.get(1)});
        }
      }
    } catch (Exception e) {
      log.warn("Failed to parse envServers JSON: {}", json, e);
    }
    return pairs;
  }
}
