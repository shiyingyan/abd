package com.autodeploy.service;

import java.io.File;
import java.io.IOException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A handle to a local git repository that centralises every JGit cross-platform workaround.
 *
 * <p>JGit does not always behave the same as command-line git on real-world file systems. The known
 * issues addressed here are:
 *
 * <ul>
 *   <li><b>macOS APFS — core.fileMode.</b> APFS does not reliably track Unix permission bits.
 *       Command-line git defaults to {@code core.fileMode=false} on macOS, but JGit does not. Repos
 *       cloned by JGit may therefore report chmod-only changes as "uncommitted changes". {@link
 *       #configureGitFromSystemSettings} forces {@code core.fileMode=false} on macOS when no
 *       explicit value was found in the user's external git config.
 *   <li><b>Case-insensitive file systems — Status false positives.</b> On macOS APFS
 *       (case-insensitive) and Windows NTFS, the git index may store a path like {@code
 *       BG/gdt-service/Foo.java} while the file system has {@code BG/GDT-Service/Foo.java}. JGit
 *       does not honour {@code core.ignorecase} when comparing the index against the working tree,
 *       so it reports the file as "missing" and {@link Status#hasUncommittedChanges()} returns
 *       {@code true} even though command-line git considers the working tree clean. {@link
 *       #hasActualUncommittedChanges} filters out such false positives.
 *   <li><b>Line endings — core.autocrlf.</b> On Windows, command-line git typically sets {@code
 *       core.autocrlf=true}. JGit repos cloned without this setting may report every file as
 *       modified. {@link #configureGitFromSystemSettings} copies the user's system/user-level git
 *       config into the repo config so JGit matches command-line git.
 * </ul>
 *
 * <p><b>Usage rule:</b> All code that opens a local repository should go through {@link
 * #openRepo(File)} so that the workarounds above are applied automatically. In particular, never
 * call {@link Status#hasUncommittedChanges()} directly — use {@link #hasActualUncommittedChanges}
 * instead.
 */
final class RepoHandle implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RepoHandle.class);

  private final Git git;
  private final File repoDir;

  private RepoHandle(Git git, File repoDir) {
    this.git = git;
    this.repoDir = repoDir;
  }

  // ------------------------------------------------------------------ factory

  /**
   * Open a local repository. Applies {@link #configureGitFromSystemSettings} automatically so that
   * all downstream operations (status, pull, etc.) behave the same as command-line git.
   *
   * @return a {@code RepoHandle}, or {@code null} if the directory is not a git repository.
   */
  static RepoHandle openRepo(File repoDir) {
    if (repoDir == null || !repoDir.isDirectory() || !new File(repoDir, ".git").isDirectory()) {
      return null;
    }
    try {
      Git git = Git.open(repoDir);
      configureGitFromSystemSettings(git.getRepository());
      return new RepoHandle(git, repoDir);
    } catch (IOException e) {
      log.warn("Failed to open repository {}: {}", repoDir.getAbsolutePath(), e.getMessage());
      return null;
    }
  }

  // ---------------------------------------------------------------- accessors

  Git git() {
    return git;
  }

  Repository repository() {
    return git.getRepository();
  }

  File repoDir() {
    return repoDir;
  }

  /** Run a JGit {@link StatusCommand}. Prefer {@link #hasActualUncommittedChanges} for checks. */
  Status status() throws Exception {
    return git.status().call();
  }

  // ------------------------------------------ cross-platform status helpers

  /**
   * Check whether the working tree has <em>real</em> uncommitted changes, working around JGit bugs
   * on case-insensitive file systems.
   *
   * <p>This method must be used instead of {@link Status#hasUncommittedChanges()} everywhere. JGit
   * does not honour {@code core.ignorecase} when comparing the index against the working tree: when
   * the index stores {@code BG/gdt-service/Foo.java} but the file system has {@code
   * BG/GDT-Service/Foo.java}, JGit reports the file as "missing" and {@code
   * hasUncommittedChanges()} returns {@code true}, even though command-line git considers the
   * working tree clean.
   *
   * <p>The method:
   *
   * <ul>
   *   <li>Does NOT rely on {@link Status#hasUncommittedChanges()} — it checks the individual change
   *       sets directly.
   *   <li>Filters {@link Status#getMissing()} through {@link #existsCaseInsensitive} so that
   *       entries that exist on disk with different case are excluded.
   *   <li>Excludes {@link Status#getUntracked()} intentionally — build artifacts ({@code target/},
   *       {@code dist/}, etc.) persist in the repo directory and would cause false positives.
   * </ul>
   */
  boolean hasActualUncommittedChanges(Status status) {
    if (!status.getChanged().isEmpty()
        || !status.getAdded().isEmpty()
        || !status.getRemoved().isEmpty()
        || !status.getModified().isEmpty()
        || !status.getConflicting().isEmpty()) {
      return true;
    }
    // "missing" may contain false positives on case-insensitive file systems
    for (String path : status.getMissing()) {
      if (!existsCaseInsensitive(path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check whether a path exists on disk, tolerating case differences. On case-insensitive file
   * systems (macOS APFS, Windows NTFS) a file may exist under a name whose case differs from what
   * the git index records.
   */
  private boolean existsCaseInsensitive(String relativePath) {
    File file = new File(repoDir, relativePath);
    if (file.exists()) {
      return true;
    }
    File parent = file.getParentFile();
    if (parent == null || !parent.exists()) {
      return false;
    }
    String targetName = file.getName();
    File[] siblings = parent.listFiles();
    if (siblings == null) {
      return false;
    }
    for (File sibling : siblings) {
      if (sibling.getName().equalsIgnoreCase(targetName)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------- system git-config adaptation (macOS/Win)

  /**
   * Configure a JGit repository to respect the user's system git configuration.
   *
   * <p>JGit does not automatically read the system/user-level git config ({@code ~/.gitconfig},
   * etc.) the way command-line git does. This method copies the relevant {@code core.*} settings
   * into the repo's own config so that JGit detects changes the same way as command-line git.
   *
   * <p>Settings handled:
   *
   * <ul>
   *   <li>{@code core.autocrlf} — line-ending normalisation (important on Windows)
   *   <li>{@code core.fileMode} — file-permission tracking (important on macOS APFS)
   *   <li>{@code core.ignoreCase} — case-sensitivity (important on macOS/Windows)
   * </ul>
   *
   * <p>Settings already present in the repo's own config take precedence over inherited values. On
   * macOS, {@code core.fileMode} is forced to {@code false} when no explicit value was found in any
   * external config, matching command-line git's default behaviour.
   */
  private static void configureGitFromSystemSettings(org.eclipse.jgit.lib.Repository repo) {
    try {
      StoredConfig repoConfig = repo.getConfig();

      // Collect key-value pairs from external configs in priority order
      // (system config overrides user config)
      java.util.Map<String, String> externalSettings = new java.util.LinkedHashMap<>();

      // 1. Try user-level config first (lower priority, added first)
      loadGitConfigSetting(
          System.getProperty("user.home") + File.separator + ".gitconfig", externalSettings);

      // 2. Try system-level config (higher priority, overwrites user settings)
      String programData = System.getenv("PROGRAMDATA");
      if (programData != null && !programData.isEmpty()) {
        loadGitConfigSetting(
            programData + File.separator + "Git" + File.separator + "config", externalSettings);
      }
      String programFiles = System.getenv("PROGRAMFILES");
      if (programFiles != null && !programFiles.isEmpty()) {
        loadGitConfigSetting(
            programFiles
                + File.separator
                + "Git"
                + File.separator
                + "etc"
                + File.separator
                + "gitconfig",
            externalSettings);
      }

      // 3. Also try JGit SystemReader (may find configs we missed)
      try {
        org.eclipse.jgit.util.SystemReader sr = org.eclipse.jgit.util.SystemReader.getInstance();
        org.eclipse.jgit.lib.Config sysCfg = sr.openSystemConfig(null, null);
        if (sysCfg != null) {
          applySetting(externalSettings, sysCfg, "autocrlf");
          applySetting(externalSettings, sysCfg, "fileMode");
          applySetting(externalSettings, sysCfg, "ignoreCase");
        }
        org.eclipse.jgit.lib.Config userCfg = sr.openUserConfig(null, null);
        if (userCfg != null) {
          // User config has lower priority — only set if not already present from system
          if (!externalSettings.containsKey("autocrlf")) {
            applySetting(externalSettings, userCfg, "autocrlf");
          }
          if (!externalSettings.containsKey("fileMode")) {
            applySetting(externalSettings, userCfg, "fileMode");
          }
          if (!externalSettings.containsKey("ignoreCase")) {
            applySetting(externalSettings, userCfg, "ignoreCase");
          }
        }
      } catch (Exception e) {
        log.info("JGit SystemReader fallback: {}", e.getMessage());
      }

      if (externalSettings.isEmpty()) {
        log.warn("No external git config found — JGit may report false positives on Windows");
      }

      // Apply settings to repo config (only if not already set in repo's own config)
      int applied = 0;
      for (java.util.Map.Entry<String, String> entry : externalSettings.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        String existing = repoConfig.getString("core", null, key);
        if (existing == null) {
          repoConfig.setString("core", null, key, value);
          log.info("Applied core.{}={} from external git config", key, value);
          applied++;
        }
      }

      // macOS fix: command-line git sets core.fileMode=false by default because APFS
      // does not reliably track Unix permission bits. JGit does not replicate this
      // behaviour, so repos cloned by JGit on macOS may have core.fileMode=true,
      // causing chmod-only changes (e.g. 0755→0644) to appear as "uncommitted changes".
      // Force core.fileMode=false on macOS when no explicit fileMode was found in any
      // external config, and also override a repo-level true value.
      boolean isMacOs =
          System.getProperty("os.name", "").toLowerCase().contains("mac")
              || System.getProperty("os.name", "").toLowerCase().contains("darwin");
      if (isMacOs && !externalSettings.containsKey("filemode")) {
        String currentFileMode = repoConfig.getString("core", null, "filemode");
        if (currentFileMode == null || !"false".equalsIgnoreCase(currentFileMode)) {
          repoConfig.setString("core", null, "filemode", "false");
          log.info("macOS detected — forced core.fileMode=false to prevent false dirty detection");
          applied++;
        }
      }

      if (applied > 0) {
        repoConfig.save();
        log.info(
            "Saved {} git config setting(s) to {}", applied, repo.getDirectory().getAbsolutePath());
      } else {
        log.info("Repo config already has all external settings — no changes needed");
      }
    } catch (Exception e) {
      log.warn("Failed to apply system git config: {}", e.getMessage());
    }
  }

  /** Read core.autocrlf, core.fileMode, core.ignoreCase from a git config file (simple parser). */
  private static void loadGitConfigSetting(String filePath, java.util.Map<String, String> target) {
    File f = new File(filePath);
    if (!f.isFile()) {
      return;
    }
    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(f))) {
      boolean inCore = false;
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.startsWith("[")) {
          inCore = line.toLowerCase().contains("[core]");
          continue;
        }
        if (inCore && line.contains("=")) {
          int eq = line.indexOf('=');
          String key = line.substring(0, eq).trim().toLowerCase();
          String value = line.substring(eq + 1).trim();
          if ("autocrlf".equals(key) || "filemode".equals(key) || "ignorecase".equals(key)) {
            target.put(key, value);
            log.info("Read core.{}={} from {}", key, value, f.getAbsolutePath());
          }
        }
      }
    } catch (Exception e) {
      log.info("Failed to parse git config {}: {}", f.getAbsolutePath(), e.getMessage());
    }
  }

  /** Helper to extract a core setting from a JGit Config and put it into the target map. */
  private static void applySetting(
      java.util.Map<String, String> target, org.eclipse.jgit.lib.Config cfg, String key) {
    String value = cfg.getString("core", null, key);
    if (value != null) {
      target.put(key, value);
    }
  }

  // ------------------------------------------------------------ AutoCloseable

  @Override
  public void close() {
    git.close();
  }
}
