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
        //String serviceKey = request.serviceName().toLowerCase().replace(" ", "-");
        // String safeIssue = safeTruncate(request.issue(), 100);

        SearchRequest searchRequest =
                SearchRequest.builder()
                        .query(request.issue())
                        .topK(2)
                        // TODO: uncomment after implementing RAG eval
                        //.filterExpression("service_name == '" + serviceKey + "'")
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
                    You are a Process-Aware SRE Agent. Your job is to analyze incidents strictly following the provided RUNBOOKS.

                    INPUT CONTEXT:
                    - Service: {service}
                    - User Issue: {issue}
                    - Time Window: {timeWindow}

                    RUNBOOK CONTENT (Markdown):
                    {context}

                    ---------------------------------------------------------
                    INSTRUCTIONS (Follow this Sequence):

                    PHASE 1: TRIAGE (Pulse Check)
                    1. Call the 'healthCheck' tool for the service.
                    2. IF 'DOWN': The issue is critical availability.
                    3. IF 'UP': The issue is likely logic/performance (proceed to investigation).

                    PHASE 2: INVESTIGATION (Forensics)
                    1. Read the Runbook provided in context. Find the section that matches the User Issue.
                    2. EXTRACT the Lucene Query from the ```lucene code block in that section.
                    3. CALL the 'searchElfLogs' tool using that exact query and the time window.
                    4. ANALYZE the tool's output (Match Count, Trace IDs).

                    PHASE 3: REPORTING (Final Answer)
                    1. Map your findings into the JSON structure below.

                    ---------------------------------------------------------
                    JSON MAPPING RULES:
                    - 'failureType': The header of the matching Alert section (e.g. "Elevated 5xx Error Rate").
                    - 'rootCauseHypothesis': Synthesize the Health Status + Log Search Results (e.g., "Service is UP, but logs show 142 500-errors").
                    - 'investigationQuery': The exact Lucene string you extracted.
                    - 'responsibleTeam': The specific team mentioned in the 'Escalation' section.
                    - 'evidence': A key-value map.
                        * Key: "Trace IDs" -> Value: List from log tool.
                        * Key: "Match Count" -> Value: Number from log tool.
                        * Key: "Health Status" -> Value: Result of healthCheck tool.
                    - 'remediationSteps': The exact list of steps found in the 'Remediation' section. Do not summarize or combine steps; extract them as individual items."
                    - 'requiresEscalation': True if the Runbook says to escalate or if severity is Critical.
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
