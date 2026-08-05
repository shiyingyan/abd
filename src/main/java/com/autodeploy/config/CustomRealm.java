package com.autodeploy.config;

import com.autodeploy.model.User;
import com.autodeploy.repository.UserRepository;
import com.autodeploy.service.UserService;
import org.apache.shiro.authc.*;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.util.ByteSource;
import org.springframework.beans.factory.annotation.Autowired;

public class CustomRealm extends AuthorizingRealm {

    @Autowired
    private UserRepository userRepository;

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = (String) token.getPrincipal();
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new UnknownAccountException("用户不存在");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new LockedAccountException("账户已被锁定");
        }
        return new SimpleAuthenticationInfo(
                user.getUsername(),
                user.getPassword(),
                ByteSource.Util.bytes(user.getPasswordSalt()),
                getName()
        );
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        return null;
    }
}
