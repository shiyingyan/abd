package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.util.EnvVarUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    /**
     * Clone or pull a repository into the target directory.
     */
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
            creds = new UsernamePasswordCredentialsProvider(authEnvKey, token);
        }

        if (repoDir.exists() && new File(repoDir, ".git").exists()) {
            // Pull
            log.info("Pulling {} from {}", config.getProjectName(), repoUrl);
            try (Git git = Git.open(repoDir)) {
                if (creds != null) {
                    git.pull().setCredentialsProvider(creds).call();
                } else {
                    git.pull().call();
                }
            }
        } else {
            // Clone
            log.info("Cloning {} from {}", config.getProjectName(), repoUrl);
            repoDir.mkdirs();
            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(repoDir)
                    .setBranch(branch)
                    .setCredentialsProvider(creds)
                    .call()
                    .close();
        }
        return repoDir;
    }
}
