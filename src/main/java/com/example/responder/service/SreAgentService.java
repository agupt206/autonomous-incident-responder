package com.example.responder.service;

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
        // Register the tools so the Agent has "Hands"
        // These strings must match the @Bean names in ToolsConfig.java
        this.chatClient = builder.defaultTools("healthCheck", "searchElfLogs").build();
        this.vectorStore = vectorStore;
    }

    public AnalysisResponse analyze(IncidentRequest request) {
        log.info(
                ">>> AGENT START: Analyzing issue '{}' for service '{}'",
                request.issue(),
                request.serviceName());

        // 1. CONTEXT PREPARATION (Hybrid Search)
        // We filter by 'service_name' metadata to ensure we only get the correct Runbook.
        // This prevents the "Inventory" runbook from polluting the "Payment" analysis.
        // TODO: uncomment after implementing RAG eval
        String serviceKey = request.serviceName().toLowerCase().replace(" ", "-");
        // String safeIssue = safeTruncate(request.issue(), 100);

        SearchRequest searchRequest =
                SearchRequest.builder()
                        .query(request.issue())
                        .topK(2)
                        // TODO: uncomment after implementing RAG eval
                        .filterExpression("service_name == '" + serviceKey + "'")
                        .build();

        List<Document> similarDocuments = vectorStore.similaritySearch(searchRequest);

        List<String> retrievedSources =
                similarDocuments.stream()
                        .map(
                                doc ->
                                        doc.getMetadata()
                                                .getOrDefault("service_name", "unknown")
                                                .toString())
                        .distinct()
                        .toList();

        String context =
                similarDocuments.stream()
                        .map(Document::getFormattedContent)
                        .collect(Collectors.joining("\n---\n"));

        // Default to 1 hour if user didn't specify
        String timeWindow = request.timeWindow() != null ? request.timeWindow() : "1h";

        // 2. THE PROMPT (The "Brain")
        // We use a structured "Chain of Thought" prompt to guide the Agent through the team's
        // specific workflow.
        var aiGeneratedResponse =
                this.chatClient
                        .prompt()
                        .user(
                                u ->
                                        u.text(
                                                        """
                    You are an SRE Incident Analyzer. Your task is to map a User Issue to a specific Runbook Alert and extract structured remediation data.

                    INPUT CONTEXT:
                    - Service: {service}
                    - User Issue: {issue}
                    - Time Window: {timeWindow}

                    RUNBOOK CONTENT:
                    {context}

                    ---------------------------------------------------------
                    LOGIC & EXTRACTION RULES:

                    1. **Identify the Alert**: Match the User Issue to the most relevant 'Alert' section in the Runbook.
                    2. **Extract Query**: Copy the Lucene query from the chosen section exactly.
                    3. **Extract Steps**: Copy the Remediation steps exactly as a list of strings. Do not summarize.
                    4. **Pulse Check**:
                       - Internal Logic: Imagine calling 'healthCheck'.
                       - If the issue implies the service is totally dead, 'rootCauseHypothesis' should reflect critical availability.
                       - If the issue implies errors/slowness, 'rootCauseHypothesis' should reflect performance/logic issues.

                    ---------------------------------------------------------
                    OUTPUT REQUIREMENT:
                    Generate a valid JSON object matching the requested schema.
                    - failureType: The exact header of the matched Alert.
                    - investigationQuery: The Lucene query (use double quotes for strings).
                    - remediationSteps: JSON Array of strings containing the exact text of the steps.
                    """)
                                                .param("service", request.serviceName())
                                                .param("issue", request.issue())
                                                .param("timeWindow", timeWindow)
                                                .param("context", context))
                        .call()
                        .entity(AnalysisResponse.class);

        return new AnalysisResponse(
                aiGeneratedResponse.failureType(),
                aiGeneratedResponse.rootCauseHypothesis(),
                aiGeneratedResponse.investigationQuery(),
                aiGeneratedResponse.evidence(),
                aiGeneratedResponse.responsibleTeam(),
                aiGeneratedResponse.remediationSteps(),
                aiGeneratedResponse.requiresEscalation(),
                retrievedSources);
    }

    // Safety: Prevent huge inputs from crashing the context window
    // TODO: currently not used - add later if useful
    private String safeTruncate(String input, int maxLength) {
        if (input == null) return "";
        return input.length() > maxLength ? input.substring(0, maxLength) : input;
    }
}
