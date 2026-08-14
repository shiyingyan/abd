package com.autodeploy.util;

import com.autodeploy.model.ProjectConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Utility class for resolving build artifacts within a module directory. Supports both
 * auto-detection by language type and regex-based matching.
 */
public class ArtifactResolver {

  private static final int MAX_DEPTH = 8;
  private static final int MAX_FILES_SCANNED = 5000;
  private static final int MAX_MATCHES_PER_MODULE = 50;

  private ArtifactResolver() {}

  /**
   * Resolve artifacts for a module directory. If deploySourcePath is empty, auto-detect by language
   * type. If deploySourcePath is set, use it as regex to match file paths.
   */
  public static List<File> resolve(File moduleDir, ProjectConfig config, Consumer<String> log) {
    List<File> artifacts = new ArrayList<>();

    String sourcePath = config.getDeploySourcePath();
    if (sourcePath != null && !sourcePath.trim().isEmpty()) {
      // Regex matching mode
      artifacts = resolveByRegex(moduleDir, sourcePath.trim(), log);
    } else {
      // Auto-detect mode
      artifacts = resolveByAutoDetect(moduleDir, config, log);
    }

    return artifacts;
  }

  /**
   * Resolve artifacts using regex pattern matching. The pattern is matched against the relative
   * path of each file within the module directory.
   */
  private static List<File> resolveByRegex(File moduleDir, String regex, Consumer<String> log) {
    List<File> matches = new ArrayList<>();
    Pattern pattern;
    try {
      pattern = Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      log.accept("构建产物路径正则表达式无效: " + regex + " - " + e.getDescription());
      return matches;
    }

    try (Stream<Path> walk =
        Files.walk(moduleDir.toPath(), MAX_DEPTH, FileVisitOption.FOLLOW_LINKS)) {
      int[] fileCount = {0};
      walk.filter(Files::isRegularFile)
          .filter(path -> !shouldPrune(path))
          .forEach(
              path -> {
                if (fileCount[0] >= MAX_FILES_SCANNED || matches.size() >= MAX_MATCHES_PER_MODULE) {
                  return;
                }
                fileCount[0]++;
                String relPath = moduleDir.toPath().relativize(path).toString().replace('\\', '/');
                Matcher matcher = pattern.matcher(relPath);
                if (matcher.find()) {
                  matches.add(path.toFile());
                }
              });
    } catch (IOException e) {
      log.accept("扫描模块目录失败: " + moduleDir.getAbsolutePath() + " - " + e.getMessage());
    }

    if (matches.isEmpty()) {
      log.accept("模块 [" + moduleDir.getName() + "] 未匹配到产物 (正则: " + regex + ")");
    } else {
      log.accept("模块 [" + moduleDir.getName() + "] 匹配到 " + matches.size() + " 个产物");
    }
    return matches;
  }

  /** Check if a path should be pruned (skipped) during scanning. */
  private static boolean shouldPrune(Path path) {
    String pathStr = path.toString().replace('\\', '/');
    return pathStr.contains("/node_modules/")
        || pathStr.contains("/.git/")
        || pathStr.contains("/.idea/")
        || pathStr.contains("/target/classes/")
        || pathStr.contains("/target/generated-sources/")
        || pathStr.contains("/target/test-classes/")
        || pathStr.contains("/build/classes/")
        || pathStr.contains("/build/generated/");
  }

  /**
   * Auto-detect artifacts based on language type. This is the existing logic extracted from
   * DeployService.resolveArtifactSource.
   */
  private static List<File> resolveByAutoDetect(
      File moduleDir, ProjectConfig config, Consumer<String> log) {
    List<File> artifacts = new ArrayList<>();
    String langType = config.getLanguageType();

    if (langType == null || langType.trim().isEmpty()) {
      artifacts.add(moduleDir);
      return artifacts;
    }

    switch (langType.toUpperCase()) {
      case "JAVA":
        resolveJavaArtifacts(moduleDir, artifacts, log);
        break;
      case "NODE":
        resolveNodeArtifacts(moduleDir, artifacts, log);
        break;
      case "GO":
        resolveGoArtifacts(moduleDir, artifacts, log);
        break;
      case "PYTHON":
      default:
        artifacts.add(moduleDir);
        break;
    }

    return artifacts;
  }

  private static void resolveJavaArtifacts(
      File moduleDir, List<File> artifacts, Consumer<String> log) {
    File targetDir = new File(moduleDir, "target");
    if (targetDir.isDirectory()) {
      File[] jars =
          targetDir.listFiles(
              (dir, name) ->
                  name.endsWith(".jar")
                      && !name.contains("-sources")
                      && !name.contains("-javadoc")
                      && !name.endsWith(".original"));
      if (jars != null && jars.length > 0) {
        for (File jar : jars) {
          artifacts.add(jar);
        }
        return;
      }
    }
    // No JAR files found - don't fall back to directory upload
    log.accept("模块 [" + moduleDir.getName() + "] 未找到 JAR 文件");
  }

