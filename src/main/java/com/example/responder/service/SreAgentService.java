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
        this.chatClient = builder.defaultTools("healthCheck", "searchElfLogs").build();
        this.vectorStore = vectorStore;
    }

    public AnalysisResponse analyze(IncidentRequest request) {
        log.info(
                ">>> AGENT START: Analyzing issue '{}' for service '{}'",
                request.issue(),
                request.serviceName());

        // String serviceKey = request.serviceName().toLowerCase().replace(" ", "-");

        SearchRequest searchRequest =
                SearchRequest.builder()
                        .query(request.issue())
                        .topK(2)
                        // .filterExpression("service_name == '" + serviceKey + "'")
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

        // FIX 2: Explicit Visual Separators for the Context
        // This prevents the "Frankenstein" issue where the LLM reads the Query from Doc A
        // but the Remediation Steps from Doc B.
        String context =
                similarDocuments.stream()
                        .map(
                                doc ->
                                        "=== FRAGMENT START ===\n"
                                                + doc.getFormattedContent()
                                                + "\n=== FRAGMENT END ===")
                        .collect(Collectors.joining("\n\n"));

        String timeWindow = request.timeWindow() != null ? request.timeWindow() : "1h";

        // FIX 3: Strict "Source of Truth" Prompting
        var aiGeneratedResponse =
                this.chatClient
                        .prompt()
                        .user(
                                u ->
                                        u.text(
                                                        """
                    You are an SRE Incident Analyzer.

                    INPUT CONTEXT:
                    - Service: {service}
                    - User Issue: {issue}

                    RETRIEVED RUNBOOK FRAGMENTS:
                    {context}

                    ---------------------------------------------------------
                    STRICT ANALYSIS RULES:
                    1. **Select ONE Fragment**: Scan the 'FRAGMENT' blocks. Find the ONE that best matches the User Issue.
                    2. **Stay in Bounds**: You must extract the Query and Remediation Steps from that *SAME* Fragment. Do not mix content from different fragments.
                    3. **Verbatim Extraction**:
                       - Copy the Remediation Steps EXACTLY as they appear in the text.
                       - Do NOT paraphrase. Do NOT use your own knowledge.
                       - If the text says "Check HikariPool", you must output "Check HikariPool", not "Check Database".
                    4. **Syntax**: Use SINGLE QUOTES ('') for strings in the Lucene query.

                    EXAMPLE OUTPUT:
                    \\{
                      "failureType": "High Latency",
                      "investigationQuery": "service:'app' AND metric:'latency'",
                      "remediationSteps": [
                        "1. Check Load Balancer status.",
                        "2. If healthy, check database locks."
                      ]
                    \\}
                    ---------------------------------------------------------
                    Generate the JSON response now:
                    """)
                                                .param("service", request.serviceName())
                                                .param("issue", request.issue())
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
}
