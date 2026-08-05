package com.autodeploy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class SystemInitConfig {

    private static final Logger log = LoggerFactory.getLogger(SystemInitConfig.class);

    @Value("${autodeploy.logs-dir}")
    private String logsDir;

    @Value("${autodeploy.builds-dir}")
    private String buildsDir;

    @Value("${autodeploy.data-dir}")
    private String dataDir;

    @Bean
    public ApplicationRunner systemDirInitializer() {
        return args -> {
            createDirIfNotExists(logsDir);
            createDirIfNotExists(buildsDir);
            createDirIfNotExists(dataDir);
            log.info("System work directories initialized: logs={}, builds={}, data={}", logsDir, buildsDir, dataDir);
        };
    }

    private void createDirIfNotExists(String path) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create directory: {}", path);
        }
    }
}
