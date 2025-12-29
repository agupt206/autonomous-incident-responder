package com.example.responder.service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest; // Import needed
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class SreAgentService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore; // Add this

    public SreAgentService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.chatClient =
                builder.defaultSystem(
                                "You are a Senior SRE. Use the provided RUNBOOK CONTEXT to analyze"
                                        + " errors.")
                        .build();
        this.vectorStore = vectorStore;
    }

    public String analyzeLog(String logEntry) {
        // 1. Search Elasticsearch for similar runbooks
        List<Document> similarDocuments =
                vectorStore.similaritySearch(
                        SearchRequest.builder().query(logEntry).topK(2).build());

        // 2. Turn list of documents into a single string
        String context =
                similarDocuments.stream()
                        .map(Document::getFormattedContent)
                        .collect(Collectors.joining("\n"));

        // 3. Send both the Log AND the Context to the AI
        return this.chatClient
                .prompt()
                .user(
                        u ->
                                u.text(
                                                """
                    ERROR LOG:
                    {log}

                    RELEVANT RUNBOOKS found in database:
                    {context}

                    INSTRUCTIONS:
                    Based strictly on the runbooks above, what is the fix?
                    """)
                                        .param("log", logEntry)
                                        .param("context", context))
                .call()
                .content();
    }
}
