package com.autodeploy.controller;

import com.autodeploy.model.LanguageType;
import com.autodeploy.service.LanguageRuntimeService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class LanguageRuntimeController {

  @Autowired private LanguageRuntimeService runtimeService;

  @GetMapping("/runtime")
  public String runtimePage(Model model) {
    model.addAttribute("languages", LanguageType.values());
    return "runtime/index";
  }

  @GetMapping("/api/runtime/installed")
  @ResponseBody
  public java.util.Map<String, Object> checkInstalled(
      @RequestParam String language, @RequestParam String version) {
    LanguageType lang = LanguageType.fromString(language);
    java.util.Map<String, Object> result = new java.util.HashMap<>();
    if (lang == null) {
      result.put("installed", false);
      result.put("error", "未知语言类型");
      return result;
    }
    boolean installed = runtimeService.isVersionInstalled(lang, version);
    result.put("installed", installed);
    return result;
  }

  @GetMapping("/api/runtime/versions")
  @ResponseBody
  public java.util.List<String> listInstalledVersions(@RequestParam String language) {
    LanguageType lang = LanguageType.fromString(language);
    if (lang == null) {
      return java.util.Collections.emptyList();
    }
    return runtimeService.listInstalledVersions(lang);
  }

  @GetMapping("/api/runtime/install")
  public SseEmitter installVersion(@RequestParam String language, @RequestParam String version) {
    SseEmitter emitter = new SseEmitter(300000L);
    LanguageType lang = LanguageType.fromString(language);
    if (lang == null) {
      try {
        emitter.send("未知语言类型");
        emitter.complete();
      } catch (Exception e) {
        emitter.completeWithError(e);
      }
      return emitter;
    }

    new Thread(
            () -> {
              try {
                Process process = runtimeService.installVersion(lang, version);
                try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    emitter.send(line);
                  }
                }
                int exitCode = process.waitFor();
                emitter.send(exitCode == 0 ? "安装成功" : "安装失败，退出码: " + exitCode);
                emitter.complete();
              } catch (Exception e) {
                try {
                  emitter.send("安装错误: " + e.getMessage());
                  emitter.complete();
                } catch (Exception ex) {
                  emitter.completeWithError(e);
                }
              }
            })
        .start();

    return emitter;
  }
}
