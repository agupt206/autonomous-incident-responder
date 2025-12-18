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
    ApplicationRunner runAnalysis(SreAgentService agent) {
        return args -> {
            System.out.println(">>> Sending Log to SRE Agent...");

            // The Task: "ERROR: Connection refused at /payment-gateway"
            String response = agent.analyzeLog("ERROR: Connection refused at /payment-gateway");

            System.out.println(">>> SRE Agent Analysis:");
            System.out.println(response);
        };
    }
}