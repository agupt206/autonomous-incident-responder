package com.example.responder.eval;

import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

@Component
public class GoldenDatasetGenerator {

    private final ChatClient chatClient;

    public GoldenDatasetGenerator(ChatClient.Builder builder) {
        this.chatClient =
                builder.defaultSystem(
                                "You are a QA Engineer responsible for generating test data from"
                                        + " technical documentation.")
                        .defaultOptions(
                                AnthropicChatOptions.builder()
                                        .temperature(0.0)
                                        .maxTokens(4000)
                                        .build())
                        .build();
    }

    public List<EvaluationCase> generateTestCases(String markdownContent, String serviceName) {
        String safeContent = markdownContent.replace("\"", "'");

        return chatClient
                .prompt()
                .user(
                        u ->
                                u.text(
                                                """
                        Analyze the following Runbook Markdown for the service: {serviceName}

                        CONTENT:
                        {content}

                        ---------------------------------------------------------
                        TASK:
                        Generate a list of realistic 'User Incident Reports' based on the specific Alerts defined in the content.

                        OUTPUT JSON FIELDS:
                        1. 'id': Generate a short code (e.g. "{serviceName}-001").
                        2. 'serviceName': Set this exactly to "{serviceName}".
                        3. 'userIssue': A vague but urgent message a human would type.
                        4. 'expectedAlertHeader': The specific Name of the alert (extract the text from the header, excluding '##' markers and the 'Alert:' prefix).
                        5. 'expectedLuceneQuery': Extract the lucene query. CRITICAL: Remove all newlines and ensure it is a single line of text."
                        6. 'expectedRemediation': A JSON List of strings. Extract steps from 'Remediation'.

                        CRITICAL JSON FORMATTING RULES:
                        - You must output VALID JSON.
                        - Do NOT use double quotes (") inside any string value.
                        - REQUIRED: Replace all internal double quotes with single quotes (').

                        EXAMPLE:
                        BAD:  "Check "UP" status"
                        GOOD: "Check 'UP' status"
                        """)
                                        .param("serviceName", serviceName)
                                        .param("content", safeContent))
                .call()
                .entity(new ParameterizedTypeReference<>() {});
    }
}
