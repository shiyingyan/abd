package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.util.EnvVarUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    @Autowired private SshService sshService;

    @Value("${autodeploy.builds-dir}")
    private String buildsDir;

    /**
     * Deploy build artifact to target server:
     * 1. Upload artifact via SCP
     * 2. Execute restart/start command
     */
    public boolean deploy(ProjectConfig config, String artifactPath) {
        try {
            String password = getDeployPassword(config);
            if (password == null) {
                log.error("Deploy auth env var not set: {}", config.getDeployAuthEnvKey());
                return false;
            }

            // Upload artifact
            sshService.uploadFile(
                    config.getDeployServerHost(),
                    config.getDeployServerPort() != null ? config.getDeployServerPort() : 22,
                    config.getDeployServerUser(),
                    password,
                    artifactPath,
                    config.getDeployTargetPath()
            );
            log.info("Artifact uploaded to {}:{}", config.getDeployServerHost(), config.getDeployTargetPath());

            // Execute restart command
            String cmd = config.getRestartCommand();
            if (cmd == null || cmd.trim().isEmpty()) {
                cmd = config.getStartCommand();
            }
            if (cmd != null && !cmd.trim().isEmpty()) {
                String output = sshService.executeCommand(
                        config.getDeployServerHost(),
                        config.getDeployServerPort() != null ? config.getDeployServerPort() : 22,
                        config.getDeployServerUser(),
                        password,
                        cmd
                );
                log.info("Restart command output: {}", output);
            }
            return true;
        } catch (Exception e) {
            log.error("Deploy failed for {}", config.getProjectName(), e);
            return false;
        }
    }

    /**
     * Download build artifact from build server to local.
     */
    public String downloadFromBuildServer(String host, int port, String user, String password,
                                          String remotePath, String localDir) throws Exception {
        sshService.downloadFile(host, port, user, password, remotePath, localDir);
        Path remote = Paths.get(remotePath);
        String fileName = remote.getFileName().toString();
        return Paths.get(localDir, fileName).toString();
    }

    /**
     * Test SSH connection to a server.
     */
    public boolean testConnection(String host, int port, String user, String authEnvKey) {
        String password = EnvVarUtil.getValue(authEnvKey);
        if (password == null) {
            return false;
        }
        return sshService.testConnection(host, port, user, password);
    }

    private String getDeployPassword(ProjectConfig config) {
        if (config.getDeployAuthEnvKey() == null || config.getDeployAuthEnvKey().trim().isEmpty()) {
            return null;
        }
        return EnvVarUtil.getValue(config.getDeployAuthEnvKey());
    }
}
