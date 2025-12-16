package com.example.responder;

import com.example.responder.service.SreAgentService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class Runner {

    @Bean
    ApplicationRunner testConnection(SreAgentService agent) {
        return args -> {
            System.out.println(">>> Testing AI Connection...");
            String response = agent.analyze("ERROR 500: Connection refused at /payment-gateway");
            System.out.println(">>> AI Response: " + response);
        };
    }
}