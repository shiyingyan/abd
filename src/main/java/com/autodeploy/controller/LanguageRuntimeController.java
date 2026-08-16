package com.autodeploy.controller;

import com.autodeploy.model.LanguageType;
import com.autodeploy.service.LanguageRuntimeService;
import com.autodeploy.service.WindowsRuntimeScanner;
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
  @Autowired private WindowsRuntimeScanner windowsScanner;

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

  @GetMapping("/api/runtime/versions-with-paths")
  @ResponseBody
  public java.util.Map<String, String> listInstalledVersionsWithPaths(
      @RequestParam String language) {
    LanguageType lang = LanguageType.fromString(language);
    if (lang == null) {
      return java.util.Collections.emptyMap();
    }
    return runtimeService.listInstalledVersionsWithPaths(lang);
  }

  @PostMapping("/api/runtime/scan")
  @ResponseBody
  public java.util.Map<String, Object> rescanRuntimes() {
    java.util.Map<String, Object> result = new java.util.HashMap<>();
    try {
      windowsScanner.refreshCache();
      result.put("success", true);
      result.put("java", windowsScanner.getJavaInstallations());
      result.put("go", windowsScanner.getGoInstallations());
    } catch (Exception e) {
      result.put("success", false);
      result.put("error", e.getMessage());
    }
    return result;
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

    boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
    if (isWindows && (lang == LanguageType.JAVA || lang == LanguageType.GO)) {
      try {
        String langName = lang == LanguageType.JAVA ? "Java (JDK)" : "Go";
        emitter.send("Windows 系统暂不支持自动安装 " + langName + "。");
        emitter.send("请手动下载并安装：");
        if (lang == LanguageType.JAVA) {
          emitter.send("  - Oracle JDK: https://www.oracle.com/java/technologies/downloads/");
          emitter.send("  - Eclipse Adoptium: https://adoptium.net/");
          emitter.send("  - Amazon Corretto: https://aws.amazon.com/corretto/");
        } else {
          emitter.send("  - Go 官方下载: https://go.dev/dl/");
        }
        emitter.send("安装完成后，请点击「重新扫描」按钮刷新版本列表。");
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
