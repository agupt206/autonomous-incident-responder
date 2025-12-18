package com.example.responder.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SreAgentService {

    private final ChatClient chatClient;

    public SreAgentService(ChatClient.Builder builder) {
        this.chatClient =
                builder.defaultSystem(
                                "You are a Senior SRE. Your goal is to analyze alerts and propose"
                                        + " actionable remediation steps.")
                        .build();
    }

    // Chain of Thought Implementation
    public String analyzeLog(String logEntry) {
        return this.chatClient
                .prompt()
                .user(
                        u ->
                                u.text(
                                                """
                    Analyze the following error log:
                    {log}

                    Return your response in this exact format:
                    1. IDENTIFY: The specific service and failure mode.
                    2. HYPOTHESIS: List 2 potential root causes (network vs application).
                    3. ACTION: One specific CLI command to verify the hypothesis.
                    """)
                                        .param("log", logEntry))
                .call()
                .content();
    }
}
