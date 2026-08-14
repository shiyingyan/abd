package com.autodeploy.config;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionExpiryFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(SessionExpiryFilter.class);

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) request;
    HttpServletResponse httpResp = (HttpServletResponse) response;

    String uri = httpReq.getRequestURI();

    if (isAnonymousPath(uri)) {
      chain.doFilter(request, response);
      return;
    }

    Subject subject = SecurityUtils.getSubject();
    log.debug("SessionExpiryFilter: uri={}, pre-authenticated={}", uri, subject.isAuthenticated());

    // Load session first (triggers DB lookup on cache miss, e.g. after restart)
    // before checking authentication state, so that principals can be restored.
    try {
      org.apache.shiro.session.Session session = subject.getSession(false);
      if (session == null) {
        String cookieId = null;
        if (httpReq.getCookies() != null) {
          for (javax.servlet.http.Cookie c : httpReq.getCookies()) {
            if ("JSESSIONID".equals(c.getName())) {
              cookieId = c.getValue();
              break;
            }
          }
        }
        log.info("SessionExpiryFilter: session is null, cookie JSESSIONID={}", cookieId);
        httpResp.sendRedirect(httpReq.getContextPath() + "/login");
        return;
      }
      log.debug(
          "SessionExpiryFilter: session found id={}, authenticated={}",
          session.getId(),
          subject.isAuthenticated());
      session.getAttribute("id");
    } catch (Exception e) {
      log.info("SessionExpiryFilter: exception during session lookup: {}", e.getMessage());
      try {
        subject.logout();
      } catch (Exception ignored) {
        // ignore logout errors on expired session
      }
      httpResp.sendRedirect(httpReq.getContextPath() + "/login");
      return;
    }

    if (!subject.isAuthenticated()) {
      httpResp.sendRedirect(httpReq.getContextPath() + "/login");
      return;
    }

    chain.doFilter(request, response);
  }

  private boolean isAnonymousPath(String uri) {
    return uri.equals("/login")
        || uri.equals("/register")
        || uri.startsWith("/static/")
        || uri.startsWith("/api/auth/");
  }
}
