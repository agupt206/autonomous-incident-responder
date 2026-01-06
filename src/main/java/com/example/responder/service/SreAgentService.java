package com.example.responder.service;

import com.example.responder.model.AgentConfig;
import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class SreAgentService {

    private static final Logger log = LoggerFactory.getLogger(SreAgentService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public SreAgentService(ChatClient.Builder builder, VectorStore vectorStore) {
        // Enable tools for the Agentic Loop
        this.chatClient = builder.defaultTools("healthCheck", "searchElfLogs").build();
        this.vectorStore = vectorStore;
    }

    public AnalysisResponse analyze(IncidentRequest request) {
        return analyze(request, AgentConfig.defaults());
    }

    public AnalysisResponse analyze(IncidentRequest request, AgentConfig config) {
        log.info(
                ">>> AGENT START: Analyzing '{}' (Config: TopK={}, Score={}, Temp={})",
                request.issue(),
                config.topK(),
                config.minScore(),
                config.temperature());

        // --- 1. RETRIEVAL (RAG) ---
        var requestBuilder = SearchRequest.builder().query(request.issue()).topK(config.topK());

        if (config.minScore() > 0) {
            requestBuilder.similarityThreshold(config.minScore());
        }

        // CORRECT FIX: Enforce VectorStore filtering whenever service name is known.
        // We do NOT rely on config.strictMetadataFiltering() here, because preventing
        // context pollution is a fundamental requirement, not an option.
        if (request.serviceName() != null && !request.serviceName().isEmpty()) {
            String serviceKey = request.serviceName().toLowerCase().replace(" ", "-");
            requestBuilder.filterExpression("service_name == '" + serviceKey + "'");
        }

        List<Document> similarDocuments = vectorStore.similaritySearch(requestBuilder.build());

        // Capture Citations
        List<String> retrievedSources =
                similarDocuments.stream()
                        .map(
                                doc ->
                                        doc.getMetadata()
                                                .getOrDefault("service_name", "unknown")
                                                .toString())
                        .distinct()
                        .toList();

        if (similarDocuments.isEmpty()) {
            return fallbackResponse(
                    "No relevant runbooks found (Score < " + config.minScore() + ")");
        }

        // --- 2. CONTEXT PREPARATION ---
        String context =
                similarDocuments.stream()
                        .map(
                                doc ->
                                        "--- START ALERT CONFIGURATION ---\n"
                                                + doc.getFormattedContent()
                                                + "\n--- END ALERT CONFIGURATION ---")
                        .collect(Collectors.joining("\n\n"));

        String timeWindow = request.timeWindow() != null ? request.timeWindow() : "1h";

        // --- 3. AGENTIC EXECUTION (Exhaustive Loop) ---
        // We use the "Exhaustive Detective" pattern which proved 100% effective in testing.
        AnalysisResponse agentOutput =
                this.chatClient
                        .prompt()
                        .system(
                                s ->
                                        s.text(
                                                """
                    You are a Lead SRE. You must rigorously diagnose the issue using the provided Runbook Alerts.

                    **PHASE 1: EXHAUSTIVE DIAGNOSTICS**
                    1.  **Extract**: Identify EVERY "Alert" block in the context.
                    2.  **Execute**: Run `searchElfLogs` for **ALL** queries found. Do not skip any.
                        - Use the provided `timeWindow`.
                    3.  **Evaluate**:
                        - Find the Alert where `matchCount > 0`. This is the Root Cause.
                        - IF ALL MATCHES ARE 0: Select the Alert that best matches the User's text description (Fallback).

                    **PHASE 2: STRICT REPORTING**
                    - `investigationQuery`: Output the Lucene string for the Selected Alert.
                        - MUST NOT BE NULL. If no matches, output the Fallback query.
                    - `remediationSteps`:
                        - **CRITICAL**: Copy steps ONLY from the Selected Alert block.
                        - **ISOLATION**: Do NOT combine steps from different alerts.
                        - **VERBATIM**: Do not summarize. Copy every single bullet point and command.
                    - `evidence`: Populate with ALL tool outputs (e.g. latency_check='0 matches', error_check='50 matches').
                    """))
                        .user(
                                u ->
                                        u.text(
                                                        """
                    CONTEXT:
                    - Service: {service}
                    - User Issue: {issue}
                    - Time Window: {timeWindow}

                    AVAILABLE ALERTS (RUNBOOK):
                    {context}

                    Perform diagnostics and report findings.
                    """)
                                                .param("service", request.serviceName())
                                                .param("issue", request.issue())
                                                .param("timeWindow", timeWindow)
                                                .param("context", context))
                        .call()
                        .entity(AnalysisResponse.class);

        // --- 4. MERGE & RETURN ---
        return new AnalysisResponse(
                agentOutput.failureType(),
                agentOutput.rootCauseHypothesis(),
                agentOutput.investigationQuery(),
                agentOutput.evidence(),
                agentOutput.responsibleTeam(),
                agentOutput.remediationSteps(),
                agentOutput.requiresEscalation(),
                retrievedSources);
    }

    private AnalysisResponse fallbackResponse(String reason) {
        return new AnalysisResponse(
                "UNKNOWN",
                reason,
                "N/A",
                java.util.Map.of(),
                "SRE-General",
                java.util.List.of("Escalate to human operator manually."),
                true,
                java.util.List.of());
    }
}