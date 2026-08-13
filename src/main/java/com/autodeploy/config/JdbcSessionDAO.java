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
 * storage in a {@code LONGTEXT} column. The in-memory cache is the source of truth during runtime;
 * the database is only for surviving restarts. DB writes are throttled to at most once every 10
 * minutes per session.
 */
@Component
public class JdbcSessionDAO implements SessionDAO {

  private static final Logger log = LoggerFactory.getLogger(JdbcSessionDAO.class);
  private static final long DB_WRITE_INTERVAL_MS = 10 * 60 * 1000L;

  @Autowired private ShiroSessionRepository repository;

  private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();

  /**
   * Tracks the last DB write timestamp per session. Also serves as a set of session IDs that have
   * been persisted (a non-null value means the row exists in the database).
   */
  private final Map<String, Long> lastDbWriteTime = new ConcurrentHashMap<>();

  /** Tracks whether authentication principals have already been persisted for a session. */
  private final Map<String, Boolean> principalsPersisted = new ConcurrentHashMap<>();

  /** Tracks whether the authenticated flag has already been persisted for a session. */
  private final Map<String, Boolean> authenticatedPersisted = new ConcurrentHashMap<>();

  @javax.annotation.PostConstruct
  public void logStartupSessionCount() {
    try {
      com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ShiroSession> qw =
          new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
      qw.le("expire_at", java.time.LocalDateTime.now());
      long expired = repository.delete(qw);
      long remaining = repository.selectCount(null);
      log.info(
          "JdbcSessionDAO initialized: {} active session(s), {} expired session(s) cleaned up",
          remaining,
          expired);
    } catch (Exception e) {
      log.warn("Failed to count/clean sessions in DB at startup: {}", e.getMessage());
    }
  }

  @Override
  public Serializable create(Session session) {
    Serializable id = session.getId();
    if (id == null) {
      id = java.util.UUID.randomUUID().toString();
      ((SimpleSession) session).setId(id);
    }
    String sid = asString(id);
    log.info("Creating session {}, timeout={}ms", sid, session.getTimeout());
    principalsPersisted.put(sid, false);
    authenticatedPersisted.put(sid, false);
    persistRow(sid, session, false);
    sessionCache.put(sid, session);
    return id;
  }

