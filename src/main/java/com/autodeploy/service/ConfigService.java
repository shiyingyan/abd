package com.autodeploy.service;

import com.autodeploy.model.ProjectConfig;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.repository.ConfigRepository;
import com.autodeploy.repository.ProjectEnvServerRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigService {

  private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

  @Autowired private ConfigRepository configRepository;

  @Autowired private ProjectEnvServerRepository projectEnvServerRepository;

  @Autowired private BuildCacheService buildCacheService;

  /** List all project configs. */
  public List<ProjectConfig> listAll() {
    return configRepository.selectList(new QueryWrapper<ProjectConfig>().orderByDesc("updated_at"));
  }

  /** Get a config by ID. */
  public ProjectConfig getById(Long id) {
    return configRepository.selectById(id);
  }

  /** Get a config by project name. */
  public ProjectConfig getByProjectName(String projectName) {
    return configRepository.selectOne(
        new QueryWrapper<ProjectConfig>().eq("project_name", projectName));
  }

  /**
   * Save config and env-server associations in one transaction. envServerPairs is a list of
   * long[]{environmentId, serverId}. For new configs: saves config first, then adds associations.
   * For existing configs: replaces all associations with the new set.
   */
  @Transactional
  public String saveWithEnvServers(ProjectConfig config, List<long[]> envServerPairs) {
    String error = save(config);
    if (error != null) {
      return error;
    }
    if (envServerPairs != null && !envServerPairs.isEmpty()) {
      replaceEnvServers(config.getId(), envServerPairs);
    }
    return null;
  }

  /**
   * Replace all env-server associations for a project. Deletes existing associations and inserts
   * new ones.
   */
  @Transactional
  public void replaceEnvServers(Long projectId, List<long[]> pairs) {
    projectEnvServerRepository.delete(
        new QueryWrapper<ProjectEnvServer>().eq("project_id", projectId));
    LocalDateTime now = LocalDateTime.now();
    for (long[] pair : pairs) {
      if (pair.length < 2) continue;
      ProjectEnvServer pes = new ProjectEnvServer();
      pes.setProjectId(projectId);
      pes.setEnvironmentId(pair[0]);
      pes.setServerId(pair[1]);
      pes.setDeployEnabled(true);
      pes.setCreatedAt(now);
      projectEnvServerRepository.insert(pes);
    }
    log.info("Replaced env-server associations for project {}: {} pairs", projectId, pairs.size());
  }

  /**
   * Save (insert or update) a config with field validation. Config changes are immediately
   * effective in the database. For new configs, generates a project_key from
   * MD5(projectName+version+timestamp). Checks for duplicates by projectName+version before
   * inserting.
   */
  @Transactional
  public String save(ProjectConfig config) {
    List<String> errors = validate(config);
    if (!errors.isEmpty()) {
      return errors.get(0);
    }

    if (config.getId() == null) {
      // New config: check for duplicates by projectName + version
      ProjectConfig existing =
          configRepository.selectOne(
              new QueryWrapper<ProjectConfig>()
                  .eq("project_name", config.getProjectName())
                  .eq("version", config.getVersion()));
      if (existing != null) {
        return "项目编码和版本号已存在，请勿重复保存";
      }
      // Generate project_key
      config.setProjectKey(generateProjectKey(config.getProjectName(), config.getVersion()));
      config.setCreatedAt(LocalDateTime.now());
      config.setUpdatedAt(LocalDateTime.now());
      configRepository.insert(config);
      log.info(
          "Created project config: {} (key={})", config.getProjectName(), config.getProjectKey());
    } else {
      // Existing config: do not allow projectKey to be changed
      ProjectConfig existing = configRepository.selectById(config.getId());
      if (existing != null && existing.getProjectKey() != null) {
        config.setProjectKey(existing.getProjectKey());
      }
      config.setUpdatedAt(LocalDateTime.now());
      configRepository.updateById(config);
      log.info("Updated project config: {}", config.getProjectName());
    }
    // Any config change may invalidate the build cache (build command, work dir,
    // language version, deploy source path, etc.), so evict conservatively on every save.
    if (config.getId() != null) {
      buildCacheService.evict(config.getId());
    }
    return null;
  }

  /** Delete a config by ID. */
  @Transactional
  public void delete(Long id) {
    configRepository.deleteById(id);
    buildCacheService.evict(id);
    log.info("Deleted project config id={}", id);
  }

  /**
   * Copy an existing project config to create a new one. All business fields are duplicated; id,
   * projectKey, createdAt, updatedAt are regenerated. Env-server associations are also copied.
   * Returns the new config id, or null if the source doesn't exist.
   */
  @Transactional
  public Long copyConfig(Long sourceId) {
    ProjectConfig source = configRepository.selectById(sourceId);
    if (source == null) {
      return null;
    }

    ProjectConfig copy = new ProjectConfig();
    copy.setProjectName(source.getProjectName() + "-副本");
    copy.setVersion(source.getVersion());
    copy.setGitRepoUrl(source.getGitRepoUrl());
    copy.setGitBranch(source.getGitBranch());
    copy.setGitAuthEnvKey(source.getGitAuthEnvKey());
    copy.setBuildCommand(source.getBuildCommand());
    copy.setBuildWorkDir(source.getBuildWorkDir());
    copy.setDeployServerHost(source.getDeployServerHost());
    copy.setDeployServerPort(source.getDeployServerPort());
    copy.setDeployServerUser(source.getDeployServerUser());
    copy.setDeployAuthEnvKey(source.getDeployAuthEnvKey());
    copy.setDeployTargetPath(source.getDeployTargetPath());
    copy.setDeploySourcePath(source.getDeploySourcePath());
    copy.setStartCommand(source.getStartCommand());
    copy.setRestartCommand(source.getRestartCommand());
    copy.setLanguageType(source.getLanguageType());
    copy.setLanguageVersion(source.getLanguageVersion());
    copy.setCustomInstallDir(source.getCustomInstallDir());
    copy.setProjectDir(source.getProjectDir());
    copy.setInstallDir(source.getInstallDir());
    copy.setScriptDir(source.getScriptDir());

    copy.setProjectKey(generateProjectKey(copy.getProjectName(), copy.getVersion()));
    copy.setCreatedAt(LocalDateTime.now());
    copy.setUpdatedAt(LocalDateTime.now());
    configRepository.insert(copy);

    List<ProjectEnvServer> associations =
        projectEnvServerRepository.selectList(
            new QueryWrapper<ProjectEnvServer>().eq("project_id", sourceId));
    if (associations != null) {
      LocalDateTime now = LocalDateTime.now();
      for (ProjectEnvServer pes : associations) {
        ProjectEnvServer newPes = new ProjectEnvServer();
        newPes.setProjectId(copy.getId());
        newPes.setEnvironmentId(pes.getEnvironmentId());
        newPes.setServerId(pes.getServerId());
        newPes.setDeployEnabled(pes.getDeployEnabled());
        newPes.setCreatedAt(now);
        projectEnvServerRepository.insert(newPes);
      }
    }

    log.info(
        "Copied project config id={} to id={} ({})", sourceId, copy.getId(), copy.getProjectName());
    return copy.getId();
  }

  /**
   * Create a snapshot of the current config for build task isolation. The build task uses this
   * snapshot and is not affected by subsequent config changes.
   */
  public ProjectConfig getSnapshot(Long id) {
    ProjectConfig original = configRepository.selectById(id);
    if (original == null) {
      return null;
    }
    // Return a copy so changes to the DB don't affect the build task
    ProjectConfig snapshot = new ProjectConfig();
    snapshot.setId(original.getId());
    snapshot.setProjectKey(original.getProjectKey());
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
    snapshot.setInstallDir(original.getInstallDir());
    snapshot.setScriptDir(original.getScriptDir());
    snapshot.setLastModuleScanAt(original.getLastModuleScanAt());
    snapshot.setLastModuleScanMsg(original.getLastModuleScanMsg());
    return snapshot;
  }

  /** Generate project key as MD5 hash of projectName + version + timestamp. */
  private String generateProjectKey(String projectName, String version) {
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    String raw = projectName + version + timestamp;
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      // Fallback: use hashCode if MD5 is not available
      return Integer.toHexString(raw.hashCode());
    }
  }

  /** Field validation for project config. */
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
    if (config.getDeployServerPort() != null
        && (config.getDeployServerPort() < 1 || config.getDeployServerPort() > 65535)) {
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
    // Validate deploySourcePath is a valid regex when set
    if (config.getDeploySourcePath() != null && !config.getDeploySourcePath().trim().isEmpty()) {
      try {
        Pattern.compile(config.getDeploySourcePath().trim());
      } catch (PatternSyntaxException e) {
        errors.add("构建产物路径不是合法的正则表达式: " + e.getDescription());
      }
    }
    // Validate installDir is absolute when set; scriptDir is relative to installDir
    if (config.getInstallDir() != null && !config.getInstallDir().trim().isEmpty()) {
      if (!config.getInstallDir().trim().startsWith("/")) {
        errors.add("项目安装目录必须是绝对路径，以 / 开头");
      }
    }
    // scriptDir is relative to installDir; must not be absolute
    if (config.getScriptDir() != null && !config.getScriptDir().trim().isEmpty()) {
      String sd = config.getScriptDir().trim();
      if (sd.startsWith("/")) {
        errors.add("执行脚本目录应填写相对于项目安装目录的路径（不以 / 开头）");
      }
    }
    return errors;
  }
}
