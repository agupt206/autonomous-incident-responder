package com.example.responder.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class SystemStateService {

    // Simulates our infrastructure (Service Name -> Is Healthy?)
    private final Map<String, Boolean> serviceHealth = new ConcurrentHashMap<>();

    public SystemStateService() {
        // Default: Everything is healthy on startup
        serviceHealth.put("payment-service", true);
        serviceHealth.put("inventory-service", true);
    }

    public boolean isHealthy(String serviceName) {
        // Default to true if we don't know the service
        return serviceHealth.getOrDefault(serviceName.toLowerCase().replace(" ", "-"), true);
    }

    public void setHealth(String serviceName, boolean healthy) {
        serviceHealth.put(serviceName.toLowerCase().replace(" ", "-"), healthy);
    }
}