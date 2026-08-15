package com.autodeploy.controller;

import com.autodeploy.model.BuildRecord;
import com.autodeploy.service.BuildHistoryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDate;
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

  private static final int PAGE_SIZE = 15;

  @GetMapping("/history")
  public String historyPage(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dateTo,
      @RequestParam(required = false) Long recordId,
      Model model) {
    LocalDateTime from = dateFrom != null ? dateFrom.atStartOfDay() : null;
    LocalDateTime to = dateTo != null ? dateTo.plusDays(1).atStartOfDay().minusNanos(1) : null;

    if (recordId != null) {
      int targetPage =
          historyService.getPageForRecord(recordId, projectName, status, from, to, PAGE_SIZE);
      if (targetPage != page) {
        StringBuilder redirect = new StringBuilder("redirect:/history?page=").append(targetPage);
        if (projectName != null) {
          try {
            redirect.append("&projectName=").append(URLEncoder.encode(projectName, "UTF-8"));
          } catch (UnsupportedEncodingException e) {
            redirect.append("&projectName=").append(projectName);
          }
        }
        if (status != null) redirect.append("&status=").append(status);
        if (dateFrom != null) redirect.append("&dateFrom=").append(dateFrom);
        if (dateTo != null) redirect.append("&dateTo=").append(dateTo);
        redirect.append("&recordId=").append(recordId);
        return redirect.toString();
      }
    }

    Page<BuildRecord> records =
        historyService.queryRecords(page, PAGE_SIZE, projectName, status, from, to);
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
