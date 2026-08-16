package com.autodeploy.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scans common installation directories on Windows to detect multiple Java/Go versions. Results are
 * cached in memory and refreshed only on demand (manual trigger) or at startup.
 */
@Service
public class WindowsRuntimeScanner {

  private static final Logger log = LoggerFactory.getLogger(WindowsRuntimeScanner.class);

  private static final String[] JAVA_SCAN_ROOTS = {
    "C:\\Program Files\\Java",
    "C:\\Program Files (x86)\\Java",
    "C:\\Program Files\\Eclipse Adoptium",
    "C:\\Program Files\\AdoptOpenJDK",
    "C:\\Program Files\\Zulu",
    "C:\\Program Files\\Microsoft",
    "C:\\Program Files\\BellSoft",
    "C:\\Program Files\\Amazon Corretto",
    "C:\\Program Files\\GraalVM",
    "C:\\Program Files\\sapmachine",
  };

  private static final String[] GO_SCAN_ROOTS = {
    "C:\\Program Files\\Go", "C:\\Go", "C:\\Program Files (x86)\\Go",
  };

  private static final Object[][] JAVA_DIR_PATTERNS = {
    {Pattern.compile("jdk[- ]?(\\d+(?:\\.\\d+)*(?:[+\\-]\\w+)?)"), 1},
    {Pattern.compile("jdk(\\d+(?:\\.\\d+)+(?:_\\d+)?)"), 1},
    {Pattern.compile("zulu[- ]?(\\d+(?:\\.\\d+)*(?:[+\\-]\\w+)?)"), 1},
    {Pattern.compile("graalvm[- ](?:jdk[- ]?)?(\\d+(?:\\.\\d+)*)"), 1},
    {Pattern.compile("corretto[- ]?(\\d+(?:\\.\\d+)*(?:[+\\-]\\w+)?)"), 1},
    {Pattern.compile("sapmachine[- ]?(\\d+(?:\\.\\d+)*)"), 1},
    {Pattern.compile("liberica[- ]?jdk[- ]?(\\d+(?:\\.\\d+)*)"), 1},
    {Pattern.compile("microsoft[- ]?jdk[- ]?(\\d+(?:\\.\\d+)*)"), 1},
    {Pattern.compile("temurin[- ]?(\\d+(?:\\.\\d+)*)"), 1},
    {Pattern.compile("jre[- ]?(\\d+(?:\\.\\d+)+(?:_\\d+)?)"), 1},
    {Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?(?:[+\\-]\\w+)?)$"), 1},
  };

  private static final Object[][] GO_DIR_PATTERNS = {
    {Pattern.compile("go(\\d+\\.\\d+(?:\\.\\d+)?)"), 1},
    {Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)$"), 1},
  };

  private volatile Map<String, String> javaCache = Collections.emptyMap();
  private volatile Map<String, String> goCache = Collections.emptyMap();

