package com.autodeploy.controller;

import com.autodeploy.model.ServerInfo;
import com.autodeploy.service.DeployEnvironmentService;
import com.autodeploy.service.DeployService;
import com.autodeploy.service.ServerInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ServerInfoController {

    @Autowired
    private ServerInfoService serverInfoService;

    @Autowired
    private DeployEnvironmentService environmentService;

    @Autowired
    private DeployService deployService;

    @GetMapping("/server")
    public String listPage(Model model) {
        model.addAttribute("servers", serverInfoService.listAll());
        model.addAttribute("environments", environmentService.listAll());
        model.addAttribute("server", new ServerInfo());
        return "server/index";
    }

    @PostMapping("/server/save")
    public String save(ServerInfo server) {
        String error = serverInfoService.save(server);
        if (error != null) {
            return "redirect:/server?error=" + error;
        }
        return "redirect:/server";
    }

    @GetMapping("/server/delete/{id}")
    public String delete(@PathVariable Long id) {
        serverInfoService.delete(id);
        return "redirect:/server";
    }

    // ===== API =====

    @GetMapping("/api/servers")
    @ResponseBody
    public ResponseEntity<List<ServerInfo>> listApi(@RequestParam(required = false) String type) {
        if (type != null && !type.isEmpty()) {
            return ResponseEntity.ok(serverInfoService.listByType(type));
        }
        return ResponseEntity.ok(serverInfoService.listAll());
    }

    @GetMapping("/api/servers/{id}")
    @ResponseBody
    public ResponseEntity<ServerInfo> getApi(@PathVariable Long id) {
        ServerInfo server = serverInfoService.getById(id);
        if (server == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(server);
    }

    @PostMapping("/api/servers")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveApi(@RequestBody ServerInfo server) {
        Map<String, Object> result = new HashMap<>();
        String error = serverInfoService.save(server);
        if (error != null) {
            result.put("success", false);
            result.put("error", error);
            return ResponseEntity.badRequest().body(result);
        }
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/api/servers/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteApi(@PathVariable Long id) {
        serverInfoService.delete(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/servers/by-environment/{environmentId}")
    @ResponseBody
    public ResponseEntity<List<ServerInfo>> listByEnvironment(@PathVariable Long environmentId) {
        return ResponseEntity.ok(serverInfoService.listByEnvironmentId(environmentId));
    }

    @PostMapping("/api/servers/test-connection")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestParam String host,
            @RequestParam(defaultValue = "22") int port,
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