  private static void resolveNodeArtifacts(
      File moduleDir, List<File> artifacts, Consumer<String> log) {
    // Step 1: Try to read outputDir from framework config files (in priority order).
    // Each entry: [configFileName, outputDirKeyInConfig]
    String[][] configCandidates = {
      {"vue.config.js", "outputDir"},
      {"vite.config.js", "outDir"},
      {"vite.config.ts", "outDir"},
      {"next.config.js", "distDir"},
      {"next.config.mjs", "distDir"},
      {"nuxt.config.js", "buildDir"},
      {"nuxt.config.ts", "buildDir"},
      {"angular.json", "outputPath"},
    };
    for (String[] candidate : configCandidates) {
      File cfg = new File(moduleDir, candidate[0]);
      if (cfg.isFile()) {
        String outputDirName = parseJsConfigOutputDir(cfg, candidate[1]);
        if (outputDirName != null) {
          File outputDir = new File(moduleDir, outputDirName);
          if (outputDir.isDirectory() && isNonEmpty(outputDir)) {
            log.accept("从 " + candidate[0] + " 的 " + candidate[1] + " 解析到产物目录: " + outputDirName);
            artifacts.add(outputDir);
            return;
          }
          log.accept(
              candidate[0] + " 声明 " + candidate[1] + "='" + outputDirName + "'，但目录不存在或为空，继续自动推断");
        }
      }
    }

    // Step 2: Auto-detect common Node output directories
    String[] nodeOutputDirs = {"dist", "build", ".next", ".output", "out"};
    for (String dirName : nodeOutputDirs) {
      File outputDir = new File(moduleDir, dirName);
      if (outputDir.isDirectory() && isNonEmpty(outputDir)) {
        artifacts.add(outputDir);
        return;
      }
    }
  }

  private static void resolveGoArtifacts(
      File moduleDir, List<File> artifacts, Consumer<String> log) {
    File[] executables =
        moduleDir.listFiles(
            (dir, name) -> {
              if (name.contains(".")) return false;
              File f = new File(dir, name);
              return f.isFile() && f.canExecute();
            });
    if (executables != null && executables.length > 0) {
      artifacts.add(executables[0]);
    } else {
      // No executable found - don't fall back to directory upload
      log.accept("模块 [" + moduleDir.getName() + "] 未找到可执行文件");
    }
  }

  /**
   * Parse a JS/TS config file to extract a specific output-dir key's value. Supports common
   * patterns:
   *
   * <ul>
   *   <li>{@code outputDir: 'dist'} (Vue CLI)
   *   <li>{@code outDir: "../dist"} (Vite)
   *   <li>{@code distDir: 'build'} (Next.js, usually inside {@code build: { distDir: ... }})
   *   <li>{@code buildDir: '.nuxt'} (Nuxt)
   *   <li>{@code "outputPath": "dist"} (angular.json style, JSON)
   * </ul>
   *
   * The key is quoted in the regex so it can be matched regardless of whether the source uses
   * quotes around the key or not.
   */
  private static String parseJsConfigOutputDir(File cfgFile, String key) {
    try {
      String content = new String(Files.readAllBytes(cfgFile.toPath()), "UTF-8");
      // Match  key\s*:\s*['"]value['"]  or  "key"\s*:\s*"value"
      String quotedKey = Pattern.quote(key);
      Matcher matcher =
          Pattern.compile(
                  "(?:[\"']?" + quotedKey + "[\"']?\\s*:\\s*[\"'])([^\"']+)[\"']",
                  Pattern.CASE_INSENSITIVE)
              .matcher(content);
      if (matcher.find()) {
        String value = matcher.group(1).trim();
        // Strip any leading "./" prefix to normalise
        if (value.startsWith("./")) {
          value = value.substring(2);
        }
        if (!value.isEmpty()) {
          return value;
        }
      }
    } catch (Exception e) {
      // Ignore parse errors — caller will fall back to auto-detect
    }
    return null;
  }

  /** @deprecated kept for backward compatibility; delegates to {@link #parseJsConfigOutputDir}. */
  @Deprecated
  private static String parseVueConfigOutputDir(File vueConfig) {
    return parseJsConfigOutputDir(vueConfig, "outputDir");
  }

  private static boolean isNonEmpty(File dir) {
    String[] files = dir.list();
    return files != null && files.length > 0;
  }

  /**
   * Match relative paths against a regex pattern. Used for REMOTE mode where we have a list of
   * paths from find command.
   */
  public static List<String> matchRelativePaths(List<String> relativePaths, String regex) {
    List<String> matches = new ArrayList<>();
    Pattern pattern;
    try {
      pattern = Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      return matches;
    }

    for (String relPath : relativePaths) {
      if (matches.size() >= MAX_MATCHES_PER_MODULE) {
        break;
      }
      Matcher matcher = pattern.matcher(relPath.replace('\\', '/'));
      if (matcher.find()) {
        matches.add(relPath);
      }
    }
    return matches;
  }
}