  @PostConstruct
  public void init() {
    if (!isWindows()) {
      return;
    }
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "runtime-scanner-init");
              t.setDaemon(true);
              return t;
            });
    executor.submit(
        () -> {
          try {
            refreshCache();
          } catch (Exception e) {
            log.warn("Initial runtime scan failed: {}", e.getMessage());
          }
        });
    executor.shutdown();
  }

  /** Re-scan all installation directories and update the cache. */
  public void refreshCache() {
    if (!isWindows()) {
      return;
    }
    log.info("Starting runtime scan...");
    long start = System.currentTimeMillis();
    javaCache = doScanJava();
    goCache = doScanGo();
    long elapsed = System.currentTimeMillis() - start;
    log.info(
        "Runtime scan completed in {}ms: found {} Java version(s), {} Go version(s)",
        elapsed,
        javaCache.size(),
        goCache.size());
    if (log.isDebugEnabled()) {
      javaCache.forEach((v, p) -> log.debug("  Java {} -> {}", v, p));
      goCache.forEach((v, p) -> log.debug("  Go {} -> {}", v, p));
    }
  }

  /** Returns cached Java installations (version -> path). Does not trigger a scan. */
  public Map<String, String> getJavaInstallations() {
    return javaCache;
  }

  /** Returns cached Go installations (version -> path). Does not trigger a scan. */
  public Map<String, String> getGoInstallations() {
    return goCache;
  }

  private Map<String, String> doScanJava() {
    Map<String, String> result = new LinkedHashMap<>();

    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null && !javaHome.isEmpty()) {
      File homeDir = new File(javaHome);
      if (homeDir.isDirectory() && hasBinExecutable(homeDir, "java")) {
        String version = extractVersionFromDir(homeDir, JAVA_DIR_PATTERNS);
        if (version == null) {
          version = runJavaVersionForPath(homeDir);
        }
        if (version != null) {
          result.putIfAbsent(version, homeDir.getAbsolutePath());
        }
      }
    }

    String userProfile = System.getProperty("user.home", "");
    if (!userProfile.isEmpty()) {
      File jdksDir = new File(userProfile, ".jdks");
      if (jdksDir.isDirectory()) {
        scanSubdirectories(jdksDir, JAVA_DIR_PATTERNS, "java", result);
      }
    }

    for (String root : JAVA_SCAN_ROOTS) {
      File rootDir = new File(root);
      if (rootDir.isDirectory()) {
        scanSubdirectories(rootDir, JAVA_DIR_PATTERNS, "java", result);
      }
    }

    return result;
  }

  private Map<String, String> doScanGo() {
    Map<String, String> result = new LinkedHashMap<>();

    String goRoot = System.getenv("GOROOT");
    if (goRoot != null && !goRoot.isEmpty()) {
      File rootDir = new File(goRoot);
      if (rootDir.isDirectory() && hasBinExecutable(rootDir, "go")) {
        String version = extractVersionFromDir(rootDir, GO_DIR_PATTERNS);
        if (version != null) {
          result.putIfAbsent(version, rootDir.getAbsolutePath());
        }
      }
    }

    for (String root : GO_SCAN_ROOTS) {
      File rootDir = new File(root);
      if (rootDir.isDirectory()) {
        if (hasBinExecutable(rootDir, "go")) {
          String version = extractVersionFromDir(rootDir, GO_DIR_PATTERNS);
          if (version != null) {
            result.putIfAbsent(version, rootDir.getAbsolutePath());
          }
        }
        scanSubdirectories(rootDir, GO_DIR_PATTERNS, "go", result);
      }
    }

    return result;
  }

  private void scanSubdirectories(
      File parentDir, Object[][] patterns, String exeName, Map<String, String> result) {
    File[] children = parentDir.listFiles(File::isDirectory);
    if (children == null) return;
    for (File child : children) {
      if (hasBinExecutable(child, exeName)) {
        String version = extractVersionFromDir(child, patterns);
        if (version != null) {
          result.putIfAbsent(version, child.getAbsolutePath());
        }
      }
    }
  }

  private boolean hasBinExecutable(File installDir, String exeName) {
    return new File(installDir, "bin\\" + exeName + ".exe").exists();
  }

  private String extractVersionFromDir(File dir, Object[][] patterns) {
    String name = dir.getName();
    for (Object[] entry : patterns) {
      Pattern p = (Pattern) entry[0];
      int group = (int) entry[1];
      Matcher m = p.matcher(name);
      if (m.find()) {
        return m.group(group);
      }
    }
    return null;
  }

  private String runJavaVersionForPath(File javaDir) {
    try {
      ProcessBuilder pb =
          new ProcessBuilder(new File(javaDir, "bin\\java.exe").getAbsolutePath(), "-version");
      pb.redirectErrorStream(true);
      Process proc = pb.start();
      Pattern vp = Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?(?:[+\\-]\\w+)?)");
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
        String line;
        while ((line = r.readLine()) != null) {
          Matcher m = vp.matcher(line);
          if (m.find()) {
            proc.waitFor();
            return m.group(1);
          }
        }
      }
      proc.waitFor();
    } catch (Exception e) {
      log.debug("Failed to run java -version for {}: {}", javaDir, e.getMessage());
    }
    return null;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
