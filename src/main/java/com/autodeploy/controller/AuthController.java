package com.autodeploy.controller;

import com.autodeploy.model.User;
import com.autodeploy.service.UserService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  @Autowired private UserService userService;

  @GetMapping("/login.jsp")
  public String loginPage1() {
    return "redirect:/login";
  }

  @GetMapping({"/login"})
  public String loginPage() {
    Subject subject = SecurityUtils.getSubject();
    if (subject.isAuthenticated()) {
      return "redirect:/";
    }
    return "auth/login";
  }

  @PostMapping("/login")
  public String doLogin(@RequestParam String username, @RequestParam String password, Model model) {
    try {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken(username, password);
      subject.login(token);
      return "redirect:/";
    } catch (AuthenticationException e) {
      log.warn("Login failed for user: {}", username, e);
      model.addAttribute("error", "用户名或密码错误");
      model.addAttribute("username", username);
      return "auth/login";
    }
  }

  @GetMapping("/register")
  public String registerPage() {
    return "auth/register";
  }

  @PostMapping("/register")
  public String doRegister(
      @RequestParam String username, @RequestParam String password, Model model) {
    if (username == null || username.trim().isEmpty()) {
      model.addAttribute("error", "用户名不能为空");
      return "auth/register";
    }
    if (password == null || password.trim().isEmpty()) {
      model.addAttribute("error", "密码不能为空");
      return "auth/register";
    }

    User user = userService.register(username.trim(), password);
    if (user == null) {
      model.addAttribute("error", "用户名已存在");
      model.addAttribute("username", username);
      return "auth/register";
    }

    model.addAttribute("success", "注册成功，请登录");
    return "auth/login";
  }

  @GetMapping("/logout")
  public String logout() {
    Subject subject = SecurityUtils.getSubject();
    subject.logout();
    return "redirect:/login";
  }

  @GetMapping("/")
  public String index() {
    return "redirect:/build";
  }

  @GetMapping("/api/auth/debug-session")
  @ResponseBody
  public Map<String, Object> debugSession() {
    Map<String, Object> result = new LinkedHashMap<>();
    try {
      Subject subject = SecurityUtils.getSubject();
      result.put("authenticated", subject.isAuthenticated());
      result.put("principal", subject.getPrincipal());
      org.apache.shiro.session.Session session = subject.getSession(false);
      result.put("sessionId", session != null ? session.getId() : null);
      if (session != null) {
        Object principals = session.getAttribute(
            "org.apache.shiro.subject.support.DefaultSubjectContext_PRINCIPALS_SESSION_KEY");
        result.put("sessionPrincipals",
            principals != null ? principals.toString() : null);
      }
    } catch (Exception e) {
      result.put("error", e.getMessage());
    }
    return result;
  }
}
