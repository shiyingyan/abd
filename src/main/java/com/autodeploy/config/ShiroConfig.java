package com.autodeploy.config;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import org.apache.shiro.authc.Authenticator;
import org.apache.shiro.authc.credential.HashedCredentialsMatcher;
import org.apache.shiro.authc.pam.ModularRealmAuthenticator;
import org.apache.shiro.authz.Authorizer;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.mgt.SubjectDAO;
import org.apache.shiro.mgt.SubjectFactory;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.DefaultWebSubjectFactory;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.apache.shiro.web.session.mgt.WebSessionManager;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShiroConfig {

  @Bean
  public HashedCredentialsMatcher credentialsMatcher() {
    HashedCredentialsMatcher matcher = new HashedCredentialsMatcher();
    matcher.setHashAlgorithmName("SHA-256");
    matcher.setHashIterations(2);
    matcher.setStoredCredentialsHexEncoded(false);
    return matcher;
  }

  @Bean
  public CustomRealm customRealm(HashedCredentialsMatcher credentialsMatcher) {
    CustomRealm realm = new CustomRealm();
    realm.setCredentialsMatcher(credentialsMatcher);
    return realm;
  }

  @Bean
  public Authenticator authenticator(CustomRealm customRealm) {
    ModularRealmAuthenticator authenticator = new ModularRealmAuthenticator();
    authenticator.setRealms(java.util.Collections.singletonList(customRealm));
    return authenticator;
  }

  @Bean
  public Authorizer authorizer(CustomRealm customRealm) {
    return customRealm;
  }

  @Bean
  public SubjectDAO subjectDAO() {
    return new DefaultSubjectDAO();
  }

  @Bean
  public SubjectFactory subjectFactory() {
    return new DefaultWebSubjectFactory();
  }

  @Bean
  public org.apache.shiro.mgt.SecurityManager securityManager(
      CustomRealm customRealm,
      WebSessionManager sessionManager,
      Authenticator authenticator,
      Authorizer authorizer,
      SubjectDAO subjectDAO,
      SubjectFactory subjectFactory) {
    DefaultWebSecurityManager sm = new DefaultWebSecurityManager();
    sm.setRealm(customRealm);
    sm.setSessionManager(sessionManager);
    sm.setAuthenticator(authenticator);
    sm.setAuthorizer(authorizer);
    sm.setSubjectDAO(subjectDAO);
    sm.setSubjectFactory(subjectFactory);
    return sm;
  }

  /**
   * Web session manager wired to the JDBC-backed session DAO. The global timeout is 24 h so a login
   * survives application restarts (sessions live in the {@code shiro_session} table).
   */
  @Bean
  public WebSessionManager sessionManager(SessionDAO sessionDAO) {
    DefaultWebSessionManager sessionManager = new DefaultWebSessionManager();
    sessionManager.setSessionDAO(sessionDAO);
    sessionManager.setGlobalSessionTimeout(24L * 60 * 60 * 1000);
    sessionManager.getSessionIdCookie().setMaxAge(24 * 60 * 60);
    sessionManager.getSessionIdCookie().setHttpOnly(true);
    return sessionManager;
  }

  @Bean
  public ShiroFilterChainDefinition shiroFilterChainDefinition() {
    DefaultShiroFilterChainDefinition chainDefinition = new DefaultShiroFilterChainDefinition();
    chainDefinition.addPathDefinition("/login", "anon");
    chainDefinition.addPathDefinition("/register", "anon");
    chainDefinition.addPathDefinition("/static/**", "anon");
    chainDefinition.addPathDefinition("/api/auth/**", "anon");
    chainDefinition.addPathDefinition("/**", "authc");
    return chainDefinition;
  }

  /**
   * Register Shiro filter with ASYNC dispatcher type support. This ensures the Shiro
   * SecurityManager is bound to the thread during SSE async dispatches, preventing "No
   * SecurityManager accessible" errors.
   */
  @Bean
  public FilterRegistrationBean<Filter> shiroFilterRegistration(
      ShiroFilterFactoryBean shiroFilterFactoryBean) throws Exception {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
    registration.setFilter((Filter) shiroFilterFactoryBean.getObject());
    registration.addUrlPatterns("/*");
    registration.setDispatcherTypes(
        DispatcherType.REQUEST,
        DispatcherType.FORWARD,
        DispatcherType.INCLUDE,
        DispatcherType.ASYNC);
    registration.setOrder(1);
    return registration;
  }
}
