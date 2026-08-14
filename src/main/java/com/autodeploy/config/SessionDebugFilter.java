package com.autodeploy.config;

import com.autodeploy.model.ShiroSession;
import com.autodeploy.repository.ShiroSessionRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Base64;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.apache.shiro.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SessionDebugFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(SessionDebugFilter.class);

  @Autowired private ShiroSessionRepository repository;

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) request;
    String uri = httpReq.getRequestURI();

    if (uri.startsWith("/static/") || uri.equals("/favicon.ico") || uri.equals("/login")) {
      chain.doFilter(request, response);
      return;
    }

    String cookieId = null;
    if (httpReq.getCookies() != null) {
      for (javax.servlet.http.Cookie c : httpReq.getCookies()) {
        if ("JSESSIONID".equals(c.getName())) {
          cookieId = c.getValue();
          break;
        }
      }
    }

    if (cookieId != null) {
      try {
        ShiroSession row = repository.selectById(cookieId);
        if (row != null && row.getSessionData() != null) {
          byte[] data = Base64.getDecoder().decode(row.getSessionData());
          try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            Session session = (Session) ois.readObject();
            Object principals =
                session.getAttribute(
                    "org.apache.shiro.subject.support.DefaultSubjectContext_PRINCIPALS_SESSION_KEY");
            log.info(
                "SessionDebugFilter DIRECT-DB: uri={}, cookieId={}, principals={}",
                uri,
                cookieId,
                principals != null
                    ? principals.getClass().getSimpleName() + "=" + principals
                    : "null");
          }
        } else {
          log.info(
              "SessionDebugFilter DIRECT-DB: uri={}, cookieId={}, NOT FOUND IN DB", uri, cookieId);
        }
      } catch (Exception e) {
        log.error("SessionDebugFilter DIRECT-DB error: {}", e.getMessage());
      }
    }

    chain.doFilter(request, response);
  }
}
