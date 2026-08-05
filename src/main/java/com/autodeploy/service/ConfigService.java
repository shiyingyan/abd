package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.repository.ConfigRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    @Autowired
    private ConfigRepository configRepository;

    /**
     * List all project configs.
     */
    public List<ProjectConfig> listAll() {
        return configRepository.selectList(new QueryWrapper<ProjectConfig>().orderByDesc("updated_at"));
    }

    /**
     * Get a config by ID.
     */
    public ProjectConfig getById(Long id) {
        return configRepository.selectById(id);
    }

    /**
     * Get a config by project name.
     */
    public ProjectConfig getByProjectName(String projectName) {
        return configRepository.selectOne(
                new QueryWrapper<ProjectConfig>().eq("project_name", projectName));
    }

    /**
     * Save (insert or update) a config with field validation.
     * Config changes are immediately effective in the database.
     */
    @Transactional
    public String save(ProjectConfig config) {
        List<String> errors = validate(config);
        if (!errors.isEmpty()) {
            return errors.get(0);
        }

        if (config.getId() == null) {
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            configRepository.insert(config);
            log.info("Created project config: {}", config.getProjectName());
        } else {
            config.setUpdatedAt(LocalDateTime.now());
            configRepository.updateById(config);
            log.info("Updated project config: {}", config.getProjectName());
        }
        return null;
    }

    /**
     * Delete a config by ID.
     */
    @Transactional
    public void delete(Long id) {
        configRepository.deleteById(id);
        log.info("Deleted project config id={}", id);
    }

    /**
     * Create a snapshot of the current config for build task isolation.
     * The build task uses this snapshot and is not affected by subsequent config changes.
     */
    public ProjectConfig getSnapshot(Long id) {
        ProjectConfig original = configRepository.selectById(id);
        if (original == null) {
            return null;
        }
        // Return a copy so changes to the DB don't affect the build task
        ProjectConfig snapshot = new ProjectConfig();
        snapshot.setId(original.getId());
        snapshot.setProjectName(original.getProjectName());
        snapshot.setVersion(original.getVersion());
        snapshot.setGitRepoUrl(original.getGitRepoUrl());
        snapshot.setGitBranch(original.getGitBranch());
        snapshot.setGitAuthEnvKey(original.getGitAuthEnvKey());
        snapshot.setBuildCommand(original.getBuildCommand());
        snapshot.setBuildWorkDir(original.getBuildWorkDir());
        snapshot.setDeployServerHost(original.getDeployServerHost());
        snapshot.setDeployServerPort(original.getDeployServerPort());
        snapshot.setDeployServerUser(original.getDeployServerUser());
        snapshot.setDeployAuthEnvKey(original.getDeployAuthEnvKey());
        snapshot.setDeployTargetPath(original.getDeployTargetPath());
        snapshot.setDeploySourcePath(original.getDeploySourcePath());
        snapshot.setStartCommand(original.getStartCommand());
        snapshot.setRestartCommand(original.getRestartCommand());
        snapshot.setLanguageType(original.getLanguageType());
        snapshot.setLanguageVersion(original.getLanguageVersion());
        snapshot.setCustomInstallDir(original.getCustomInstallDir());
        snapshot.setProjectDir(original.getProjectDir());
        return snapshot;
    }

    /**
     * Field validation for project config.
     */
    public List<String> validate(ProjectConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.getProjectName() == null || config.getProjectName().trim().isEmpty()) {
            errors.add("项目名称不能为空");
        }
        if (config.getVersion() == null || config.getVersion().trim().isEmpty()) {
            errors.add("版本号不能为空");
        }
        if (config.getGitRepoUrl() == null || config.getGitRepoUrl().trim().isEmpty()) {
            errors.add("Git 仓库地址不能为空");
        }
        if (config.getBuildCommand() == null || config.getBuildCommand().trim().isEmpty()) {
            errors.add("构建指令不能为空");
        }
        if (config.getDeployServerHost() == null || config.getDeployServerHost().trim().isEmpty()) {
            errors.add("部署服务器地址不能为空");
        }
        if (config.getDeployTargetPath() == null || config.getDeployTargetPath().trim().isEmpty()) {
            errors.add("部署目标路径不能为空");
        }
        if (config.getDeployServerPort() != null && (config.getDeployServerPort() < 1 || config.getDeployServerPort() > 65535)) {
            errors.add("SSH 端口必须在 1-65535 范围内");
        }
        // Validate build work directory is a relative path
        if (config.getBuildWorkDir() != null && !config.getBuildWorkDir().trim().isEmpty()) {
            String workDir = config.getBuildWorkDir().trim();
            if (workDir.startsWith("/") || workDir.startsWith("\\")) {
                errors.add("构建工作目录必须是相对路径，不能以 / 开头");
            }
            if (workDir.contains("..")) {
                errors.add("构建工作目录不能包含 .. (路径遍历)");
            }
        }
        // Validate language version is set when language type is selected
        if (config.getLanguageType() != null && !config.getLanguageType().trim().isEmpty()) {
            if (config.getLanguageVersion() == null || config.getLanguageVersion().trim().isEmpty()) {
                errors.add("已选择语言类型 " + config.getLanguageType() + "，请选择对应的语言版本");
            }
        }
        return errors;
    }
}
