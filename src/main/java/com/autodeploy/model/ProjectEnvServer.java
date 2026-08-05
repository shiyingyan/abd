package com.autodeploy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("project_env_server")
public class ProjectEnvServer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("project_id")
    private Long projectId;

    @TableField("environment_id")
    private Long environmentId;

    @TableField("server_id")
    private Long serverId;

    @TableField("deploy_enabled")
    private Boolean deployEnabled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private String environmentName;

    @TableField(exist = false)
    private String serverName;

    @TableField(exist = false)
    private String serverHost;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }

    public Boolean getDeployEnabled() { return deployEnabled; }
    public void setDeployEnabled(Boolean deployEnabled) { this.deployEnabled = deployEnabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getEnvironmentName() { return environmentName; }
    public void setEnvironmentName(String environmentName) { this.environmentName = environmentName; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }
}
