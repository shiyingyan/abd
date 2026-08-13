package com.autodeploy.config;

import com.autodeploy.model.ShiroSession;
import com.autodeploy.repository.ShiroSessionRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Persists Shiro sessions to the {@code shiro_session} table so users don't have to log in again
 * after an application restart. Sessions are Java-serialized and then Base64-encoded for safe
 * storage in a {@code LONGTEXT} column. An in-memory cache avoids deserializing from the database
 * on every request.
 */
@Component
public class JdbcSessionDAO implements SessionDAO {

  private static final Logger log = LoggerFactory.getLogger(JdbcSessionDAO.class);
  private static final long LAST_ACCESS_UPDATE_INTERVAL_MS = 10 * 60 * 1000L;

  @Autowired private ShiroSessionRepository repository;

  private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();

  @Override
  public Serializable create(Session session) {
    Serializable id = session.getId();
    if (id == null) {
      id = java.util.UUID.randomUUID().toString();
      ((SimpleSession) session).setId(id);
    }
    save(session);
    sessionCache.put(asString(id), session);
    return id;
  }

  @Override
  public Session readSession(Serializable sessionId) {
    String id = asString(sessionId);

    Session cached = sessionCache.get(id);
    if (cached != null) {
      if (cached instanceof SimpleSession && ((SimpleSession) cached).isExpired()) {
        sessionCache.remove(id);
        repository.deleteById(id);
        return null;
      }
      return cached;
    }

    ShiroSession row = repository.selectById(id);
    if (row == null || row.getSessionData() == null) return null;
    if (row.getExpireAt() != null && row.getExpireAt().isBefore(LocalDateTime.now())) {
      repository.deleteById(id);
      return null;
    }
    try {
      byte[] data = Base64.getDecoder().decode(row.getSessionData());
      try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
        Session session = (Session) ois.readObject();
        if (session instanceof SimpleSession && ((SimpleSession) session).isExpired()) {
          repository.deleteById(id);
          return null;
        }
        sessionCache.put(id, session);
        return session;
      }
    } catch (Exception e) {
      log.warn("Failed to deserialize session {}: {}", sessionId, e.getMessage());
      return null;
    }
  }

  @Override
  public void update(Session session) {
    if (session == null || session.getId() == null) return;
    String id = asString(session.getId());
    save(session);
    if (session instanceof SimpleSession && ((SimpleSession) session).isExpired()) {
      sessionCache.remove(id);
    } else {
      sessionCache.put(id, session);
    }
  }

  @Override
  public void delete(Session session) {
    if (session == null || session.getId() == null) return;
    String id = asString(session.getId());
    sessionCache.remove(id);
    repository.deleteById(id);
  }

  @Override
  public Collection<Session> getActiveSessions() {
    return Collections.emptyList();
  }

  private void save(Session session) {
    Serializable id = session.getId();
    if (id == null) return;
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
        oos.writeObject(session);
      }
      String encoded = Base64.getEncoder().encodeToString(baos.toByteArray());

      Date lastAccess = session.getLastAccessTime();
      LocalDateTime lastAccessLdt =
          lastAccess != null
              ? LocalDateTime.ofInstant(lastAccess.toInstant(), ZoneId.systemDefault())
              : LocalDateTime.now();
      long timeoutMs = session.getTimeout();
      long expireAtMs = lastAccess.getTime() + (timeoutMs > 0 ? timeoutMs : 24L * 60 * 60 * 1000);
      LocalDateTime expireAt =
          LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtMs), ZoneId.systemDefault());

      ShiroSession existing = repository.selectById(asString(id));
      if (existing == null) {
        ShiroSession row = new ShiroSession();
        row.setSessionId(asString(id));
        row.setSessionData(encoded);
        row.setLastAccessTime(lastAccessLdt);
        row.setExpireAt(expireAt);
        repository.insert(row);
      } else {
        existing.setSessionData(encoded);
        if (existing.getLastAccessTime() == null
            || lastAccess.getTime()
                    - existing
                        .getLastAccessTime()
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                >= LAST_ACCESS_UPDATE_INTERVAL_MS) {
          existing.setLastAccessTime(lastAccessLdt);
          repository.updateById(existing);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to persist session {}: {}", id, e.getMessage());
    }
  }

  private static String asString(Serializable id) {
    return id == null ? null : id.toString();
  }
}
