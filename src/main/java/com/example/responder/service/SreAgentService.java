package com.example.responder.service;

import com.example.responder.model.AgentConfig;
import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    // Regex to capture content between the first { and last } across newlines
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("(?s)\\{.*\\}");

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

        // --- 1. RETRIEVAL (RAG) WITH ROBUST FILTERING ---
        List<Document> relevantDocs = retrieveContext(request, config);

        if (relevantDocs.isEmpty()) {
            return fallbackResponse(
                    "No relevant runbooks found for service: " + request.serviceName());
        }

        List<String> citations =
                relevantDocs.stream()
                        .map(
                                d ->
                                        d.getMetadata()
                                                .getOrDefault("service_name", "unknown")
                                                .toString())
                        .distinct()
                        .toList();

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
               - **CRITICAL**: First, identify the exact "## Alert: ..." header in the context that matches the issue.
               - Quote the Alert Name in your thought.
               - If multiple alerts are present, pick the ONE that best matches the user's symptoms.

            2. **ACTION**: Call a tool if you need more information.
               - `healthCheck(service)`: Returns 'UP' or 'DOWN'.
               - `searchElfLogs(luceneQuery)`: Returns log counts and samples.
               - **CRITICAL**: When using `searchElfLogs`, copy the Lucene query syntax EXACTLY from the chosen Alert section.

            3. **OBSERVATION**: The tool output will be provided to you.

            **TERMINATION**
            When you have gathered enough evidence, or if the runbook instructions are clear, you must STOP and output the Final Report.

            **FINAL OUTPUT FORMAT**
            Output ONLY the raw JSON object (no markdown, no conversational text):
            {
              "failureType": "String (The Name of the Alert you identified)",
              "rootCauseHypothesis": "String",
              "investigationQuery": "String (The exact Lucene query used)",
              "evidence": { "tool_name": "result_summary" },
              "responsibleTeam": "String",
              "remediationSteps": [ "Step 1", "Step 2" ],
              "requiresEscalation": boolean
            }
            """;

        conversationHistory.add(new SystemMessage(systemPrompt));
        conversationHistory.add(
                new UserMessage(
                        "CONTEXT:\n" + runbookContext + "\n\nUSER ISSUE: " + request.issue()));

        // --- 3. EXECUTION LOOP ---
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("--- Turn {}/{} ---", i + 1, MAX_ITERATIONS);

            var response =
                    chatClient
                            .prompt()
                            .messages(conversationHistory)
                            .tools("healthCheck", "searchElfLogs")
                            .call()
                            .chatResponse();

            var assistantMessage = response.getResult().getOutput();
            conversationHistory.add(assistantMessage);

            String content = assistantMessage.getText();

            // Handle Tool Calls (content is null/empty)
            if (content == null || content.isBlank()) {
                log.debug("Agent generated Tool Call. Continuing loop...");
                continue;
            }

            log.debug("Agent Output: {}", content);

            // FIX: Robust JSON Extraction
            String potentialJson = extractJson(content);
            if (potentialJson != null) {
                try {
                    AnalysisResponse partial =
                            objectMapper.readValue(potentialJson, AnalysisResponse.class);
                    // Inject citations and return
                    return new AnalysisResponse(
                            partial.failureType(),
                            partial.rootCauseHypothesis(),
                            partial.investigationQuery(),
                            partial.evidence(),
                            partial.responsibleTeam(),
                            partial.remediationSteps(),
                            partial.requiresEscalation(),
                            citations);
                } catch (JsonProcessingException e) {
                    log.warn("JSON Parse Error on detected block: {}", e.getMessage());
                    // Don't return fallback yet; let the agent try again or loop continues
                }
            }
        }

        return fallbackResponse(
                "Agent exceeded max iterations ("
                        + MAX_ITERATIONS
                        + ") without producing valid JSON.");
    }

    private List<Document> retrieveContext(IncidentRequest request, AgentConfig config) {
        var requestBuilder = SearchRequest.builder().query(request.issue()).topK(config.topK());

        if (config.minScore() > 0) {
            requestBuilder.similarityThreshold(config.minScore());
        }

        // 1. Database-level filtering (Best effort)
        if (config.strictMetadataFiltering()) {
            String serviceKey = request.serviceName().toLowerCase().trim().replace(" ", "-");
            requestBuilder.filterExpression("service_name == '" + serviceKey + "'");
        }

        List<Document> rawDocs = vectorStore.similaritySearch(requestBuilder.build());

        // 2. Application-level filtering (Guaranteed Precision)
        if (config.strictMetadataFiltering()) {
            String targetService = request.serviceName().toLowerCase().trim().replace(" ", "-");
            return rawDocs.stream()
                    .filter(
                            doc -> {
                                Object metaVal = doc.getMetadata().get("service_name");
                                return metaVal != null && metaVal.toString().equals(targetService);
                            })
                    .collect(Collectors.toList());
        }

        return rawDocs;
    }

    /** Extracts the first outer JSON object {...} from a string, handling markdown and newlines. */
    private String extractJson(String content) {
        if (content == null) return null;
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
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
