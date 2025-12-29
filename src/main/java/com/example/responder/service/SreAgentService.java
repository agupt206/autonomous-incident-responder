package com.example.responder.service;

import com.example.responder.model.AnalysisResponse; // Import new DTO
import com.example.responder.model.IncidentRequest;  // Import new DTO
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SreAgentService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public SreAgentService(ChatClient.Builder builder, VectorStore vectorStore) {
        // We remove the strict "System" prompt here because .entity() adds its own instructions
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
    }

    // Change return type from String to AnalysisResponse
    public AnalysisResponse analyze(IncidentRequest request) {

        // 1. RAG: Search for runbooks
        List<Document> similarDocuments =
                vectorStore.similaritySearch(
                        SearchRequest.builder().query(request.errorLog()).topK(2).build());

        String context = similarDocuments.stream()
                .map(Document::getFormattedContent)
                .collect(Collectors.joining("\n"));

        // 2. Prompt with Structured Output
        return this.chatClient.prompt()
                .user(u -> u.text("""
                    You are a Senior SRE. Analyze the error using the provided RUNBOOKS.
                    
                    ERROR: {log}
                    SERVICE: {service}
                    RUNBOOKS: {context}
                    
                    RULES FOR OUTPUT:
                    1. 'suggestedSteps': Must contain ONLY executable CLI commands. No explanation text.
                    2. 'rootCauseHypothesis': Keep it under 20 words.
                    3. 'requiresEscalation': Set to TRUE only if the runbook says "Call Senior Engineer" or if no runbook matches. Otherwise FALSE.
                    """)
                        .param("service", request.serviceName())
                        .param("log", request.errorLog())
                        .param("context", context))
                .call()
                .entity(AnalysisResponse.class);
    }
}