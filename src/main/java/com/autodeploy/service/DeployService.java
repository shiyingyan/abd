package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.model.ServerInfo;
import com.autodeploy.util.EnvVarUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    @Autowired private SshService sshService;
    @Autowired private ServerInfoService serverInfoService;

    @Value("${autodeploy.builds-dir}")
    private String buildsDir;

    /**
     * Deploy build artifact to all associated servers:
     * 1. Get server list from project_env_server associations (fallback to inline config fields)
     * 2. Resolve artifact path (by config or language type)
     * 3. For each server: backup, upload, restart
     */
    public boolean deploy(ProjectConfig config, String workDir, Consumer<String> logConsumer) {
        try {
            // Step 1: Resolve artifact source path
            File artifactSource = resolveArtifactSource(config, workDir, logConsumer);
            if (artifactSource == null) {
                return false;
            }
            logConsumer.accept("Deploy artifact source: " + artifactSource.getAbsolutePath());

            // Step 2: Get target servers from associations
            List<ProjectEnvServer> associations = serverInfoService.listProjectAssociations(config.getId());
            if (associations.isEmpty()) {
                // Fallback to inline config fields for backward compatibility
                if (config.getDeployServerHost() != null && !config.getDeployServerHost().trim().isEmpty()) {
                    logConsumer.accept("No server associations found, using inline config (legacy mode)");
                    return deployToServer(config, config.getDeployServerHost(),
                            config.getDeployServerPort() != null ? config.getDeployServerPort() : 22,
                            config.getDeployServerUser(), config.getDeployAuthEnvKey(),
                            artifactSource, logConsumer);
                }
                logConsumer.accept("No deploy servers configured (neither associations nor inline)");
                return false;
            }

            // Step 3: Deploy to each associated server
            boolean allSuccess = true;
            boolean anyDeployed = false;
            for (ProjectEnvServer assoc : associations) {
                if (assoc.getDeployEnabled() != null && !assoc.getDeployEnabled()) {
                    logConsumer.accept("Skipping server (deploy disabled): id=" + assoc.getServerId());
                    continue;
                }
                anyDeployed = true;
                ServerInfo server = serverInfoService.getById(assoc.getServerId());
                if (server == null) {
                    logConsumer.accept("Server not found (id=" + assoc.getServerId() + "), skipping");
                    allSuccess = false;
                    continue;
                }
                logConsumer.accept("Deploying to server: " + server.getName() + " (" + server.getHost() + ")");
                int serverPort = server.getPort() != null ? server.getPort() : 22;
                boolean ok = deployToServer(config, server.getHost(), serverPort, server.getUser(),
                        server.getAuthEnvKey(), artifactSource, logConsumer);
                if (!ok) {
                    logConsumer.accept("Deploy FAILED for server: " + server.getName());
                    allSuccess = false;
                } else {
                    logConsumer.accept("Deploy SUCCESS for server: " + server.getName());
                }
            }
            if (!anyDeployed) {
                logConsumer.accept("No servers enabled for deployment");
            }
            return allSuccess;
        } catch (Exception e) {
            log.error("Deploy failed for {}", config.getProjectName(), e);
            logConsumer.accept("Deploy exception: " + e.getMessage());
            return false;
        }
    }

    private boolean deployToServer(ProjectConfig config, String host, int port, String user,
                                    String authEnvKey, File artifactSource, Consumer<String> logConsumer) {
        try {
            String password = EnvVarUtil.getValue(authEnvKey);
            if (password == null) {
                logConsumer.accept("Deploy auth env var not set: " + authEnvKey);
                log.error("Deploy auth env var not set: {}", authEnvKey);
                return false;
            }

            String targetPath = config.getDeployTargetPath();
            if (targetPath == null || targetPath.trim().isEmpty()) {
                logConsumer.accept("Deploy target path not configured");
                return false;
            }

            // Backup existing deployment
            backupExistingDeployment(host, port, user, password, targetPath, logConsumer);

            // Upload artifact
            logConsumer.accept("Uploading artifact to " + host + ":" + targetPath);
            sshService.uploadFile(host, port, user, password,
                    artifactSource.getAbsolutePath(), targetPath);
            logConsumer.accept("Artifact uploaded successfully");
            log.info("Artifact uploaded to {}:{}", host, targetPath);

            // Execute restart command
            String cmd = config.getRestartCommand();
            if (cmd == null || cmd.trim().isEmpty()) {
                cmd = config.getStartCommand();
            }
            if (cmd != null && !cmd.trim().isEmpty()) {
                logConsumer.accept("Executing command: " + cmd);
                String output = sshService.executeCommand(host, port, user, password, cmd);
                if (output != null && !output.trim().isEmpty()) {
                    logConsumer.accept("Command output: " + output.trim());
                }
                log.info("Restart command output: {}", output);
            }
            return true;
        } catch (Exception e) {
            log.error("Deploy to {} failed", host, e);
            logConsumer.accept("Deploy exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolve the artifact source directory/file based on config or language type.
     * Returns null and logs error via logConsumer if artifact cannot be found.
     */
    private File resolveArtifactSource(ProjectConfig config, String workDir, Consumer<String> logConsumer) {
        // If deploySourcePath is configured, use it (relative to workDir)
        if (config.getDeploySourcePath() != null && !config.getDeploySourcePath().trim().isEmpty()) {
            File source = new File(workDir, config.getDeploySourcePath().trim());
            if (source.exists()) {
                return source;
            }
            logConsumer.accept("Configured artifact path not found: " + source.getAbsolutePath());
            return null;
        }

        // Auto-detect based on language type
        String langType = config.getLanguageType();
        if (langType == null || langType.trim().isEmpty()) {
            return new File(workDir);
        }

        switch (langType.toUpperCase()) {
            case "JAVA": {
                File targetDir = new File(workDir, "target");
                if (targetDir.isDirectory()) {
                    File[] jars = targetDir.listFiles((dir, name) ->
                            name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc"));
                    if (jars != null && jars.length > 0) {
                        return jars[0];
                    }
                }
                return targetDir.isDirectory() ? targetDir : new File(workDir);
            }
            case "NODE": {
                // Step 1: Check vue.config.js for outputDir
                File vueConfig = new File(workDir, "vue.config.js");
                if (vueConfig.isFile()) {
                    String outputDirName = parseVueConfigOutputDir(vueConfig);
                    if (outputDirName != null) {
                        File outputDir = new File(workDir, outputDirName);
                        if (outputDir.isDirectory() && isNonEmpty(outputDir)) {
                            logConsumer.accept("Detected outputDir from vue.config.js: " + outputDirName);
                            return outputDir;
                        }
                        logConsumer.accept("vue.config.js outputDir is '" + outputDirName
                                + "', but directory does not exist or is empty. Please check if the build has completed.");
                        return null;
                    }
                }

                // Step 2: Auto-detect common Node output directories
                String[] nodeOutputDirs = {"dist", "build", ".next", ".output", "out"};
                for (String dirName : nodeOutputDirs) {
                    File outputDir = new File(workDir, dirName);
                    if (outputDir.isDirectory() && isNonEmpty(outputDir)) {
                        logConsumer.accept("Auto-detected Node artifact directory: " + dirName);
                        return outputDir;
                    }
                }

                logConsumer.accept("Node artifact directory not found. No build output detected in "
                        + workDir + ". Please ensure the build has completed successfully.");
                return null;
            }
            case "GO": {
                File[] executables = new File(workDir).listFiles((dir, name) -> {
                    if (name.contains(".")) return false;
                    File f = new File(dir, name);
                    return f.isFile() && f.canExecute();
                });
                if (executables != null && executables.length > 0) {
                    return executables[0];
                }
                return new File(workDir);
            }
            case "PYTHON":
            default:
                return new File(workDir);
        }
    }

    /**
     * Parse vue.config.js to extract the outputDir value.
     * Matches patterns like: outputDir: 'dist' or outputDir: "my-output"
     */
    private String parseVueConfigOutputDir(File vueConfig) {
        try {
            String content = new String(Files.readAllBytes(vueConfig.toPath()), "UTF-8");
            Matcher matcher = Pattern.compile("outputDir\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("Failed to parse vue.config.js: {}", e.getMessage());
        }
        return null;
    }

    private boolean isNonEmpty(File dir) {
        String[] files = dir.list();
        return files != null && files.length > 0;
    }

    /**
     * Backup existing deployment directory on target server by renaming with timestamp suffix.
     */
    private void backupExistingDeployment(String host, int port, String user, String password,
                                          String targetPath, Consumer<String> logConsumer) throws Exception {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String normalizedPath = targetPath.endsWith("/")
                ? targetPath.substring(0, targetPath.length() - 1) : targetPath;
        String backupPath = normalizedPath + "_" + timestamp;

        String checkCmd = "if [ -e \"" + normalizedPath + "\" ]; then echo EXISTS; fi";
        String result = sshService.executeCommand(host, port, user, password, checkCmd);
        if (result != null && result.contains("EXISTS")) {
            logConsumer.accept("Backing up existing deployment to " + backupPath);
            sshService.executeCommand(host, port, user, password,
                    "mv \"" + normalizedPath + "\" \"" + backupPath + "\"");
            logConsumer.accept("Backup completed");
        } else {
            logConsumer.accept("No existing deployment to backup");
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
}
