package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.util.EnvVarUtil;
import java.io.File;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GitService {

  private static final Logger log = LoggerFactory.getLogger(GitService.class);

  /** Clone or pull a repository into the target directory. */
  public File cloneOrPull(ProjectConfig config, String workDir) throws Exception {
    String repoUrl = config.getGitRepoUrl();
    String branch = config.getGitBranch() != null ? config.getGitBranch() : "main";
    File repoDir = new File(workDir);

    UsernamePasswordCredentialsProvider creds = resolveCredentials(config);

    boolean hasGitRepo = repoDir.exists() && new File(repoDir, ".git").exists();

    if (hasGitRepo) {
      log.info("Existing repository found at {}, attempting git pull", repoDir.getAbsolutePath());
      try (Git git = Git.open(repoDir)) {
        PullCommand pull = git.pull();
        if (creds != null) {
          pull.setCredentialsProvider(creds);
        }
        pull.call();

        String currentBranch = git.getRepository().getBranch();
        if (!branch.equals(currentBranch)) {
          log.info("Branch changed from {} to {}, switching", currentBranch, branch);
          git.checkout().setName(branch).setCreateBranch(false).call();
        }
        log.info("Git pull completed for {}", config.getProjectName());
      } catch (Exception e) {
        log.warn("Git pull failed for {}: {}. Will re-clone.", config.getProjectName(), e.getMessage());
        deleteDirectory(repoDir);
        doClone(repoUrl, branch, repoDir, creds);
      }
    } else {
      if (repoDir.exists()) {
        log.info("Directory exists but no .git found at {}, will clone fresh", repoDir.getAbsolutePath());
        deleteDirectory(repoDir);
      }
      doClone(repoUrl, branch, repoDir, creds);
    }
    return repoDir;
  }

  private void doClone(String repoUrl, String branch, File repoDir,
      UsernamePasswordCredentialsProvider creds) throws Exception {
    log.info("Cloning {} (branch: {}) into {}", repoUrl, branch, repoDir.getAbsolutePath());
    CloneCommand clone =
        Git.cloneRepository().setURI(repoUrl).setDirectory(repoDir).setBranch(branch);
    if (creds != null) {
      clone.setCredentialsProvider(creds);
    }
    clone.call().close();
    log.info("Git clone completed");
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
