package com.example.responder.tools;

import com.example.responder.service.SystemStateService; // Import this
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HealthCheckTool
        implements Function<HealthCheckTool.Request, HealthCheckTool.Response> {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckTool.class);
    private final SystemStateService systemState; // Add dependency

    // Constructor injection
    public HealthCheckTool(SystemStateService systemState) {
        this.systemState = systemState;
    }

    public record Request(String serviceName) {}

    public record Response(String status, String logs) {}

    @Override
    public Response apply(Request request) {
        log.info(">>> TOOL EXECUTION: Checking real-time health for '{}'", request.serviceName());

        boolean isUp = systemState.isHealthy(request.serviceName());

        if (!isUp) {
            // Simulate a realistic crash log
            return new Response(
                    "DOWN", "CRITICAL: Connection Refused. CPU 99%. OOMKilled event detected.");
        }

        return new Response("UP", "Service is healthy. Latency: 45ms. 200 OK.");
    }
}
