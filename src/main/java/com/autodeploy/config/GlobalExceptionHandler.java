package com.autodeploy.config;

import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(HttpMessageNotWritableException.class)
  public void handleSseWriteError(
      HttpMessageNotWritableException ex, HttpServletRequest request) {
    String uri = request.getRequestURI();
    String accept = request.getHeader("Accept");
    boolean isSse =
        (uri != null && (uri.contains("/sse") || uri.equals("/api/runtime/install")))
            || (accept != null && accept.contains("text/event-stream"));
    if (isSse) {
      log.debug("SSE client disconnected: {}", uri);
      return;
    }
    log.warn("HttpMessageNotWritableException on {}: {}", uri, ex.getMessage());
  }
}
