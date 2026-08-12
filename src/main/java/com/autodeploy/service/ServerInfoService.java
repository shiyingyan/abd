package com.autodeploy.service;

import com.autodeploy.model.DeployEnvironment;
import com.autodeploy.model.ProjectEnvServer;
import com.autodeploy.model.ServerInfo;
import com.autodeploy.repository.DeployEnvironmentRepository;
import com.autodeploy.repository.ProjectEnvServerRepository;
import com.autodeploy.repository.ServerInfoRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServerInfoService {

  private static final Logger log = LoggerFactory.getLogger(ServerInfoService.class);

  @Autowired private ServerInfoRepository serverInfoRepository;

  @Autowired private DeployEnvironmentRepository environmentRepository;

  @Autowired private ProjectEnvServerRepository projectEnvServerRepository;

  public List<ServerInfo> listAll() {
    List<ServerInfo> servers =
        serverInfoRepository.selectList(new QueryWrapper<ServerInfo>().orderByDesc("updated_at"));
    for (ServerInfo s : servers) {
      if (s.getEnvironmentId() != null) {
        DeployEnvironment env = environmentRepository.selectById(s.getEnvironmentId());
        if (env != null) {
          s.setEnvironmentName(env.getName());
        }
      }
    }
    return servers;
  }

  public List<ServerInfo> listByType(String serverType) {
    List<ServerInfo> servers =
        serverInfoRepository.selectList(
            new QueryWrapper<ServerInfo>().eq("server_type", serverType).orderByDesc("updated_at"));
    for (ServerInfo s : servers) {
      if (s.getEnvironmentId() != null) {
        DeployEnvironment env = environmentRepository.selectById(s.getEnvironmentId());
        if (env != null) {
          s.setEnvironmentName(env.getName());
        }
      }
    }
    return servers;
  }

  public List<ServerInfo> listByEnvironmentId(Long environmentId) {
    return serverInfoRepository.selectList(
        new QueryWrapper<ServerInfo>()
            .eq("environment_id", environmentId)
            .eq("server_type", ServerInfo.TYPE_DEPLOY)
            .orderByDesc("updated_at"));
  }

  public ServerInfo getById(Long id) {
    return serverInfoRepository.selectById(id);
  }

  @Transactional
  public String save(ServerInfo server) {
    List<String> errors = validate(server);
    if (!errors.isEmpty()) {
      return errors.get(0);
    }
    if (server.getId() == null) {
      server.setCreatedAt(LocalDateTime.now());
      server.setUpdatedAt(LocalDateTime.now());
      serverInfoRepository.insert(server);
      log.info("Created server: {} ({})", server.getName(), server.getHost());
    } else {
      server.setUpdatedAt(LocalDateTime.now());
      serverInfoRepository.updateById(server);
      log.info("Updated server: {} ({})", server.getName(), server.getHost());
    }
    return null;
  }

  @Transactional
  public void delete(Long id) {
    projectEnvServerRepository.delete(new QueryWrapper<ProjectEnvServer>().eq("server_id", id));
    serverInfoRepository.deleteById(id);
    log.info("Deleted server id={}", id);
  }

  private List<String> validate(ServerInfo server) {
    List<String> errors = new java.util.ArrayList<>();
    if (server.getName() == null || server.getName().trim().isEmpty()) {
      errors.add("服务器名称不能为空");
    }
    if (server.getHost() == null || server.getHost().trim().isEmpty()) {
      errors.add("服务器地址不能为空");
    }
    if (server.getServerType() == null || server.getServerType().trim().isEmpty()) {
      errors.add("服务器类型不能为空");
    }
    if (server.getPort() != null && (server.getPort() < 1 || server.getPort() > 65535)) {
      errors.add("SSH 端口必须在 1-65535 范围内");
    }
    if (ServerInfo.TYPE_DEPLOY.equals(server.getServerType())
        && server.getEnvironmentId() == null) {
      errors.add("部署服务器必须选择所属环境");
    }
    return errors;
  }

  // ===== Project-Environment-Server association =====

  public List<ProjectEnvServer> listProjectAssociations(Long projectId) {
    List<ProjectEnvServer> list =
        projectEnvServerRepository.selectList(
            new QueryWrapper<ProjectEnvServer>().eq("project_id", projectId).orderByDesc("id"));
    for (ProjectEnvServer pes : list) {
      DeployEnvironment env = environmentRepository.selectById(pes.getEnvironmentId());
      if (env != null) {
        pes.setEnvironmentName(env.getName());
      }
      ServerInfo server = serverInfoRepository.selectById(pes.getServerId());
      if (server != null) {
        pes.setServerName(server.getName());
        pes.setServerHost(server.getHost());
      }
    }
    return list;
  }

  @Transactional
  public void addProjectServer(Long projectId, Long environmentId, Long serverId) {
    ProjectEnvServer existing =
        projectEnvServerRepository.selectOne(
            new QueryWrapper<ProjectEnvServer>()
                .eq("project_id", projectId)
                .eq("environment_id", environmentId)
                .eq("server_id", serverId));
    if (existing != null) {
      return;
    }
    ProjectEnvServer pes = new ProjectEnvServer();
    pes.setProjectId(projectId);
    pes.setEnvironmentId(environmentId);
    pes.setServerId(serverId);
    pes.setDeployEnabled(true);
    pes.setCreatedAt(LocalDateTime.now());
    projectEnvServerRepository.insert(pes);
    log.info("Added server {} to project {} in environment {}", serverId, projectId, environmentId);
  }

  @Transactional
  public void toggleDeploy(Long id, Boolean enabled) {
    ProjectEnvServer pes = projectEnvServerRepository.selectById(id);
    if (pes != null) {
      pes.setDeployEnabled(enabled);
      projectEnvServerRepository.updateById(pes);
      log.info("Updated deploy_enabled={} for project_env_server id={}", enabled, id);
    }
  }

  @Transactional
  public void removeProjectServer(Long id) {
    projectEnvServerRepository.deleteById(id);
  }
}
