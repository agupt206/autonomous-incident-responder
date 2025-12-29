package com.example.responder;

import com.example.responder.service.IngestionService;
import com.example.responder.service.SreAgentService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class Runner {

    @Bean
    ApplicationRunner startApplication(SreAgentService agent, IngestionService ingestion) {
        return args -> {
            // Step 1: Teach the AI (Ingest)
            try {
                ingestion.ingestRunbooks();
            } catch (Exception e) {
                System.err.println(
                        ">>> CRITICAL: Ingestion failed. Please check your Vector Store"
                                + " (Elasticsearch) state.\n"
                                + "Error: "
                                + e.getMessage());
                return;
            }

            // Step 2: Test the knowledge
            System.out.println("\n>>> Asking AI about 'Connection Refused'...");
            String response = agent.analyzeLog("ERROR: Connection refused at /payment-gateway");

            System.out.println("\n>>> AI RESPONSE:");
            System.out.println(response);
        };
    }
}
