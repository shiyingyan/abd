package com.autodeploy.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Persisted Shiro session, stored so logins survive application restarts. */
@TableName("shiro_session")
public class ShiroSession {

  @TableId("session_id")
  private String sessionId;

  /** Base64-encoded Java-serialized {@code org.apache.shiro.session.Session}. */
  @TableField("session_data")
  private String sessionData;

  @TableField("last_access_time")
  private LocalDateTime lastAccessTime;

  @TableField("expire_at")
  private LocalDateTime expireAt;

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getSessionData() {
    return sessionData;
  }

  public void setSessionData(String sessionData) {
    this.sessionData = sessionData;
  }

  public LocalDateTime getLastAccessTime() {
    return lastAccessTime;
  }

  public void setLastAccessTime(LocalDateTime lastAccessTime) {
    this.lastAccessTime = lastAccessTime;
  }

  public LocalDateTime getExpireAt() {
    return expireAt;
  }

  public void setExpireAt(LocalDateTime expireAt) {
    this.expireAt = expireAt;
  }
}
