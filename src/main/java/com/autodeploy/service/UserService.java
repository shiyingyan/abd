package com.autodeploy.service;

import com.autodeploy.model.User;
import com.autodeploy.repository.UserRepository;
import org.apache.shiro.crypto.RandomNumberGenerator;
import org.apache.shiro.crypto.SecureRandomNumberGenerator;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.apache.shiro.util.ByteSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int HASH_ITERATIONS = 2;

    /**
     * Register a new user.
     * @param username username
     * @param hashedPassword frontend already hashed the raw password (irreversible hash)
     * @return the created User, or null if username already exists
     */
    public User register(String username, String hashedPassword) {
        User existing = userRepository.findByUsername(username);
        if (existing != null) {
            return null;
        }

        // Generate random salt
        RandomNumberGenerator generator = new SecureRandomNumberGenerator();
        String salt = generator.nextBytes().toBase64();

        // Hash the already-hashed password with salt
        SimpleHash hash = new SimpleHash(
                HASH_ALGORITHM,
                hashedPassword,
                ByteSource.Util.bytes(salt),
                HASH_ITERATIONS
        );

        User user = new User();
        user.setUsername(username);
        user.setPassword(hash.toBase64());
        user.setPasswordSalt(salt);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());

        userRepository.insert(user);
        log.info("User registered: {}", username);
        return user;
    }

    /**
     * Verify login credentials and update last login time.
     * @param username username
     * @param hashedPassword frontend already hashed the raw password
     * @return true if credentials match
     */
    public boolean verifyLogin(String username, String hashedPassword) {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            return false;
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return false;
        }

        SimpleHash hash = new SimpleHash(
                HASH_ALGORITHM,
                hashedPassword,
                ByteSource.Util.bytes(user.getPasswordSalt()),
                HASH_ITERATIONS
        );

        if (hash.toBase64().equals(user.getPassword())) {
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.updateById(user);
            log.info("User logged in: {}", username);
            return true;
        }
        return false;
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username);
    }
}
