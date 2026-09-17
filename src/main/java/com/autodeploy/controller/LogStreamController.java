package com.autodeploy.controller;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.service.ConfigService;
import com.autodeploy.service.LogStreamService;
import com.autodeploy.service.ServerInfoService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class LogStreamController {

  private static final Logger log = LoggerFactory.getLogger(LogStreamController.class);

  @Autowired private LogStreamService logStreamService;
  @Autowired private ConfigService configService;
  @Autowired private ServerInfoService serverInfoService;

  /** Log viewer page: shows config info and server selector. */
  @GetMapping("/log/{configId}")
  public String logPage(@PathVariable Long configId, Model model) {
    ProjectConfig config = configService.getById(configId);
    if (config == null) {
      return "redirect:/config";
    }

    // Get associated servers for this project, grouped by environment
    List<ProjectEnvServer> associations = serverInfoService.listProjectAssociations(configId);

    boolean hasLogConfig = !"NODE".equals(config.getLanguageType());

    model.addAttribute("config", config);
    model.addAttribute("associations", associations);
    model.addAttribute("hasLogConfig", hasLogConfig);
    return "log/view";
  }

  /** SSE endpoint for real-time log streaming. */
  @GetMapping("/api/log/sse/{configId}/{serverId}")
  @ResponseBody
  public SseEmitter streamLog(@PathVariable Long configId, @PathVariable Long serverId) {
    try {
      return logStreamService.subscribe(configId, serverId);
    } catch (LogStreamService.LogStreamException e) {
      SseEmitter emitter = new SseEmitter();
      try {
        emitter.send("ERROR: " + e.getMessage());
        emitter.complete();
      } catch (Exception ex) {
        emitter.completeWithError(ex);
      }
      return emitter;
    }
  }
}
