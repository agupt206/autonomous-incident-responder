package com.example.responder;

import com.example.responder.eval.EvaluationCase;
import com.example.responder.eval.GoldenDatasetGenerator;
import com.example.responder.eval.GradingResult; // We will define this record below if not exists
import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import com.example.responder.service.EmbeddedLogEngine;
import com.example.responder.service.SreAgentService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Allows non-static @MethodSource
public class AgentEvaluationTest {

    @Autowired private SreAgentService agent;

    @Autowired private GoldenDatasetGenerator datasetGenerator;

    @Autowired private ChatClient.Builder clientBuilder;

    @Autowired private EmbeddedLogEngine logEngine;

    // 1. DATA LOADING & GENERATION
    Stream<EvaluationCase> provideGoldenData() throws IOException {
        System.out.println(">>> GENERATING GOLDEN DATASET via LLM...");

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:runbooks/*.md");

        return Stream.of(resources)
                .flatMap(
                        resource -> {
                            try {
                                String content =
                                        new String(
                                                resource.getInputStream().readAllBytes(),
                                                StandardCharsets.UTF_8);
                                String serviceName =
                                        resource.getFilename().replace(".md", "").toLowerCase();

                                System.out.println("   -> Processing: " + serviceName);
                                return datasetGenerator
                                        .generateTestCases(content, serviceName)
                                        .stream();
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read runbook", e);
                            }
                        });
    }

    @ParameterizedTest
    @MethodSource("provideGoldenData")
    void evaluateFullAgentLifecycle(EvaluationCase testCase) {
        System.out.println("\n===================================================");
        System.out.println("EVALUATING CASE: " + testCase.id());
        System.out.println("SERVICE: " + testCase.serviceName());
        System.out.println("USER ISSUE: " + testCase.userIssue());
        System.out.println("---------------------------------------------------");

        // 1. DYNAMIC SETUP: Set the world state to match the test case
        String scenarioId = mapAlertToScenario(testCase.serviceName(), testCase.expectedAlertHeader());
        System.out.println("   -> Loading Simulation Scenario: " + scenarioId);
        logEngine.loadScenario(scenarioId);

        // 2. EXECUTION (Run the Agent Once)
        AnalysisResponse response =
                agent.analyze(
                        new IncidentRequest(testCase.serviceName(), testCase.userIssue(), "1h"));

        // 3. ASSERTION (Run all verifications independent of each other)
        Assertions.assertAll(
                "Agent Performance Metrics",
                () -> verifyRetrievalPrecision(testCase, response),
                () -> verifyActionCorrectness(testCase, response),
                () -> verifyPlanFaithfulness(testCase, response));

        System.out.println("===================================================\n");
    }

    private void verifyRetrievalPrecision(EvaluationCase testCase, AnalysisResponse response) {
        boolean precise =
                response.citations().stream()
                        .allMatch(
                                source ->
                                        source.toLowerCase()
                                                .contains(testCase.serviceName().toLowerCase()));

        if (!precise) {
            System.out.println(
                    "❌ PRECISION FAIL: Expected source containing '"
                            + testCase.serviceName()
                            + "'");
            System.out.println("   ACTUAL SOURCES: " + response.citations());
        } else {
            System.out.println("✅ PRECISION PASS");
            System.out.println("   CORRECT SOURCES: " + response.citations());
        }

        Assertions.assertTrue(
                precise,
                "Context Precision Failed: Expected source "
                        + testCase.serviceName()
                        + " not found in "
                        + response.citations());
    }

    private void verifyActionCorrectness(EvaluationCase testCase, AnalysisResponse response) {
        ChatClient judge = clientBuilder.build();

        var result =
                judge.prompt()
                        .system(
                                "You are a Syntax Judge. Compare two Lucene queries for functional"
                                        + " equivalence.")
                        .user(
                                u ->
                                        u.text(
                                                        """
                        EXPECTED QUERY: {expected}
                        GENERATED QUERY: {actual}
                        Return pass=true if they target the same fields and values.
                        """)
                                                .param("expected", testCase.expectedLuceneQuery())
                                                .param("actual", response.investigationQuery()))
                        .call()
                        .entity(GradingResult.class);

        if (!result.pass()) {
            System.out.println("❌ ACTION FAIL: " + result.reasoning());
            System.out.println("EXPECTED QUERY: " + testCase.expectedLuceneQuery());
            System.out.println("GENERATED QUERY: " + response.investigationQuery());
        } else {
            System.out.println("✅ ACTION PASS: " + result.reasoning());
            System.out.println("EXPECTED QUERY: " + testCase.expectedLuceneQuery());
            System.out.println("GENERATED QUERY: " + response.investigationQuery());
        }

        Assertions.assertTrue(result.pass(), "Action Relevance Failed: " + result.reasoning());
    }

    private void verifyPlanFaithfulness(EvaluationCase testCase, AnalysisResponse response) {
        ChatClient judge = clientBuilder.build();

        // Join lists for easier reading by the LLM
        String formattedGroundTruth = String.join("\n", testCase.expectedRemediation());
        String formattedActualSteps = String.join("\n", response.remediationSteps());

        var result = judge.prompt()
                .system("You are a strict QA Auditor. Your goal is to detect Hallucinations.")
                .user(u -> u.text("""
                    TASK: Check if the 'Agent Output' is faithful to the 'Ground Truth'.
                    
                    GROUND TRUTH (Source of Truth):
                    {groundTruth}
                    
                    AGENT OUTPUT (Generated Plan):
                    {actualSteps}
                    
                    EVALUATION RULES:
                    1. PASS (true): If the Agent's steps are semantically present in the Ground Truth.
                       - Minor phrasing changes are OK (e.g. "Check Health" vs "Verify Health Status").
                       - Omissions are OK (e.g. if the Agent missed a step, it is NOT a hallucination).
                    
                    2. FAIL (false): If the Agent INVENTED a step or advice not found in the Ground Truth.
                       - Example: Suggesting "Restart Database" when the text only says "Check Logs".
                    """)
                        .param("groundTruth", formattedGroundTruth)
                        .param("actualSteps", formattedActualSteps))
                .call()
                .entity(GradingResult.class);

        if (!result.pass()) {
            System.out.println("❌ FAITHFULNESS FAIL: " + result.reasoning());
            System.out.println("EXPECTED STEPS: " + testCase.expectedRemediation());
            System.out.println("ACTUAL STEPS: " + response.remediationSteps());
        } else {
            System.out.println("✅ FAITHFULNESS PASS: " + result.reasoning());
            System.out.println("EXPECTED STEPS: " + testCase.expectedRemediation());
            System.out.println("ACTUAL STEPS: " + response.remediationSteps());
        }
        Assertions.assertTrue(result.pass(), "Faithfulness Failed: " + result.reasoning());
    }

    private String mapAlertToScenario(String serviceName, String alertHeader) {
        String key = serviceName + "::" + alertHeader;

        // Simple heuristic mapping based on your Runbook headers
        return switch (key) {
            // Payment Service
            case "payment-service::Elevated 5xx Error Rate" -> "payment-500-npe";
            case "payment-service::Upstream Gateway Latency" -> "payment-latency";

            // Inventory Service
            case "inventory-service::Database Connection Timeout" -> "inventory-db-timeout";
            case "inventory-service::Elevated 5xx Error Rate" -> "inventory-stock-mismatch";

            // Default / Fallback
            default -> "healthy";
        };
    }

    // 2. THE EVALUATION LOOP
    //    @ParameterizedTest
    //    @MethodSource("provideGoldenData")
    //    void evaluateAgentPerformance(EvaluationCase testCase) {
    //        // 1. Header
    //        System.out.println("\n===================================================");
    //        System.out.println("EVALUATING CASE: " + testCase.id());
    //        System.out.println("SERVICE: " + testCase.serviceName());
    //        System.out.println("USER ISSUE: " + testCase.userIssue());
    //        System.out.println("---------------------------------------------------");
    //
    //        // 2. Execution (The Student)
    //        AnalysisResponse response = agent.analyze(
    //                new IncidentRequest(testCase.serviceName(), testCase.userIssue(), "1h")
    //        );
    //
    //        // 3. Print Comparison (Formatted for Clarity)
    //        System.out.println(">>> EXPECTED QUERY (Ground Truth):");
    //        System.out.println(testCase.expectedLuceneQuery());
    //        System.out.println("");
    //        System.out.println("<<< GENERATED QUERY (Agent):");
    //        System.out.println(response.investigationQuery());
    //        System.out.println("---------------------------------------------------");
    //
    //        // Context Precision
    //        boolean retrievalIsPrecise = response.citations().stream()
    //                .allMatch(source -> source.contains(testCase.serviceName()));
    //
    //        // 4. Evaluation (The Judge)
    //        ChatClient judge = clientBuilder.build();
    //
    //        var gradingResult = judge.prompt()
    //                .system("You are an expert SRE Evaluator. Compare the Actual Output against
    // the Expected Ground Truth.")
    //                .user(u -> u.text("""
    //                        CONTEXT:
    //                        Service: {service}
    //                        Input Issue: {issue}
    //
    //                        EXPECTED GROUND TRUTH:
    //                        - Failure Type: {expectedType}
    //                        - Lucene Query: {expectedQuery}
    //
    //                        ACTUAL AGENT OUTPUT:
    //                        - Failure Type: {actualType}
    //                        - Generated Query: {actualQuery}
    //
    //                        TASK:
    //                        Rate the Agent on 'Answer Relevance'.
    //                        Pass = true if the Generated Query is functionally identical to the
    // Expected Query.
    //                        """)
    //                        .param("service", testCase.serviceName())
    //                        .param("issue", testCase.userIssue())
    //                        .param("expectedType", testCase.expectedAlertHeader())
    //                        .param("expectedQuery", testCase.expectedLuceneQuery())
    //                        .param("actualType", response.failureType())
    //                        .param("actualQuery", response.investigationQuery())
    //                )
    //                .call()
    //                .entity(GradingResult.class);
    //
    //        // 5. Result
    //        System.out.println("JUDGE RESULT: " + (gradingResult.pass() ? "✅ PASS" : "❌ FAIL"));
    //        System.out.println("REASONING: " + gradingResult.reasoning());
    //
    //        if (retrievalIsPrecise) {
    //            System.out.println("CORRECT LISTED CITATIONS: " + response.citations());
    //        }
    //
    //        System.out.println("===================================================\n");
    //
    //        Assertions.assertTrue(retrievalIsPrecise, "Test failed with incorrect citations: " +
    // response.citations());
    //
    //        Assertions.assertTrue(gradingResult.pass(),
    //                "Judge failed the agent on case " + testCase.id() + ": " +
    // gradingResult.reasoning());
    //    }
}
