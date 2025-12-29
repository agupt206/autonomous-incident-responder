package com.example.responder.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Function;

// 1. The Input (What the AI sends us)
public class HealthCheckTool implements Function<HealthCheckTool.Request, HealthCheckTool.Response> {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckTool.class);

    public record Request(String serviceName) {}
    public record Response(String status, String logs) {}

    // 2. The Logic (What we do)
    @Override
    public Response apply(Request request) {
        log.info(">>> TOOL EXECUTION: Checking health for '{}'", request.serviceName());

        // In a real app, you would make an HTTP call here.
        // For this workshop, we SIMULATE a broken service.
        if (request.serviceName().toLowerCase().contains("payment")) {
            return new Response("DOWN", "Connection refused: 500 error. CPU at 99%.");
        }

        return new Response("UP", "Service is healthy. Latency: 45ms");
    }
}