package com.autodeploy.service;

import com.autodeploy.config.BuildThreadPoolManager;
import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectModule;
import com.autodeploy.repository.ConfigRepository;
import com.autodeploy.repository.ProjectModuleRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Service
public class ModuleScanService {

  private static final Logger log = LoggerFactory.getLogger(ModuleScanService.class);
  private static final int MAX_MODULE_DEPTH = 10;
  private static final int MAX_MODULES = 200;

  @Autowired private ProjectModuleRepository moduleRepository;
  @Autowired private ConfigRepository configRepository;
  @Autowired private GitService gitService;
  @Autowired private BuildThreadPoolManager poolManager;

  @Value("${autodeploy.work-dir}")
  private String workDir;

  private final Map<Long, Boolean> scanning = new ConcurrentHashMap<>();

  /**
   * Start module scan for a project. Returns null on success-started, or error message. Scan runs
   * asynchronously in the thread pool.
   */
  public String startScan(Long projectId) {
    ProjectConfig config = configRepository.selectById(projectId);
    if (config == null) {
      return "项目配置不存在";
    }
    if ("NODE".equals(config.getLanguageType())) {
      return "NODE 项目无需模块扫描";
    }
    if (scanning.putIfAbsent(projectId, true) != null) {
      return "扫描进行中，请稍候";
    }
    poolManager.submit(() -> doScan(projectId));
    return null;
  }

  private void doScan(Long projectId) {
    ProjectConfig config = configRepository.selectById(projectId);
    if (config == null) {
      scanning.remove(projectId);
      return;
    }

    try {
      File scanDir = resolveScanDirectory(config);
      File baseDir = resolveBaseDirectory(config, scanDir);

      if (!baseDir.exists()) {
        updateScanResult(projectId, "构建工作目录不存在: " + baseDir.getAbsolutePath());
        return;
      }

      List<ProjectModule> modules;
      String langType = config.getLanguageType();
      if ("JAVA".equals(langType)) {
        modules = scanMavenModules(baseDir, projectId);
      } else if ("GO".equals(langType)) {
        modules = scanGoModules(baseDir, projectId);
      } else {
        // PYTHON or other: single root module
        modules = new ArrayList<>();
        modules.add(createModule(projectId, ".", baseDir.getName(), ".", null));
      }

      replaceModules(projectId, modules);
      updateScanResult(projectId, "扫描到 " + modules.size() + " 个模块");
      log.info("Module scan completed for project {}: {} modules", projectId, modules.size());

    } catch (Exception e) {
      log.error("Module scan failed for project {}", projectId, e);
      updateScanResult(projectId, "扫描失败: " + e.getMessage());
    } finally {
      scanning.remove(projectId);
    }
  }

  /**
   * Resolve the directory to scan for modules. If projectDir is configured and has .git, use it
   * directly (no network). Otherwise, shallow clone into {workDir}/scan/{projectKey}.
   */
  private File resolveScanDirectory(ProjectConfig config) throws Exception {
    // If projectDir is configured and has .git, use it directly
    if (config.getProjectDir() != null && !config.getProjectDir().trim().isEmpty()) {
      File projectDir = new File(config.getProjectDir().trim());
      if (new File(projectDir, ".git").exists()) {
        log.info("Using existing project directory for scan: {}", projectDir.getAbsolutePath());
        return projectDir;
      }
    }

    // Otherwise, shallow clone into scan directory
    String scanPath = java.nio.file.Paths.get(workDir, "scan", config.getProjectKey()).toString();
    File scanDir = new File(scanPath);

    if (new File(scanDir, ".git").exists()) {
      // Already cloned, do a pull
      gitService.cloneOrPull(config, scanPath);
    } else {
      // Shallow clone
      if (scanDir.exists()) {
        deleteDirectory(scanDir);
      }
      gitService.shallowClone(config, scanPath);
    }
    return scanDir;
  }

  private File resolveBaseDirectory(ProjectConfig config, File repoDir) {
    if (config.getBuildWorkDir() != null && !config.getBuildWorkDir().trim().isEmpty()) {
      return new File(repoDir, config.getBuildWorkDir().trim());
    }
    return repoDir;
  }

  /** Scan Maven modules by recursively parsing pom.xml files. */
  private List<ProjectModule> scanMavenModules(File baseDir, Long projectId) {
    List<ProjectModule> modules = new ArrayList<>();
    File rootPom = new File(baseDir, "pom.xml");

    if (!rootPom.exists()) {
      // No pom.xml, treat as single module
      modules.add(createModule(projectId, ".", baseDir.getName(), ".", null));
      return modules;
    }

    // BFS scan of Maven modules
    scanMavenRecursive(baseDir, baseDir, projectId, modules, null, 0);
    return modules;
  }

  private void scanMavenRecursive(
      File pomDir,
      File baseDir,
      Long projectId,
      List<ProjectModule> modules,
      String parentKey,
      int depth) {
    if (depth > MAX_MODULE_DEPTH || modules.size() >= MAX_MODULES) {
      return;
    }

    File pomFile = new File(pomDir, "pom.xml");
    if (!pomFile.exists()) {
      return;
    }

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false); // Avoid namespace issues with Maven POMs
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(pomFile);
      doc.getDocumentElement().normalize();

