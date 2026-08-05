package com.autodeploy.controller;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.service.ConfigService;
import com.autodeploy.service.DeployService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    @Autowired
    private ConfigService configService;

    @Autowired
    private DeployService deployService;

    @Autowired
    private ObjectMapper objectMapper;

    // ===== Page routes =====

    @GetMapping("/config")
    public String listPage(Model model) {
        List<ProjectConfig> configs = configService.listAll();
        model.addAttribute("configs", configs);
        return "config/list";
    }

    @GetMapping("/config/new")
    public String newPage(Model model) {
        model.addAttribute("config", new ProjectConfig());
        model.addAttribute("isNew", true);
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
        return "config/edit";
    }

    @PostMapping("/config/save")
    public String save(ProjectConfig config, Model model) {
        String error = configService.save(config);
        if (error != null) {
            model.addAttribute("error", error);
            model.addAttribute("config", config);
            model.addAttribute("isNew", config.getId() == null);
            return "config/edit";
        }
        return "redirect:/config";
    }

    @GetMapping("/config/delete/{id}")
    public String delete(@PathVariable Long id) {
        configService.delete(id);
        return "redirect:/config";
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
}
