package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.util.EnvVarUtil;
import java.io.File;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GitService {

  private static final Logger log = LoggerFactory.getLogger(GitService.class);

  /**
   * Clone or pull a repository into the target directory.
   *
   * <p>Safety rules:
   *
   * <ul>
   *   <li>If the working tree has uncommitted local changes, refuse to pull (do NOT delete the
   *       repo) so the user's modifications are preserved.
   *   <li>If pull fails due to a merge conflict, abort the merge and surface the error; the repo is
   *       reset to the pre-pull HEAD and no code is lost.
   *   <li>If pull fails for any other reason (network, auth, etc.), keep the repo intact so the
   *       next attempt can resume.
   * </ul>
   */
  public File cloneOrPull(ProjectConfig config, String workDir) throws Exception {
    String repoUrl = config.getGitRepoUrl();
    String branch = config.getGitBranch() != null ? config.getGitBranch() : "main";
    File repoDir = new File(workDir);

    UsernamePasswordCredentialsProvider creds = resolveCredentials(config);

    boolean hasGitRepo = repoDir.exists() && new File(repoDir, ".git").exists();

    if (hasGitRepo) {
      log.info("Existing repository found at {}, attempting git pull", repoDir.getAbsolutePath());
      try (Git git = Git.open(repoDir)) {
        // Configure JGit to respect system git configuration (core.autocrlf, core.fileMode, etc.)
        // This ensures JGit detects changes the same way as command-line git.
        configureGitFromSystemSettings(git.getRepository());

        // Guard 1: refuse to pull if working tree has uncommitted changes to tracked files.
        // Untracked files (build artifacts) are intentionally excluded — they don't conflict
        // with git pull and would cause false positives in build environments.
        Status status = git.status().call();
        if (status.hasUncommittedChanges()) {
          String msg =
              "本地仓库有未提交的修改（" + status.getUncommittedChanges().size() + " 个已修改），请先提交或暂存后再触发构建";
          log.warn("Git pull refused: {}", msg);
          throw new IllegalStateException(msg);
        }

        try {
          PullCommand pull = git.pull();
          if (creds != null) {
            pull.setCredentialsProvider(creds);
          }
          pull.call();
        } catch (Exception e) {
          // Determine whether the failure left the repo in an in-progress merge state.
          // JGit's pull() throws a JGitInternalException wrapping the merge conflict —
          // we detect it by inspecting the repository state so the merge can be aborted
          // and the repo reset to a clean HEAD (no code is lost).
          boolean mergeInProgress = false;
          try {
            RepositoryState state = git.getRepository().getRepositoryState();
            mergeInProgress =
                state == RepositoryState.MERGING || state == RepositoryState.MERGING_RESOLVED;
          } catch (Exception ignore) {
            // ignore state-check errors
          }
          String msg = e.getMessage() != null ? e.getMessage() : "";
          boolean looksLikeConflict =
              msg.contains("Merge conflict")
                  || msg.contains("CONFLICTS")
                  || msg.contains("conflicting changes");

          if (mergeInProgress || looksLikeConflict) {
            log.warn(
                "Merge conflict on pull for {}: {}. Resetting to HEAD.",
                config.getProjectName(),
                msg);
            try {
              git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
            } catch (Exception ignore) {
              // ignore reset errors
            }
            throw new IllegalStateException(
                "Git pull 发生合并冲突，已自动将本地分支重置到最近一次提交。"
                    + "请手动合并目标分支 "
                    + branch
                    + " 并解决冲突后再构建。原始错误: "
                    + msg,
                e);
          }

          // Network / auth / other error — keep repo intact for next attempt
          log.warn(
              "Git pull failed for {}: {}. Repo kept intact for next attempt.",
              config.getProjectName(),
              msg);
          throw e;
        }

        String currentBranch = git.getRepository().getBranch();
        if (!branch.equals(currentBranch)) {
          log.info("Branch changed from {} to {}, switching", currentBranch, branch);
          git.checkout().setName(branch).setCreateBranch(false).call();
        }
        log.info("Git pull completed for {}", config.getProjectName());
      }
    } else {
      if (repoDir.exists()) {
        log.info(
            "Directory exists but no .git found at {}, will clone fresh",
            repoDir.getAbsolutePath());
        deleteDirectory(repoDir);
      }
      doClone(repoUrl, branch, repoDir, creds);
    }
    return repoDir;
  }

  private void doClone(
      String repoUrl, String branch, File repoDir, UsernamePasswordCredentialsProvider creds)
      throws Exception {
    log.info("Cloning {} (branch: {}) into {}", repoUrl, branch, repoDir.getAbsolutePath());
    CloneCommand clone =
        Git.cloneRepository().setURI(repoUrl).setDirectory(repoDir).setBranch(branch);
    if (creds != null) {
      clone.setCredentialsProvider(creds);
    }
    // Try-with-resources to close the Git instance and release native handles
    try (Git g = clone.call()) {
      // no-op: close releases handles
    }
    log.info("Git clone completed");
  }

  /**
   * Return the HEAD commit hash of a local git repository. Returns null if the directory is not a
   * git repository, has no commits, or any error occurs. If the working tree has uncommitted local
   * changes (staged, unstaged, or untracked files), the hash is suffixed with {@code -DIRTY} so
   * that build caches treat "edited but not committed" as a real code change.
   */
  public String getHeadHash(File repoDir) {
    if (repoDir == null || !repoDir.isDirectory() || !new File(repoDir, ".git").isDirectory()) {
      return null;
    }
    try (Git git = Git.open(repoDir)) {
      // Configure JGit to respect system git configuration
      configureGitFromSystemSettings(git.getRepository());

      org.eclipse.jgit.revwalk.RevCommit head = git.log().setMaxCount(1).call().iterator().next();
      String hash = head.getName();

      // Detect working-tree changes that haven't been committed yet.
      // Note: getUntracked() is intentionally excluded — build artifacts from previous
      // builds (target/, dist/, etc.) persist in the repo directory and would cause
      // false positives.
      Status status = git.status().call();
      boolean dirty =
          status.hasUncommittedChanges()
              || !status.getChanged().isEmpty()
              || !status.getAdded().isEmpty()
              || !status.getRemoved().isEmpty()
              || !status.getMissing().isEmpty()
              || !status.getModified().isEmpty()
              || !status.getConflicting().isEmpty();
      return dirty ? hash + "-DIRTY" : hash;
    } catch (Exception e) {
      log.warn("Failed to read HEAD hash from {}: {}", repoDir.getAbsolutePath(), e.getMessage());
      return null;
    }
  }

  /**
   * Check whether a local repository has uncommitted changes to tracked files. Returns false if the
   * directory doesn't exist or isn't a git repository.
   */
  public boolean hasUncommittedChanges(File repoDir) {
    if (repoDir == null || !repoDir.isDirectory() || !new File(repoDir, ".git").isDirectory()) {
      return false;
    }
    try (Git git = Git.open(repoDir)) {
      configureGitFromSystemSettings(git.getRepository());
      Status status = git.status().call();
      return status.hasUncommittedChanges();
    } catch (Exception e) {
      log.warn(
          "Failed to check uncommitted changes in {}: {}",
          repoDir.getAbsolutePath(),
          e.getMessage());
      return false;
    }
  }

  /**
   * Get the current branch name of a local repository. Returns null if the directory is not a git
   * repository or any error occurs.
   */
  public String getCurrentBranch(File repoDir) {
    if (repoDir == null || !repoDir.isDirectory() || !new File(repoDir, ".git").isDirectory()) {
      return null;
    }
    try (Git git = Git.open(repoDir)) {
      return git.getRepository().getBranch();
    } catch (Exception e) {
      log.warn(
          "Failed to get current branch from {}: {}", repoDir.getAbsolutePath(), e.getMessage());
      return null;
    }
  }

  /**
   * List available remote branches for a repository. Returns a list of branch names (without
   * refs/remotes/origin/ prefix). Returns empty list if the directory is not a git repository or
   * any error occurs.
   */
  public java.util.List<String> listBranches(ProjectConfig config, String workDir)
      throws Exception {
    java.util.List<String> branches = new java.util.ArrayList<>();
    File repoDir = new File(workDir);

    if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
      return branches;
    }

    try (Git git = Git.open(repoDir)) {
      configureGitFromSystemSettings(git.getRepository());

      // Fetch latest remote refs
      UsernamePasswordCredentialsProvider creds = resolveCredentials(config);
      org.eclipse.jgit.api.FetchCommand fetchCmd = git.fetch();
      if (creds != null) {
        fetchCmd.setCredentialsProvider(creds);
      }
      fetchCmd.call();

      // List remote branches
      java.util.List<org.eclipse.jgit.lib.Ref> refs =
          git.branchList()
              .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
              .call();
      for (org.eclipse.jgit.lib.Ref ref : refs) {
        String name = ref.getName();
        // Strip refs/remotes/origin/ prefix
        if (name.startsWith("refs/remotes/origin/")) {
          name = name.substring("refs/remotes/origin/".length());
          // Skip HEAD pointer
          if (!"HEAD".equals(name)) {
            branches.add(name);
          }
        }
      }
    }
    java.util.Collections.sort(branches);
    return branches;
  }

  /**
   * Checkout (switch to) a different branch in the local repository. Returns true if successful,
   * false otherwise.
   */
  public boolean checkoutBranch(File repoDir, String branch) {
    if (repoDir == null || !repoDir.isDirectory() || !new File(repoDir, ".git").isDirectory()) {
      return false;
    }
    try (Git git = Git.open(repoDir)) {
      configureGitFromSystemSettings(git.getRepository());
      log.info("Checking out branch {} in {}", branch, repoDir.getAbsolutePath());
      git.checkout().setName(branch).setCreateBranch(false).call();
      return true;
    } catch (Exception e) {
      log.warn(
          "Failed to checkout branch {} in {}: {}",
          branch,
          repoDir.getAbsolutePath(),
          e.getMessage());
      return false;
    }
  }

  /**
   * Create a git worktree for the given project and branch. The worktree is created in a temp
   * directory. Uses command-line git since JGit does not support worktree. Returns the absolute
   * path of the worktree directory.
   */
  public String createWorktree(ProjectConfig config, String branch) throws Exception {
    String projectDir = config.getProjectDir();
    if (projectDir == null || projectDir.trim().isEmpty()) {
      throw new IllegalStateException("项目目录未配置，无法创建 worktree");
    }

    File repoDir = new File(projectDir.trim());
    if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
      throw new IllegalStateException("项目 Git 仓库不存在，请先执行一次常规构建以初始化仓库");
    }

    // Fetch latest in main repo first
    try (Git git = Git.open(repoDir)) {
      configureGitFromSystemSettings(git.getRepository());
      UsernamePasswordCredentialsProvider creds = resolveCredentials(config);
      org.eclipse.jgit.api.FetchCommand fetchCmd = git.fetch();
      if (creds != null) {
        fetchCmd.setCredentialsProvider(creds);
      }
      fetchCmd.call();
    }

    // Generate unique worktree path
    String tempBase = System.getProperty("java.io.tmpdir");
    String safeBranch = branch.replaceAll("[^a-zA-Z0-9_-]", "_");
    String worktreeName =
        config.getProjectName()
            + "_"
            + java.util.UUID.randomUUID().toString().substring(0, 8)
            + "_"
            + safeBranch;
    String worktreePath =
        java.nio.file.Paths.get(tempBase, "autodeploy-worktrees", worktreeName).toString();

    // Ensure parent directory exists
    new File(worktreePath).getParentFile().mkdirs();

    // Use origin/<branch> to avoid "already checked out" conflict with the main repo.
    // This creates a detached-HEAD worktree, which is fine for building.
    ProcessBuilder pb =
        new ProcessBuilder("git", "worktree", "add", worktreePath, "origin/" + branch);
    pb.directory(repoDir);
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    String output = readProcessOutput(proc);
    int exit = proc.waitFor();
    if (exit != 0) {
      throw new IllegalStateException("git worktree add 失败 (exit=" + exit + "): " + output);
    }

    log.info("Created worktree at {} for branch {}", worktreePath, branch);
    return worktreePath;
  }

  /** Remove a git worktree directory. */
  public void removeWorktree(String worktreePath) {
    if (worktreePath == null || worktreePath.isEmpty()) return;
    try {
      File worktreeDir = new File(worktreePath);
      if (!worktreeDir.exists()) return;

      // Find main repo by reading the .git file in the worktree
      File gitFile = new File(worktreeDir, ".git");
      String mainRepoPath = null;
      if (gitFile.isFile()) {
        String content = new String(java.nio.file.Files.readAllBytes(gitFile.toPath())).trim();
        if (content.startsWith("gitdir:")) {
          String gitDir = content.substring("gitdir:".length()).trim();
          // gitDir points to main/.git/worktrees/name — go up two levels
          mainRepoPath = new File(gitDir).getParentFile().getParentFile().getAbsolutePath();
        }
      }

      if (mainRepoPath != null) {
        ProcessBuilder pb =
            new ProcessBuilder("git", "worktree", "remove", "--force", worktreePath);
        pb.directory(new File(mainRepoPath));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        readProcessOutput(proc);
        proc.waitFor();
      }

      // Fallback: delete directory if git worktree remove failed or wasn't possible
      File wtDir = new File(worktreePath);
      if (wtDir.exists()) {
        deleteDirectory(wtDir);
      }

      log.info("Removed worktree at {}", worktreePath);
    } catch (Exception e) {
      log.warn("Failed to remove worktree at {}: {}", worktreePath, e.getMessage());
    }
  }

  private String readProcessOutput(Process proc) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (java.io.BufferedReader reader =
        new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), "UTF-8"))) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
    }
    return sb.toString();
  }

  /** Clone for module scanning. Uses single-branch clone for efficiency. */
  public File shallowClone(ProjectConfig config, String workDir) throws Exception {
    String repoUrl = config.getGitRepoUrl();
    String branch = config.getGitBranch() != null ? config.getGitBranch() : "main";
    File repoDir = new File(workDir);

    UsernamePasswordCredentialsProvider creds = resolveCredentials(config);

    log.info(
        "Cloning for scan {} (branch: {}) into {}", repoUrl, branch, repoDir.getAbsolutePath());
    CloneCommand clone =
        Git.cloneRepository()
            .setURI(repoUrl)
            .setDirectory(repoDir)
            .setBranch(branch)
            .setCloneAllBranches(false); // Only clone the specified branch
    if (creds != null) {
      clone.setCredentialsProvider(creds);
    }
    try (Git g = clone.call()) {
      // no-op: close releases handles
    }
    log.info("Clone for scan completed");
    return repoDir;
  }

  private UsernamePasswordCredentialsProvider resolveCredentials(ProjectConfig config)
      throws IllegalStateException {
    String authEnvKey = config.getGitAuthEnvKey();
    if (authEnvKey != null && !authEnvKey.trim().isEmpty()) {
      String token = EnvVarUtil.getValue(authEnvKey);
      if (token == null) {
        throw new IllegalStateException("Git认证环境变量 " + authEnvKey + " 未设置");
      }
      return buildCredentials(config.getGitRepoUrl(), token);
    }
    return null;
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

  /**
   * Build credentials provider based on URL type. HTTPS: use token as password (works with
   * GitHub/GitLab/Bitbucket personal access tokens). SSH: token is used as passphrase (typically
   * empty for key-based auth without passphrase).
   */
  private UsernamePasswordCredentialsProvider buildCredentials(String repoUrl, String token) {
    if (repoUrl != null && repoUrl.toLowerCase().startsWith("https")) {
      // For HTTPS, token is the password. Username can be anything non-empty
      // (GitHub accepts "oauth2", GitLab accepts "oauth2" or actual username,
      // Gitea/Gogs accept any non-empty string).
      return new UsernamePasswordCredentialsProvider("oauth2", token);
    } else {
      // For SSH, token is typically the passphrase for the SSH key
      // (empty string if no passphrase)
      return new UsernamePasswordCredentialsProvider(token, "");
    }
  }

  /**
   * Configure JGit repository to respect system git configuration. This ensures JGit detects
   * changes the same way as command-line git, especially for settings like core.autocrlf (line
   * ending normalization) and core.fileMode (file permission tracking).
   *
   * <p>Reads config in git's standard priority order: system → user. Settings already present in
   * the repo's own config take precedence over inherited values.
   */
  private void configureGitFromSystemSettings(org.eclipse.jgit.lib.Repository repo) {
    try {
      org.eclipse.jgit.lib.StoredConfig repoConfig = repo.getConfig();

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
        return;
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
  private void loadGitConfigSetting(String filePath, java.util.Map<String, String> target) {
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
  private void applySetting(
      java.util.Map<String, String> target, org.eclipse.jgit.lib.Config cfg, String key) {
    String value = cfg.getString("core", null, key);
    if (value != null) {
      target.put(key, value);
    }
  }
}
