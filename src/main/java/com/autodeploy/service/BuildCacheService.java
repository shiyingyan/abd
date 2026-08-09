package com.autodeploy.service;

import com.autodeploy.model.BuildCacheEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Persists the last successful build state per project config, so subsequent builds can skip the
 * compile step when source code hasn't changed and artifacts still exist on disk.
 */
@Service
public class BuildCacheService {

  private static final Logger log = LoggerFactory.getLogger(BuildCacheService.class);
  private static final String CACHE_FILE = "build-cache.json";

  @Value("${autodeploy.builds-dir}")
  private String buildsDir;

  @Autowired private ObjectMapper objectMapper;

  private final Map<Long, BuildCacheEntry> cache = new ConcurrentHashMap<>();
  private volatile boolean loaded = false;

  /** Get the cached build state for a project config. Returns null if none. */
  public BuildCacheEntry get(Long configId) {
    ensureLoaded();
    return cache.get(configId);
  }

  /** Persist a new cache entry after a successful build. */
  public void put(BuildCacheEntry entry) {
    if (entry == null || entry.getConfigId() == null) return;
    ensureLoaded();
    cache.put(entry.getConfigId(), entry);
    persist();
  }

  /** Remove the cache entry for a project config (e.g. when config changes). */
  public void evict(Long configId) {
    if (configId == null) return;
    ensureLoaded();
    if (cache.remove(configId) != null) {
      persist();
    }
  }

  /**
   * Decide whether a build can be skipped. Requires: same git HEAD, same module set, same
   * build-related configuration, and the work directory still exists on disk.
   */
  public boolean shouldSkipBuild(
      BuildCacheEntry entry,
      String currentGitHash,
      List<String> currentModules,
      String currentBuildWorkDir,
      String currentBuildCommand,
      String currentLanguageVersion,
      String currentDeploySourcePath,
      String currentBuildMode,
      File workDir) {
    if (entry == null || currentGitHash == null) return false;
    if (!currentGitHash.equals(entry.getGitHash())) return false;
    if (!nullSafeEquals(currentBuildWorkDir, entry.getBuildWorkDir())) return false;
    if (!nullSafeEquals(currentBuildCommand, entry.getBuildCommand())) return false;
    if (!nullSafeEquals(currentLanguageVersion, entry.getLanguageVersion())) return false;
    if (!nullSafeEquals(currentDeploySourcePath, entry.getDeploySourcePath())) return false;
    if (!nullSafeEquals(currentBuildMode, entry.getBuildMode())) return false;
    if (!listsEqual(currentModules, entry.getModulePaths())) return false;
    if (workDir == null || !workDir.isDirectory()) return false;
    return true;
  }

  private static boolean nullSafeEquals(String a, String b) {
    String x = a == null ? null : (a.trim().isEmpty() ? null : a.trim());
    String y = b == null ? null : (b.trim().isEmpty() ? null : b.trim());
    if (x == null && y == null) return true;
    if (x == null || y == null) return false;
    return x.equals(y);
  }

  private static boolean listsEqual(List<String> a, List<String> b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      String x = a.get(i);
      String y = b.get(i);
      if (x == null && y == null) continue;
      if (x == null || y == null) return false;
      if (!x.equals(y)) return false;
    }
    return true;
  }

  private void ensureLoaded() {
    if (loaded) return;
    synchronized (this) {
      if (loaded) return;
      File file = cacheFile();
      if (file.isFile()) {
        try {
          Map<Long, BuildCacheEntry> data =
              objectMapper.readValue(file, new TypeReference<Map<Long, BuildCacheEntry>>() {});
          if (data != null) cache.putAll(data);
        } catch (Exception e) {
          log.warn(
              "Failed to load build cache from {}: {}", file.getAbsolutePath(), e.getMessage());
        }
      }
      loaded = true;
    }
  }

  private void persist() {
    File file = cacheFile();
    try {
      Files.createDirectories(file.toPath().getParent());
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, new HashMap<>(cache));
    } catch (Exception e) {
      log.warn("Failed to persist build cache to {}: {}", file.getAbsolutePath(), e.getMessage());
    }
  }

  private File cacheFile() {
    return Paths.get(buildsDir, CACHE_FILE).toFile();
  }
}
