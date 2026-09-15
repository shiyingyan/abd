package com.autodeploy.config;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.subject.PrincipalCollection;

public class SessionRestoreToken implements AuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final PrincipalCollection principals;

  public SessionRestoreToken(PrincipalCollection principals) {
    this.principals = principals;
  }

  @Override
  public Object getPrincipal() {
    return principals.getPrimaryPrincipal();
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  public PrincipalCollection getPrincipals() {
    return principals;
  }
}
