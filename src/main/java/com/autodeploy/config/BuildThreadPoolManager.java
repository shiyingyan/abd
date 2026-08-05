package com.autodeploy.config;

import com.autodeploy.service.SystemSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class BuildThreadPoolManager {

    private static final Logger log = LoggerFactory.getLogger(BuildThreadPoolManager.class);

    @Autowired
    private SystemSettingsService settingsService;

    private ThreadPoolExecutor pool;

    @PostConstruct
    public void init() {
        int maxSize = settingsService.getInt(SystemSettingsService.KEY_MAX_CONCURRENT, 20);
        rebuildPool(maxSize);
    }

    public synchronized void rebuildPool(int maxSize) {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        pool = new ThreadPoolExecutor(
                maxSize, maxSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "build-task-" + System.currentTimeMillis());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("Build thread pool rebuilt with max size: {}", maxSize);
    }

    public void submit(Runnable task) {
        pool.submit(task);
    }

    public int getActiveCount() {
        return pool.getActiveCount();
    }

    public int getQueueSize() {
        return pool.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
