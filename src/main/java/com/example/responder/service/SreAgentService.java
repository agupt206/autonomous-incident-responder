package com.example.responder.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SreAgentService {

    private final ChatClient chatClient;

    public SreAgentService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("You are a Senior SRE. Analyze the given error log briefly.")
                .build();
    }

    public String analyze(String log) {
        return chatClient.prompt()
                .user(log)
                .call()
                .content();
    }
}