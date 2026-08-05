package com.autodeploy.controller;

import com.autodeploy.model.BuildTask;
import com.autodeploy.model.ProjectConfig;
import com.autodeploy.service.BuildService;
import com.autodeploy.service.BuildHistoryService;
import com.autodeploy.service.ConfigService;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class BuildController {

    @Autowired private BuildService buildService;
    @Autowired private BuildHistoryService buildHistoryService;
    @Autowired private ConfigService configService;

    @GetMapping("/build")
    public String buildPage(Model model) {
        model.addAttribute("configs", configService.listAll());
        model.addAttribute("tasks", buildService.listTasks());
        return "build/index";
    }

    @PostMapping("/build/start")
    public String startBuild(@RequestParam Long configId,
                             @RequestParam(defaultValue = "LOCAL") String buildMode,
                             Model model) {
        String username = (String) SecurityUtils.getSubject().getPrincipal();
        String taskId = buildService.startBuild(configId, buildMode, username);
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
    public ResponseEntity<Map<String, Object>> getLogContent(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();
        // Try to find the log file from the builds logs directory
        String logContent = buildService.readLogFile(taskId);
        if (logContent != null) {
            result.put("success", true);
            result.put("content", logContent);
        } else {
            result.put("success", false);
            result.put("content", "日志文件未找到");
        }
        return ResponseEntity.ok(result);
    }
}
