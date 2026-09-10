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
      try (RepoHandle handle = RepoHandle.openRepo(repoDir)) {
        try {
          PullCommand pull = handle.git().pull();
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
            RepositoryState state = handle.repository().getRepositoryState();
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
              handle.git().reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
            } catch (Exception ignore) {
              // ignore reset errors
            }
            throw new IllegalStateException(
                "Git pull 发生合并冲突，已自动将本地分支重置到最近一次提交。"
                    + "请先手动合并目标分支 "
                    + branch
                    + " 并解决冲突后再构建。原始错误: "
                    + msg,
                e);
          }

          boolean localChangesBlockPull =
              msg.contains("would be overwritten") || msg.contains("local changes");
          if (localChangesBlockPull) {
            log.warn(
                "Git pull failed due to uncommitted local changes for {}: {}",
                config.getProjectName(),
                msg);
            throw new IllegalStateException(
                "Git pull 失败：本地未提交的修改与远程更新存在冲突，无法自动合并。"
                    + "请先提交或暂存本地修改，或勾选「跳过代码更新，直接构建」。原始错误: "
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

        String currentBranch = handle.repository().getBranch();
        if (!branch.equals(currentBranch)) {
          log.info("Branch changed from {} to {}, switching", currentBranch, branch);
          try {
            handle.git().checkout().setName(branch).setCreateBranch(false).call();
          } catch (Exception checkoutEx) {
            log.info("Local branch '{}' not found in cloneOrPull, creating from remote", branch);
            handle.git().fetch().call();
            String remoteRef = "refs/remotes/origin/" + branch;
            org.eclipse.jgit.lib.Ref remoteBranch = handle.repository().exactRef(remoteRef);
            if (remoteBranch != null) {
              handle
                  .git()
                  .checkout()
                  .setName(branch)
                  .setCreateBranch(true)
                  .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                  .setStartPoint("origin/" + branch)
                  .call();
            } else {
              throw new IllegalStateException("分支 " + branch + " 在本地和远程均未找到，请先确认远程仓库存在该分支");
            }
          }
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
   * changes, the hash is suffixed with {@code -DIRTY} so that build caches treat "edited but not
   * committed" as a real code change.
   */
  public String getHeadHash(File repoDir) {
    try (RepoHandle handle = RepoHandle.openRepo(repoDir)) {
      if (handle == null) {
        return null;
      }
      org.eclipse.jgit.revwalk.RevCommit head =
          handle.git().log().setMaxCount(1).call().iterator().next();
      String hash = head.getName();
      Status status = handle.status();
      return handle.hasActualUncommittedChanges(status) ? hash + "-DIRTY" : hash;
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
    try (RepoHandle handle = RepoHandle.openRepo(repoDir)) {
      if (handle == null) {
        return false;
      }
      Status status = handle.status();
      boolean hasChanges = handle.hasActualUncommittedChanges(status);

      if (hasChanges || status.hasUncommittedChanges() || !status.getMissing().isEmpty()) {
        log.info(
            "hasUncommittedChanges for {}: jgit.hasUncommittedChanges={}, changed={}, added={}, "
                + "removed={}, missing={}, modified={}, conflicting={}, untracked={}, "
                + "untrackedFolders={}, actual={}",
            repoDir.getAbsolutePath(),
            status.hasUncommittedChanges(),
            status.getChanged(),
            status.getAdded(),
            status.getRemoved(),
            status.getMissing(),
            status.getModified(),
            status.getConflicting(),
            status.getUntracked(),
            status.getUntrackedFolders(),
            hasChanges);
      }

      return hasChanges;
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
    try (RepoHandle handle = RepoHandle.openRepo(repoDir)) {
      if (handle == null) {
        if (repoDir != null) {
          log.info(
              "getCurrentBranch: repository not found at {} (exists={}, isDir={}, hasGit={})",
              repoDir.getAbsolutePath(),
              repoDir.exists(),
              repoDir.isDirectory(),
              repoDir.isDirectory() ? new File(repoDir, ".git").isDirectory() : false);
        }
        return null;
      }
      String branch = handle.repository().getBranch();
      log.info("getCurrentBranch: detected branch '{}' for {}", branch, repoDir.getAbsolutePath());
      return branch;
    } catch (Exception e) {
      log.warn(
          "Failed to get current branch from {}: {}", repoDir.getAbsolutePath(), e.getMessage(), e);
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

    if (workDir == null || workDir.trim().isEmpty()) {
      String repoUrl = config.getGitRepoUrl();
      if (repoUrl != null && !repoUrl.trim().isEmpty()) {
        return listRemoteBranches(config, repoUrl.trim());
      }
      return branches;
    }

    File repoDir = new File(workDir);

    if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
      // No local clone yet — use ls-remote to list branches from the remote URL
      String repoUrl = config.getGitRepoUrl();
      if (repoUrl != null && !repoUrl.trim().isEmpty()) {
        return listRemoteBranches(config, repoUrl.trim());
      }
      return branches;
    }

    try (RepoHandle handle = RepoHandle.openRepo(repoDir)) {
      if (handle == null) {
        return branches;
      }

      // Fetch latest remote refs
      UsernamePasswordCredentialsProvider creds = resolveCredentials(config);
      org.eclipse.jgit.api.FetchCommand fetchCmd = handle.git().fetch();
      if (creds != null) {
        fetchCmd.setCredentialsProvider(creds);
      }
      fetchCmd.call();

      // List remote branches
      java.util.List<org.eclipse.jgit.lib.Ref> refs =
          handle
              .git()
              .branchList()
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

  /** List remote branches using ls-remote, without requiring a local clone. */
  private java.util.List<String> listRemoteBranches(ProjectConfig config, String repoUrl)
      throws Exception {
    java.util.List<String> branches = new java.util.ArrayList<>();
    org.eclipse.jgit.api.LsRemoteCommand lsRemote = Git.lsRemoteRepository().setRemote(repoUrl);
    UsernamePasswordCredentialsProvider creds = resolveCredentials(config);
    if (creds != null) {
      lsRemote.setCredentialsProvider(creds);
    }
    java.util.Collection<org.eclipse.jgit.lib.Ref> refs = lsRemote.call();
    for (org.eclipse.jgit.lib.Ref ref : refs) {
      String name = ref.getName();
      if (name.startsWith("refs/heads/")) {
        branches.add(name.substring("refs/heads/".length()));
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
    try (RepoHandle handle = RepoHandle.openRepo(repoDir)) {
      if (handle == null) {
        return false;
      }
      log.info("Checking out branch {} in {}", branch, repoDir.getAbsolutePath());
      try {
        handle.git().checkout().setName(branch).setCreateBranch(false).call();
        return true;
      } catch (Exception checkoutEx) {
        // Branch may not exist locally — try to create a tracking branch from origin/<branch>
        log.info(
            "Local branch '{}' not found, attempting to create from remote tracking branch",
            branch);
        try {
          handle.git().fetch().call();
          String remoteRef = "refs/remotes/origin/" + branch;
          org.eclipse.jgit.lib.Ref remoteBranch = handle.repository().exactRef(remoteRef);
          if (remoteBranch != null) {
            handle
                .git()
                .checkout()
                .setName(branch)
                .setCreateBranch(true)
                .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setStartPoint("origin/" + branch)
                .call();
            log.info("Created local tracking branch '{}' from origin/{}", branch, branch);
            return true;
          } else {
            log.warn(
                "Remote branch 'origin/{}' not found in {}", branch, repoDir.getAbsolutePath());
            return false;
          }
        } catch (Exception fetchEx) {
          log.warn(
              "Failed to create tracking branch '{}' from remote in {}: {}",
              branch,
              repoDir.getAbsolutePath(),
              fetchEx.getMessage());
          return false;
        }
      }
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
  public String createWorktree(ProjectConfig config, String branch, String username)
      throws Exception {
    String projectDir = config.getProjectDir();
    if (projectDir == null || projectDir.trim().isEmpty()) {
      throw new IllegalStateException("项目目录未配置，无法创建 worktree");
    }

    File repoDir = new File(projectDir.trim());
    if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
      throw new IllegalStateException("项目 Git 仓库不存在，请先执行一次常规构建以初始化仓库");
    }

    // Fetch latest in main repo first
    try (RepoHandle handle = RepoHandle.openRepo(repoDir)) {
      if (handle == null) {
        throw new IllegalStateException("项目 Git 仓库无法打开");
      }
      UsernamePasswordCredentialsProvider creds = resolveCredentials(config);
      org.eclipse.jgit.api.FetchCommand fetchCmd = handle.git().fetch();
      if (creds != null) {
        fetchCmd.setCredentialsProvider(creds);
      }
      fetchCmd.call();
    }

    // Generate unique worktree path with username for traceability
    String tempBase = System.getProperty("java.io.tmpdir");
    String safeBranch = (branch != null ? branch : "HEAD").replaceAll("[^a-zA-Z0-9_-]", "_");
    String safeUsername =
        (username != null ? username : "unknown").replaceAll("[^a-zA-Z0-9_-]", "_");
    String worktreeName =
        config.getProjectName()
            + "_"
            + safeUsername
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
    // Enable core.longpaths to support long file paths on Windows.
    ProcessBuilder pb =
        new ProcessBuilder(
            "git",
            "-c",
            "core.longpaths=true",
            "worktree",
            "add",
            worktreePath,
            "origin/" + branch);
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
    if (files == null) return;
    for (File f : files) {
      if (f.isDirectory()) {
        deleteDirectory(f);
      } else {
        f.delete();
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
}
