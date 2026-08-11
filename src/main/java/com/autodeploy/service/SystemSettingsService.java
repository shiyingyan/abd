package com.autodeploy.service;

import com.autodeploy.model.EnvVarRef;
import com.autodeploy.model.SystemSetting;
import com.autodeploy.repository.EnvVarRefRepository;
import com.autodeploy.repository.SystemSettingRepository;
import com.autodeploy.util.EnvVarUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemSettingsService {

  private static final Logger log = LoggerFactory.getLogger(SystemSettingsService.class);

  public static final String KEY_MAX_CONCURRENT = "max_concurrent";
  public static final String KEY_LOG_RETENTION = "log_retention_days";
  public static final String KEY_HISTORY_RETENTION = "history_retention_days";
  public static final String KEY_BUILD_SERVER_HOST = "build_server_host";
  public static final String KEY_BUILD_SERVER_PORT = "build_server_port";
  public static final String KEY_BUILD_SERVER_USER = "build_server_user";
  public static final String KEY_BUILD_SERVER_AUTH_ENV = "build_server_auth_env_key";
  public static final String KEY_QUEUE_SCHEDULER_ALWAYS_ON = "queue_scheduler_always_on";

  @Autowired private SystemSettingRepository settingRepository;

  @Autowired private EnvVarRefRepository envVarRefRepository;

  public String get(String key) {
    SystemSetting setting =
        settingRepository.selectOne(new QueryWrapper<SystemSetting>().eq("setting_key", key));
    return setting != null ? setting.getSettingValue() : null;
  }

  public int getInt(String key, int defaultValue) {
    String value = get(key);
    try {
      return value != null ? Integer.parseInt(value) : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  public Map<String, String> getAllSettings() {
    List<SystemSetting> list = settingRepository.selectList(null);
    Map<String, String> map = new HashMap<>();
    for (SystemSetting s : list) {
      map.put(s.getSettingKey(), s.getSettingValue());
    }
    return map;
  }

  @Transactional
  public void save(String key, String value, String description) {
    SystemSetting existing =
        settingRepository.selectOne(new QueryWrapper<SystemSetting>().eq("setting_key", key));
    if (existing != null) {
      existing.setSettingValue(value);
      existing.setUpdatedAt(LocalDateTime.now());
      if (description != null) {
        existing.setDescription(description);
      }
      settingRepository.updateById(existing);
    } else {
      SystemSetting setting = new SystemSetting();
      setting.setSettingKey(key);
      setting.setSettingValue(value);
      setting.setDescription(description);
      setting.setUpdatedAt(LocalDateTime.now());
      settingRepository.insert(setting);
    }
    log.info("System setting updated: {} = {}", key, value);
  }

  @Transactional
  public String saveMaxConcurrent(int maxConcurrent) {
    if (maxConcurrent < 1 || maxConcurrent > 20) {
      return "并发数必须在 1-20 范围内";
    }
    save(KEY_MAX_CONCURRENT, String.valueOf(maxConcurrent), "最大并发构建数");
    return null;
  }

  @Transactional
  public void saveLogRetentionDays(int days) {
    save(KEY_LOG_RETENTION, String.valueOf(days), "构建日志保留天数");
  }

  @Transactional
  public void saveHistoryRetentionDays(int days) {
    save(KEY_HISTORY_RETENTION, String.valueOf(days), "构建历史保留天数");
  }

  @Transactional
  public void saveBuildServerConfig(String host, int port, String user, String authEnvKey) {
    save(KEY_BUILD_SERVER_HOST, host, "构建服务器地址");
    save(KEY_BUILD_SERVER_PORT, String.valueOf(port), "构建服务器SSH端口");
    save(KEY_BUILD_SERVER_USER, user, "构建服务器用户名");
    save(KEY_BUILD_SERVER_AUTH_ENV, authEnvKey, "构建服务器认证环境变量名");
  }

  public boolean isQueueSchedulerAlwaysOn() {
    return "true".equals(get(KEY_QUEUE_SCHEDULER_ALWAYS_ON));
  }

  @Transactional
  public void saveQueueSchedulerAlwaysOn(boolean alwaysOn) {
    save(KEY_QUEUE_SCHEDULER_ALWAYS_ON, String.valueOf(alwaysOn), "构建任务调度常驻开关");
  }

  // ===== Env Var Ref management =====

  public List<EnvVarRef> listEnvVarRefs() {
    return envVarRefRepository.selectList(new QueryWrapper<EnvVarRef>().orderByDesc("created_at"));
  }

  @Transactional
  public void addEnvVarRef(String key, String description) {
    EnvVarRef ref = new EnvVarRef();
    ref.setVarKey(key);
    ref.setDescription(description);
    ref.setCreatedAt(LocalDateTime.now());
    envVarRefRepository.insert(ref);
    log.info("Added env var ref: {}", key);
  }

  @Transactional
  public void deleteEnvVarRef(Long id) {
    envVarRefRepository.deleteById(id);
  }

  public List<EnvVarUtil.EnvVarStatus> checkEnvVarStatus() {
    List<EnvVarRef> refs = listEnvVarRefs();
    List<String> keys = new ArrayList<>();
    for (EnvVarRef ref : refs) {
      keys.add(ref.getVarKey());
    }
    return EnvVarUtil.checkKeys(keys);
  }
}