  @Override
  public Session readSession(Serializable sessionId) {
    String id = asString(sessionId);

    Session cached = sessionCache.get(id);
    if (cached != null) {
      if (cached instanceof SimpleSession && ((SimpleSession) cached).isExpired()) {
        log.debug("Session {} expired in cache, removing", id);
        sessionCache.remove(id);
        lastDbWriteTime.remove(id);
        repository.deleteById(id);
        return null;
      }
      return cached;
    }

    log.debug("Session {} cache miss, querying DB", id);
    ShiroSession row = repository.selectById(id);
    if (row == null || row.getSessionData() == null) {
      log.debug("Session {} not found in DB", id);
      return null;
    }
    if (row.getExpireAt() != null && row.getExpireAt().isBefore(LocalDateTime.now())) {
      log.debug("Session {} expired in DB (expire_at={}), removing", id, row.getExpireAt());
      repository.deleteById(id);
      return null;
    }
    try {
      byte[] data = Base64.getDecoder().decode(row.getSessionData());
      try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
        Session session = (Session) ois.readObject();
        long timeout = session.getTimeout();
        boolean expired =
            session instanceof SimpleSession && ((SimpleSession) session).isExpired();
        boolean hasPrincipals = session.getAttribute(
                "org.apache.shiro.subject.support.DefaultSubjectContext_PRINCIPALS_SESSION_KEY")
            != null;
        boolean hasAuthenticated =
            session.getAttribute(
                    "org.apache.shiro.subject.support.DefaultSubjectContext_AUTHENTICATED_SESSION_KEY")
                != null;
        log.info(
            "Session {} deserialized: timeout={}ms, expired={}, lastAccess={}, hasPrincipals={}, hasAuthenticated={}",
            id,
            timeout,
            expired,
            session.getLastAccessTime(),
            hasPrincipals,
            hasAuthenticated);
        if (expired) {
          log.debug("Session {} is expired after deserialization, removing from DB", id);
          repository.deleteById(id);
          return null;
        }
        sessionCache.put(id, session);
        principalsPersisted.put(id, session.getAttribute(
                "org.apache.shiro.subject.support.DefaultSubjectContext_PRINCIPALS_SESSION_KEY")
            != null);
        authenticatedPersisted.put(id, hasAuthenticated);
        lastDbWriteTime.putIfAbsent(id, System.currentTimeMillis());
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
    if (session instanceof SimpleSession && ((SimpleSession) session).isExpired()) {
      sessionCache.remove(id);
      lastDbWriteTime.remove(id);
      principalsPersisted.remove(id);
      authenticatedPersisted.remove(id);
      repository.deleteById(id);
      return;
    }
    sessionCache.put(id, session);
    boolean hasPrincipals =
        session.getAttribute(
                "org.apache.shiro.subject.support.DefaultSubjectContext_PRINCIPALS_SESSION_KEY")
            != null;
    boolean hasAuthenticated =
        session.getAttribute(
                "org.apache.shiro.subject.support.DefaultSubjectContext_AUTHENTICATED_SESSION_KEY")
            != null;
    boolean forcePrincipals = hasPrincipals && !Boolean.TRUE.equals(principalsPersisted.get(id));
    boolean forceAuthenticated =
        hasAuthenticated && !Boolean.TRUE.equals(authenticatedPersisted.get(id));
    boolean force = forcePrincipals || forceAuthenticated;
    log.info(
        "update() session {}: hasPrincipals={}, hasAuthenticated={}, principalsPersisted={},"
            + " authenticatedPersisted={}, force={}",
        id,
        hasPrincipals,
        hasAuthenticated,
        principalsPersisted.get(id),
        authenticatedPersisted.get(id),
        force);
    persistRow(id, session, force);
    if (forcePrincipals) {
      principalsPersisted.put(id, true);
    }
    if (forceAuthenticated) {
      authenticatedPersisted.put(id, true);
    }
  }

  @Override
  public void delete(Session session) {
    if (session == null || session.getId() == null) return;
    String id = asString(session.getId());
    sessionCache.remove(id);
    lastDbWriteTime.remove(id);
    principalsPersisted.remove(id);
    authenticatedPersisted.remove(id);
    repository.deleteById(id);
  }

  @Override
  public Collection<Session> getActiveSessions() {
    return Collections.emptyList();
  }

  /**
   * Persist the session to the database, throttled to at most once every {@value
   * #DB_WRITE_INTERVAL_MS} ms per session. The cache is the source of truth during runtime; the DB
   * is only for surviving restarts. When a DB write does occur, both {@code session_data} and
   * {@code expire_at} are refreshed so the sliding-timeout expiry column stays in sync.
   */
  private void persistRow(String id, Session session, boolean force) {
    long now = System.currentTimeMillis();
    Long lastWrite = lastDbWriteTime.get(id);
    if (!force && lastWrite != null && now - lastWrite < DB_WRITE_INTERVAL_MS) {
      log.info("persistRow() session {}: THROTTLED (lastWrite={}ms ago)", id, now - lastWrite);
      return;
    }
    log.info("persistRow() session {}: WRITING to DB (force={}, lastWrite={})", id, force,
        lastWrite == null ? "never" : (now - lastWrite) + "ms ago");

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

      if (lastWrite != null) {
        ShiroSession row = new ShiroSession();
        row.setSessionId(id);
        row.setSessionData(encoded);
        row.setLastAccessTime(lastAccessLdt);
        row.setExpireAt(expireAt);
        repository.updateById(row);
      } else {
        ShiroSession existing = repository.selectById(id);
        if (existing != null) {
          existing.setSessionData(encoded);
          existing.setLastAccessTime(lastAccessLdt);
          existing.setExpireAt(expireAt);
          repository.updateById(existing);
        } else {
          ShiroSession row = new ShiroSession();
          row.setSessionId(id);
          row.setSessionData(encoded);
          row.setLastAccessTime(lastAccessLdt);
          row.setExpireAt(expireAt);
          repository.insert(row);
        }
      }
      lastDbWriteTime.put(id, now);
      log.debug("Session {} persisted to DB, expireAt={}", id, expireAt);
    } catch (Exception e) {
      log.warn("Failed to persist session {}: {}", id, e.getMessage());
    }
  }

  private static String asString(Serializable id) {
    return id == null ? null : id.toString();
  }
}
