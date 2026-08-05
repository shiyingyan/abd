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
    File repoDir = new File(workDir, config.getProjectName());

    // Get auth from env vars
    String authEnvKey = config.getGitAuthEnvKey();
    UsernamePasswordCredentialsProvider creds = null;
    if (authEnvKey != null && !authEnvKey.trim().isEmpty()) {
      String token = EnvVarUtil.getValue(authEnvKey);
      if (token == null) {
        throw new IllegalStateException("Git认证环境变量 " + authEnvKey + " 未设置");
      }
      creds = buildCredentials(repoUrl, token);
    }

    if (repoDir.exists() && new File(repoDir, ".git").exists()) {
      // Pull
      log.info("Pulling {} from {}", config.getProjectName(), repoUrl);
      try (Git git = Git.open(repoDir)) {
        PullCommand pull = git.pull();
        if (creds != null) {
          pull.setCredentialsProvider(creds);
        }
        pull.call();
      }
    } else {
      // Clone
      log.info("Cloning {} from {}", config.getProjectName(), repoUrl);
      repoDir.mkdirs();
      CloneCommand clone =
          Git.cloneRepository().setURI(repoUrl).setDirectory(repoDir).setBranch(branch);
      if (creds != null) {
        clone.setCredentialsProvider(creds);
      }
      clone.call().close();
    }
    return repoDir;
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
