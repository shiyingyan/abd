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
        // Guard 1: refuse to pull if working tree is dirty — protects local edits
        Status status = git.status().call();
        if (status.hasUncommittedChanges() || !status.getUntracked().isEmpty()) {
          String msg =
              "本地仓库有未提交的修改（"
                  + status.getUncommittedChanges().size()
                  + " 个已修改, "
                  + status.getUntracked().size()
                  + " 个未跟踪），请先提交或暂存后再触发构建";
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
      org.eclipse.jgit.revwalk.RevCommit head = git.log().setMaxCount(1).call().iterator().next();
      String hash = head.getName();

      // Detect any working-tree change that hasn't been committed yet.
      Status status = git.status().call();
      boolean dirty =
          status.hasUncommittedChanges()
              || !status.getUntracked().isEmpty()
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
}