      String artifactId = getDirectChildText(doc.getDocumentElement(), "artifactId");
      if (artifactId == null || artifactId.isEmpty()) {
        artifactId = pomDir.getName();
      }

      String moduleKey = relativize(baseDir, pomDir);
      String moduleName = artifactId;
      modules.add(createModule(projectId, moduleKey, moduleName, moduleKey, parentKey));

      // Scan child modules
      NodeList moduleNodes = doc.getElementsByTagName("module");
      for (int i = 0; i < moduleNodes.getLength(); i++) {
        String moduleName2 = moduleNodes.item(i).getTextContent().trim();
        if (moduleName2.isEmpty()) continue;

        File childDir = new File(pomDir, moduleName2);
        File childPom = new File(childDir, "pom.xml");
        if (childPom.exists()) {
          scanMavenRecursive(childDir, baseDir, projectId, modules, moduleKey, depth + 1);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to parse pom.xml at {}: {}", pomFile.getAbsolutePath(), e.getMessage());
    }
  }

  /** Scan Go modules by finding go.mod files. */
  private List<ProjectModule> scanGoModules(File baseDir, Long projectId) {
    List<ProjectModule> modules = new ArrayList<>();
    scanGoRecursive(baseDir, baseDir, projectId, modules, 0);

    if (modules.isEmpty()) {
      modules.add(createModule(projectId, ".", baseDir.getName(), ".", null));
    }
    return modules;
  }

  private void scanGoRecursive(
      File dir, File baseDir, Long projectId, List<ProjectModule> modules, int depth) {
    if (depth > 5 || modules.size() >= MAX_MODULES) {
      return;
    }

    String dirName = dir.getName();
    if (dirName.equals("vendor") || dirName.startsWith(".")) {
      return;
    }

    File goMod = new File(dir, "go.mod");
    if (goMod.exists()) {
      String moduleKey = relativize(baseDir, dir);
      modules.add(createModule(projectId, moduleKey, dirName, moduleKey, null));
      return; // Don't recurse into found module
    }

    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        if (child.isDirectory()) {
          scanGoRecursive(child, baseDir, projectId, modules, depth + 1);
        }
      }
    }
  }

  private ProjectModule createModule(
      Long projectId, String moduleKey, String moduleName, String modulePath, String parentKey) {
    ProjectModule m = new ProjectModule();
    m.setProjectId(projectId);
    m.setModuleKey(moduleKey);
    m.setModuleName(moduleName);
    m.setModulePath(modulePath);
    m.setParentModuleKey(parentKey);
    m.setScannedAt(LocalDateTime.now());
    return m;
  }

  private String relativize(File baseDir, File target) {
    if (baseDir.equals(target)) {
      return ".";
    }
    String basePath = baseDir.getAbsolutePath().replace('\\', '/');
    String targetPath = target.getAbsolutePath().replace('\\', '/');
    if (targetPath.startsWith(basePath)) {
      String rel = targetPath.substring(basePath.length());
      if (rel.startsWith("/")) {
        rel = rel.substring(1);
      }
      return rel.isEmpty() ? "." : rel;
    }
    return target.getName();
  }

  private String getDirectChildText(Element parent, String tagName) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element) {
        Element child = (Element) children.item(i);
        if (child.getTagName().equals(tagName)) {
          return child.getTextContent().trim();
        }
      }
    }
    return null;
  }

  @Transactional
  public void replaceModules(Long projectId, List<ProjectModule> modules) {
    moduleRepository.delete(new QueryWrapper<ProjectModule>().eq("project_id", projectId));
    for (ProjectModule m : modules) {
      moduleRepository.insert(m);
    }
  }

  private void updateScanResult(Long projectId, String message) {
    configRepository.update(
        null,
        new UpdateWrapper<ProjectConfig>()
            .eq("id", projectId)
            .set("last_module_scan_at", LocalDateTime.now())
            .set("last_module_scan_msg", message));
  }

  /** List all modules for a project, ordered by module_path. */
  public List<ProjectModule> listModules(Long projectId) {
    return moduleRepository.selectList(
        new QueryWrapper<ProjectModule>().eq("project_id", projectId).orderByDesc("id"));
  }

  /** Get scan status for a project. */
  public Map<String, Object> getScanStatus(Long projectId) {
    ProjectConfig config = configRepository.selectById(projectId);
    // HashMap (not ConcurrentHashMap) because scan status values may be null for never-scanned
    // projects, and this map is only serialized to JSON.
    Map<String, Object> result = new HashMap<>();
    result.put("scanning", scanning.containsKey(projectId));
    if (config != null) {
      result.put("lastScanAt", config.getLastModuleScanAt());
      result.put("lastScanMsg", config.getLastModuleScanMsg());
    }
    return result;
  }

  public boolean isScanning(Long projectId) {
    return scanning.containsKey(projectId);
  }

  private void deleteDirectory(File dir) {
    if (!dir.exists()) return;
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.isDirectory()) {
          deleteDirectory(f);
        } else {
          f.delete();
        }
      }
    }
    dir.delete();
  }
}
