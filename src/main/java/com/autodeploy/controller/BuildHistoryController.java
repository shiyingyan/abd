package com.autodeploy.controller;

import com.autodeploy.model.BuildRecord;
import com.autodeploy.service.BuildHistoryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class BuildHistoryController {

  @Autowired private BuildHistoryService historyService;

  @GetMapping("/history")
  public String historyPage(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd")
          LocalDateTime dateFrom,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDateTime dateTo,
      Model model) {
    Page<BuildRecord> records =
        historyService.queryRecords(page, 15, projectName, status, dateFrom, dateTo);
    model.addAttribute("records", records.getRecords());
    model.addAttribute("currentPage", page);
    model.addAttribute("totalPages", records.getPages());
    model.addAttribute("total", records.getTotal());
    model.addAttribute("projectName", projectName);
    model.addAttribute("status", status);
    model.addAttribute("dateFrom", dateFrom);
    model.addAttribute("dateTo", dateTo);
    return "history/index";
  }

  @GetMapping("/history/detail/{id}")
  @ResponseBody
  public ResponseEntity<BuildRecord> getDetail(@PathVariable Long id) {
    BuildRecord record = historyService.getById(id);
    if (record == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(record);
  }

  @GetMapping("/history/log/{id}")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> getLog(
      @PathVariable Long id, @RequestParam(defaultValue = "500") int tailLines) {
    Map<String, Object> result = historyService.getLogContentTail(id, tailLines);
    return ResponseEntity.ok(result);
  }
}
