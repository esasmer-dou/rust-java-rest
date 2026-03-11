package com.reactor.rust.example.service;

import com.reactor.rust.di.annotation.PostConstruct;
import com.reactor.rust.di.annotation.PreDestroy;
import com.reactor.rust.di.annotation.Service;

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
        System.out.println("[NotificationService] Initialized and ready");
    }

    @PreDestroy
    public void cleanup() {
        this.initialized = false;
        System.out.println("[NotificationService] Shutdown complete");
    }

    /**
     * Send a notification.
     */
    public void notify(String message) {
        if (!initialized) {
            System.err.println("[NotificationService] Service not initialized!");
            return;
        }
        System.out.println("[NotificationService] " + message);
    }

    /**
     * Check if service is ready.
     */
    public boolean isReady() {
        return initialized;
    }
}
