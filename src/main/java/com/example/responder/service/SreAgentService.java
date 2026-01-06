package com.example.responder.service;

import com.example.responder.model.AgentConfig;
import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class SreAgentService {

    private static final Logger log = LoggerFactory.getLogger(SreAgentService.class);
    private static final int MAX_ITERATIONS = 5;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    public SreAgentService(
            ChatClient.Builder builder, VectorStore vectorStore, ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    public AnalysisResponse analyze(IncidentRequest request) {
        return analyze(request, AgentConfig.defaults());
    }

    public AnalysisResponse analyze(IncidentRequest request, AgentConfig config) {
        log.info(">>> RE-ACT AGENT START: Analyzing '{}'", request.issue());

        // --- 1. RETRIEVAL (RAG) ---
        List<Document> relevantDocs = retrieveContext(request, config);

        if (relevantDocs.isEmpty()) {
            return fallbackResponse(
                    "No relevant runbooks found for service: " + request.serviceName());
        }

        // Capture citations for evidence/debugging
        List<String> citations =
                relevantDocs.stream()
                        .map(
                                d ->
                                        d.getMetadata()
                                                .getOrDefault("service_name", "unknown")
                                                .toString())
                        .distinct()
                        .toList();

        // Format Context
        String runbookContext =
                relevantDocs.stream()
                        .map(
                                doc ->
                                        "--- RUNBOOK ALERT CONFIGURATION ---\n"
                                                + doc.getFormattedContent())
                        .collect(Collectors.joining("\n\n"));

        // --- 2. RE-ACT LOOP INITIALIZATION ---
        List<Message> conversationHistory = new ArrayList<>();

        String systemPrompt =
                """
            You are a Senior Site Reliability Engineer (SRE) Agent.
            Your goal is to diagnose incidents by strictly following the provided RUNBOOKS.

            **THE RE-ACT PROTOCOL**
            You must alternate between THOUGHT, ACTION, and OBSERVATION.

            1. **THOUGHT**: Analyze the User Issue against the Runbook Context.
               - Identify the specific Alert block that matches the issue.
               - CHECK: Does the runbook specify a threshold (e.g. latency > 5000)? You MUST use that exact number.

            2. **ACTION**: Call a tool if you need more information.
               - Available Tools: [healthCheck, searchElfLogs]
               - `healthCheck(service)`: Returns 'UP' or 'DOWN'.
               - `searchElfLogs(luceneQuery)`: Returns log counts and samples.
               - **CRITICAL**: When using `searchElfLogs`, copy the Lucene query syntax EXACTLY from the runbook. Do not change fields (e.g. do not swap 'service' for 'application.name').

            3. **OBSERVATION**: The tool output will be provided to you. Read it.

            **TERMINATION**
            When you have gathered enough evidence, or if the runbook instructions are clear (e.g. "If X, then Y"), you must STOP and output the Final Report.

            **FINAL OUTPUT FORMAT**
            You must output a valid JSON object matching this structure exactly (no markdown formatting around it):
            {
              "failureType": "String",
              "rootCauseHypothesis": "String",
              "investigationQuery": "String (The exact Lucene query used)",
              "evidence": { "tool_name": "result_summary" },
              "responsibleTeam": "String",
              "remediationSteps": [ "Step 1", "Step 2" ],
              "requiresEscalation": boolean
            }

            **CONSTRAINT: REMEDIATION STEPS**
            - You must extract steps VERBATIM from the runbook.
            - If the runbook says "Flush Redis", do not write "Clear cache".
            - Include ALL steps listed in the remediation section.
            """;

        conversationHistory.add(new SystemMessage(systemPrompt));
        conversationHistory.add(
                new UserMessage(
                        "CONTEXT:\n" + runbookContext + "\n\nUSER ISSUE: " + request.issue()));

        AnalysisResponse finalResponse = null;

        // --- 3. EXECUTION LOOP ---
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("--- Turn {}/{} ---", i + 1, MAX_ITERATIONS);

            // Call LLM with Tools enabled
            ChatResponse response =
                    chatClient
                            .prompt()
                            .messages(conversationHistory)
                            .functions("healthCheck", "searchElfLogs")
                            .call()
                            .chatResponse();

            Message assistantMessage = response.getResult().getOutput();
            conversationHistory.add(assistantMessage);

            String content = assistantMessage.getText();

            // FIX 1: HANDLE NULL CONTENT (Tool Calls)
            // If the model calls a tool, content is often null. We must continue to let Spring AI
            // execute the tool.
            if (content == null || content.isBlank()) {
                log.debug("Agent generated Tool Call. Continuing loop...");
                continue;
            }

            log.debug("Agent Output: {}", content);

            // Heuristic: Is this the Final JSON?
            if (content.trim().startsWith("{") && content.trim().endsWith("}")) {
                try {
                    finalResponse = objectMapper.readValue(content, AnalysisResponse.class);
                    // Inject citations and return
                    return new AnalysisResponse(
                            finalResponse.failureType(),
                            finalResponse.rootCauseHypothesis(),
                            finalResponse.investigationQuery(),
                            finalResponse.evidence(),
                            finalResponse.responsibleTeam(),
                            finalResponse.remediationSteps(),
                            finalResponse.requiresEscalation(),
                            citations);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse JSON response. Continuing loop...");
                }
            }
        }

        return fallbackResponse(
                "Agent exceeded max iterations ("
                        + MAX_ITERATIONS
                        + ") without strictly formatting final JSON.");
    }

    private List<Document> retrieveContext(IncidentRequest request, AgentConfig config) {
        var requestBuilder = SearchRequest.builder().query(request.issue()).topK(config.topK());

        if (config.minScore() > 0) {
            requestBuilder.similarityThreshold(config.minScore());
        }

        // Attempt Vector Store Filter (May be ignored by some stores if not strictly configured)
        if (config.strictMetadataFiltering()) {
            String serviceKey = request.serviceName().toLowerCase().trim().replace(" ", "-");
            requestBuilder.filterExpression("service_name == '" + serviceKey + "'");
        }

        List<Document> rawDocs = vectorStore.similaritySearch(requestBuilder.build());

        // POST-RETRIEVAL FILTERING (Belt and Suspenders)
        //        if (config.strictMetadataFiltering()) {
        //            String targetService = request.serviceName().toLowerCase().trim().replace(" ",
        // "-");
        //
        //            return rawDocs.stream()
        //                    .filter(doc -> {
        //                        String docService = doc.getMetadata().getOrDefault("service_name",
        // "").toString();
        //                        return docService.equals(targetService);
        //                    })
        //                    .collect(Collectors.toList());
        //        }

        return rawDocs;
    }

    private AnalysisResponse fallbackResponse(String reason) {
        return new AnalysisResponse(
                "AGENT_FAILURE",
                reason,
                "N/A",
                Map.of(),
                "SRE-OnCall",
                List.of("Escalate to human operator."),
                true,
                List.of());
    }
}
