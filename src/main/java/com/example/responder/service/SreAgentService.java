package com.example.responder.service;

import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor; // <--- Import this
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SreAgentService {

    private static final Logger log = LoggerFactory.getLogger(SreAgentService.class);
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public SreAgentService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.chatClient = builder
                // Built-in logging: Prints the Prompt & Token usage to the console
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.vectorStore = vectorStore;
    }

    public AnalysisResponse analyze(IncidentRequest request) {
        long startTime = System.currentTimeMillis();

        // --- PHASE 1: Retrieval (The Memory) ---
        List<Document> similarDocuments =
                vectorStore.similaritySearch(
                        SearchRequest.builder().query(request.errorLog()).topK(2).build());

        long retrievalTime = System.currentTimeMillis() - startTime;
        log.info(">>> RAG Retrieval: Found {} docs in {}ms", similarDocuments.size(), retrievalTime);

        String context = similarDocuments.stream()
                .map(Document::getFormattedContent)
                .collect(Collectors.joining("\n"));

        // --- PHASE 2: Generation (The Brain) ---
        long aiStartTime = System.currentTimeMillis();

        AnalysisResponse response = this.chatClient.prompt()
                .user(u -> u.text("""
                    You are a Senior SRE. Analyze the error using the provided RUNBOOKS and TOOLS.
                    
                    ERROR: {log}
                    SERVICE: {service}
                    RUNBOOKS: {context}
                    
                    INSTRUCTIONS:
                    1. If the error is ambiguous, USE THE 'healthCheck' TOOL to see the real status.
                    2. Incorporate the tool's findings into your root cause hypothesis.
                    
                    RULES FOR OUTPUT:
                    1. 'suggestedSteps': CLI commands only.
                    2. 'rootCauseHypothesis': Mention the tool's result (e.g., "Tool reported CPU 99%").
                    3. 'requiresEscalation': True if tool reports "DOWN".
                    """)
                        .param("service", request.serviceName())
                        .param("log", request.errorLog())
                        .param("context", context))
                // ENABLE THE TOOL HERE:
                .tools("healthCheck")
                .call()
                .entity(AnalysisResponse.class);

        long aiTime = System.currentTimeMillis() - aiStartTime;
        long totalTime = System.currentTimeMillis() - startTime;

        log.info(">>> AI Analysis: Generated in {}ms. Total Request Time: {}ms", aiTime, totalTime);

        return response;
    }
}
