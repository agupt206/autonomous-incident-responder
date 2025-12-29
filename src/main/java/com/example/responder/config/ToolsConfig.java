package com.example.responder.config;

import com.example.responder.service.SystemStateService;
import com.example.responder.tools.HealthCheckTool;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

@Configuration
public class ToolsConfig {

    @Bean
    @Description("Check real-time health...")
    public Function<HealthCheckTool.Request, HealthCheckTool.Response> healthCheck(
            SystemStateService systemState) {
        return new HealthCheckTool(systemState); // Pass the service here
    }
}
