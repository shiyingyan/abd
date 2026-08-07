package com.autodeploy.config;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import org.apache.shiro.authc.credential.HashedCredentialsMatcher;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
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
  public DefaultWebSecurityManager securityManager(CustomRealm customRealm) {
    DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
    securityManager.setRealm(customRealm);
    return securityManager;
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
   * SecurityManager is bound to the thread during SSE async dispatches, preventing
   * "No SecurityManager accessible" errors.
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
