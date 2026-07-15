package com.reactor.rust.example.service;

import com.reactor.rust.di.annotation.PostConstruct;
import com.reactor.rust.di.annotation.PreDestroy;
import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.logging.FrameworkLogger;

/**
 * Notification Service - Handles notifications.
 *
 * <p>Uses @Service annotation and demonstrates lifecycle callbacks.</p>
 */
@Service
public class NotificationService {

    private boolean initialized = false;

    @PostConstruct
    public void init() {
        this.initialized = true;
        FrameworkLogger.debug("[NotificationService] Initialized and ready");
    }

    @PreDestroy
    public void cleanup() {
        this.initialized = false;
        FrameworkLogger.debug("[NotificationService] Shutdown complete");
    }

    /**
     * Send a notification.
     */
    public void notify(String message) {
        if (!initialized) {
            FrameworkLogger.warn("[NotificationService] Service not initialized!");
            return;
        }
        FrameworkLogger.debug("[NotificationService] " + message);
    }

    /**
     * Check if service is ready.
     */
    public boolean isReady() {
        return initialized;
    }
}
