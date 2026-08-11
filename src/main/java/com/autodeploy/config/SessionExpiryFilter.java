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
    if (!subject.isAuthenticated()) {
      httpResp.sendRedirect(httpReq.getContextPath() + "/login");
      return;
    }

    try {
      subject.getSession().getAttribute("id");
      chain.doFilter(request, response);
    } catch (Exception e) {
      log.debug("Session expired, redirecting to login");
      try {
        subject.logout();
      } catch (Exception ignored) {
        // ignore logout errors on expired session
      }
      httpResp.sendRedirect(httpReq.getContextPath() + "/login");
    }
  }

  private boolean isAnonymousPath(String uri) {
    return uri.equals("/login")
        || uri.equals("/register")
        || uri.startsWith("/static/")
        || uri.startsWith("/api/auth/");
  }
}
