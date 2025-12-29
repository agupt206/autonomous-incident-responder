package com.example.responder.config;

import com.example.responder.tools.HealthCheckTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

@Configuration
public class ToolsConfig {

    @Bean
    @Description("Check the real-time health status and logs of a service. Use this when the user asks about system status.")
    public Function<HealthCheckTool.Request, HealthCheckTool.Response> healthCheck() {
        return new HealthCheckTool();
    }
}