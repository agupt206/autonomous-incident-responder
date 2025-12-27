package com.example.responder;

import org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {ElasticsearchVectorStoreAutoConfiguration.class})
public class AutonomousIncidentResponderApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutonomousIncidentResponderApplication.class, args);
    }
}
