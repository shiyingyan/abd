package com.autodeploy.controller;

import com.autodeploy.model.DeployEnvironment;
import com.autodeploy.service.DeployEnvironmentService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class DeployEnvironmentController {

  @Autowired private DeployEnvironmentService environmentService;

  @GetMapping("/environment")
  public String listPage(Model model) {
    model.addAttribute("environments", environmentService.listAll());
    model.addAttribute("env", new DeployEnvironment());
    return "environment/index";
  }

  @PostMapping("/environment/save")
  public String save(DeployEnvironment env) {
    String error = environmentService.save(env);
    if (error != null) {
      return "redirect:/environment?error=" + error;
    }
    return "redirect:/environment";
  }

  @GetMapping("/environment/delete/{id}")
  public String delete(@PathVariable Long id) {
    environmentService.delete(id);
    return "redirect:/environment";
  }

  // ===== API =====

  @GetMapping("/api/environments")
  @ResponseBody
  public ResponseEntity<List<DeployEnvironment>> listApi() {
    return ResponseEntity.ok(environmentService.listAll());
  }

  @GetMapping("/api/environments/{id}")
  @ResponseBody
  public ResponseEntity<DeployEnvironment> getApi(@PathVariable Long id) {
    DeployEnvironment env = environmentService.getById(id);
    if (env == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(env);
  }

  @PostMapping("/api/environments")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> saveApi(@RequestBody DeployEnvironment env) {
    Map<String, Object> result = new HashMap<>();
    String error = environmentService.save(env);
    if (error != null) {
      result.put("success", false);
      result.put("error", error);
      return ResponseEntity.badRequest().body(result);
    }
    result.put("success", true);
    return ResponseEntity.ok(result);
  }

  @DeleteMapping("/api/environments/{id}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> deleteApi(@PathVariable Long id) {
    environmentService.delete(id);
    Map<String, Object> result = new HashMap<>();
    result.put("success", true);
    return ResponseEntity.ok(result);
  }
}
