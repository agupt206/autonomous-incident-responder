package com.example.responder;

import com.example.responder.eval.EvaluationCase;
import com.example.responder.eval.GoldenDatasetGenerator;
import com.example.responder.eval.GradingResult;
import com.example.responder.model.AgentConfig;
import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import com.example.responder.service.EmbeddedLogEngine;
import com.example.responder.service.SreAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AgentEvaluationTest {

    @Autowired private SreAgentService agent;
    @Autowired private GoldenDatasetGenerator datasetGenerator;
    @Autowired private ChatClient.Builder clientBuilder;
    @Autowired private EmbeddedLogEngine logEngine;
    @Autowired private ObjectMapper objectMapper;

    // Thread-safe storage
    private final List<EvaluationReportEntry> reportEntries = new CopyOnWriteArrayList<>();

    // --- 1. DATA LOADING ---

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
                                return datasetGenerator
                                        .generateTestCases(content, serviceName)
                                        .stream();
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read runbook", e);
                            }
                        });
    }

    // --- 2. MAIN TEST LOOP (ROBUST ERROR HANDLING) ---

    @ParameterizedTest
    @MethodSource("provideGoldenData")
    void evaluateFullAgentLifecycle(EvaluationCase testCase) {
        System.out.println(">>> Executing Test Case: " + testCase.id());

        var config = AgentConfig.defaults();

        AnalysisResponse response = null;
        GradingResult precisionResult = new GradingResult(false, "N/A - Test Crashed");
        GradingResult actionResult = new GradingResult(false, "N/A - Test Crashed");
        GradingResult faithfulnessResult = new GradingResult(false, "N/A - Test Crashed");
        Exception capturedException = null;

        try {
            // A. SETUP
            String scenarioId =
                    mapAlertToScenario(testCase.serviceName(), testCase.expectedAlertHeader());
            logEngine.loadScenario(scenarioId);

            // B. EXECUTION
            response =
                    agent.analyze(
                            new IncidentRequest(testCase.serviceName(), testCase.userIssue(), "1h"),
                            config);

            // C. EVALUATION
            precisionResult = calculateRetrievalPrecision(testCase, response);
            actionResult = calculateActionCorrectness(testCase, response);
            faithfulnessResult = calculatePlanFaithfulness(testCase, response);

        } catch (Exception e) {
            capturedException = e;
            // Create a dummy response to prevent NPEs in the report
            if (response == null) {
                response =
                        new AnalysisResponse(
                                "CRASHED",
                                "System Exception: " + e.getMessage(),
                                "N/A",
                                Map.of(),
                                "N/A",
                                List.of(),
                                true,
                                List.of());
            }
        } finally {
            // D. RECORDING (Guaranteed to run)
            reportEntries.add(
                    new EvaluationReportEntry(
                            testCase,
                            response,
                            config,
                            precisionResult,
                            actionResult,
                            faithfulnessResult,
                            capturedException));
        }

        // E. ASSERTION (Fail the test AFTER recording)
        if (capturedException != null) {
            Assertions.fail(
                    "Test Failed with Exception: " + capturedException.getMessage(),
                    capturedException);
        }

        // Standard assertions
        GradingResult finalPrecision = precisionResult;
        GradingResult finalAction = actionResult;
        GradingResult finalFaithfulness = faithfulnessResult;

        Assertions.assertAll(
                "Agent Performance Metrics",
                () ->
                        Assertions.assertTrue(
                                finalPrecision.pass(),
                                "Retrieval Precision Failed: " + finalPrecision.reasoning()),
                () ->
                        Assertions.assertTrue(
                                finalAction.pass(),
                                "Action Correctness Failed: " + finalAction.reasoning()),
                () ->
                        Assertions.assertTrue(
                                finalFaithfulness.pass(),
                                "Plan Faithfulness Failed: " + finalFaithfulness.reasoning()));
    }

    // --- 3. REPORTING HOOK ---

    @AfterAll
    void generateExperimentReport() {
        String timestamp =
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "experiment_results_" + timestamp + ".txt";
        Path reportPath = Paths.get(filename);

        StringBuilder sb = new StringBuilder();
        sb.append("BENCHMARK EXPERIMENT REPORT\n");
        sb.append("Timestamp: ").append(timestamp).append("\n");
        sb.append("Total Cases: ").append(reportEntries.size()).append("\n");
        long failCount = reportEntries.stream().filter(e -> e.exception() != null).count();
        sb.append("Crashes: ").append(failCount).append("\n\n");

        for (EvaluationReportEntry entry : reportEntries) {
            try {
                sb.append(formatReportEntry(entry));
            } catch (Exception e) {
                sb.append("Error formatting entry: ").append(e.getMessage()).append("\n");
            }
        }

        try {
            Files.writeString(
                    reportPath,
                    sb.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("\n\n=======================================================");
            System.out.println("✅ REPORT GENERATED: " + reportPath.toAbsolutePath());
            System.out.println("=======================================================\n");
        } catch (IOException e) {
            System.err.println("FAILED TO WRITE REPORT: " + e.getMessage());
        }
    }

    private String formatReportEntry(EvaluationReportEntry entry) throws Exception {
        EvaluationCase tc = entry.testCase();
        AnalysisResponse ar = entry.agentResponse();
        AgentConfig cfg = entry.config();

        String crashInfo = "";
        if (entry.exception() != null) {
            StringWriter sw = new StringWriter();
            entry.exception().printStackTrace(new PrintWriter(sw));
            crashInfo =
                    "\n[CRITICAL FAILURE]\nException: "
                            + entry.exception().getMessage()
                            + "\n"
                            + sw.toString()
                            + "\n";
        }

        String rawJson = "N/A";
        try {
            rawJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ar);
        } catch (Exception ignored) {
        }

        return """
               === TEST CASE: %s ===
               SERVICE: %s
               SCENARIO: %s

               [CONFIGURATION]
               - TopK: %d
               - Min Score: %.2f
               - Temperature: %.2f
               - Strict Filtering: %s

               [INPUT]
               User Query: %s

               [EXPECTED_GROUND_TRUTH]
               Lucene Query: %s
               Remediation Steps: %s

               [ACTUAL_AGENT_OUTPUT]
               Lucene Query: %s
               Remediation Steps: %s

               [EVALUATION_METRICS]
               - Retrieval Precision: [%s] (%s)
               - Action Correctness:  [%s] (%s)
               - Plan Faithfulness:   [%s] (%s)
               %s
               [RAW_DATA_JSON]
               %s
               ========================

               """
                .formatted(
                        tc.id(),
                        tc.serviceName(),
                        tc.expectedAlertHeader(),
                        cfg.topK(),
                        cfg.minScore(),
                        cfg.temperature(),
                        cfg.strictMetadataFiltering(),
                        tc.userIssue(),
                        tc.expectedLuceneQuery(),
                        tc.expectedRemediation(),
                        ar.investigationQuery(),
                        ar.remediationSteps(),
                        entry.precision().pass() ? "PASS" : "FAIL",
                        entry.precision().reasoning(),
                        entry.action().pass() ? "PASS" : "FAIL",
                        entry.action().reasoning(),
                        entry.faithfulness().pass() ? "PASS" : "FAIL",
                        entry.faithfulness().reasoning(),
                        crashInfo,
                        rawJson);
    }

    // --- 4. METRIC CALCULATORS ---

    private GradingResult calculateRetrievalPrecision(
            EvaluationCase testCase, AnalysisResponse response) {
        // 1. Check for empty results
        if (response.citations() == null || response.citations().isEmpty()) {
            return new GradingResult(false, "FAIL: No documents retrieved.");
        }

        // 2. Strict Metadata Matching
        // We expect the retrieved source to EXACTLY match the service name (e.g.,
        // "payment-service")
        // defined in IngestionService.
        boolean allSourcesMatch =
                response.citations().stream()
                        .allMatch(source -> source.equalsIgnoreCase(testCase.serviceName()));

        if (allSourcesMatch) {
            return new GradingResult(
                    true, "PASS: All retrieved fragments belong to " + testCase.serviceName());
        } else {
            return new GradingResult(
                    false,
                    "FAIL: Context Pollution Detected. Retrieved: "
                            + response.citations()
                            + " | Expected: "
                            + testCase.serviceName());
        }
    }

    private GradingResult calculateActionCorrectness(
            EvaluationCase testCase, AnalysisResponse response) {
        ChatClient judge = clientBuilder.build();

        return judge.prompt()
                .system("You are a strict Lucene Query Syntax Validator.")
                .user(
                        u ->
                                u.text(
                                                """
                        TASK: Compare the Actual Query against the Expected Query.

                        EXPECTED (Ground Truth): {e}
                        ACTUAL (Agent Output):   {a}

                        EVALUATION CRITERIA:
                        1. SYNTAX: The Actual Query MUST be valid Lucene syntax.
                           - No unescaped special characters.
                           - Proper field usage (e.g., 'service:"name"').
                        2. ACCURACY: It must target the same fields and values as Expected.

                        Output JSON: \\{ "pass": boolean, "reasoning": "string" \\}
                        """)
                                        .param("e", testCase.expectedLuceneQuery())
                                        .param("a", response.investigationQuery()))
                .call()
                .entity(GradingResult.class);
    }

    private GradingResult calculatePlanFaithfulness(
            EvaluationCase testCase, AnalysisResponse response) {
        ChatClient judge = clientBuilder.build();

        if (response.remediationSteps() == null || response.remediationSteps().isEmpty()) {
            return new GradingResult(false, "FAIL: Agent returned empty remediation steps.");
        }

        // Fixes "Weakness #2": Checks for OMISSION (Recall)
        return judge.prompt()
                .system("You are a QA Lead Auditor.")
                .user(
                        u ->
                                u.text(
                                                """
                        Compare the remediation plans.

                        GROUND TRUTH (Required Steps):
                        {gt}

                        ACTUAL AGENT OUTPUT:
                        {act}

                        STRICT GRADING RULES:
                        1. RECALL: The Agent MUST include ALL steps from the Ground Truth.
                           - If a step is missing -> FAIL.
                        2. HALLUCINATION: The Agent must NOT invent new steps not present in the text.
                           - If new steps are added -> FAIL.
                        3. ORDER: The logical order must be preserved.

                        Does the Actual plan meet all criteria?
                        """)
                                        .param(
                                                "gt",
                                                String.join("\n", testCase.expectedRemediation()))
                                        .param(
                                                "act",
                                                String.join("\n", response.remediationSteps())))
                .call()
                .entity(GradingResult.class);
    }

    private String mapAlertToScenario(String serviceName, String alertHeader) {
        String key = serviceName + "::" + alertHeader;
        return switch (key) {
            case "payment-service::Elevated 5xx Error Rate" -> "payment-500-npe";
            case "payment-service::Upstream Gateway Latency" -> "payment-latency";
            case "inventory-service::Database Connection Timeout" -> "inventory-db-timeout";
            case "inventory-service::Elevated 5xx Error Rate" -> "inventory-stock-mismatch";
            default -> "healthy";
        };
    }

    private record EvaluationReportEntry(
            EvaluationCase testCase,
            AnalysisResponse agentResponse,
            AgentConfig config,
            GradingResult precision,
            GradingResult action,
            GradingResult faithfulness,
            Exception exception // New field for crash tracking
            ) {}
}
