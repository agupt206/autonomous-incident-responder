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

    // Thread-safe list to hold results for the final report
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

    // --- 2. MAIN TEST LOOP ---

    @ParameterizedTest
    @MethodSource("provideGoldenData")
    void evaluateAgentQuality(EvaluationCase testCase) {
        System.out.println(">>> Executing Test Case: " + testCase.id());

        // Config: TopK=5 ensures we get all alerts for the service.
        var config = new AgentConfig(5, 0.0, 0.0, true);

        AnalysisResponse response = null;
        GradingResult actionResult = new GradingResult(false, "N/A - Test Crashed");
        GradingResult planResult = new GradingResult(false, "N/A - Test Crashed");
        GradingResult precisionResult = new GradingResult(false, "N/A - Test Crashed");
        Exception capturedException = null;

        try {
            // Load Scenario into Log Engine (Mocking the environment)
            String scenarioId =
                    mapAlertToScenario(testCase.serviceName(), testCase.expectedAlertHeader());
            logEngine.loadScenario(scenarioId);

            // 1. EXECUTE AGENT
            response =
                    agent.analyze(
                            new IncidentRequest(testCase.serviceName(), testCase.userIssue(), "1h"),
                            config);

            // 2. ROBUST EVALUATION (LLM-as-a-Judge)
            actionResult =
                    evaluateActionSemantics(
                            testCase.expectedLuceneQuery(), response.investigationQuery());
            planResult =
                    evaluatePlanRecall(testCase.expectedRemediation(), response.remediationSteps());
            precisionResult = calculateRetrievalPrecision(testCase, response);

        } catch (Exception e) {
            capturedException = e;
            e.printStackTrace();
        } finally {
            // Capture result for the report
            reportEntries.add(
                    new EvaluationReportEntry(
                            testCase,
                            response,
                            config,
                            precisionResult,
                            actionResult,
                            planResult,
                            capturedException));
        }

        if (capturedException != null) {
            Assertions.fail("Test Failed with Exception: " + capturedException.getMessage());
        }

        // Final Assertions for JUnit
        GradingResult finalAction = actionResult;
        GradingResult finalPlan = planResult;
        GradingResult finalPrecision = precisionResult;

        Assertions.assertAll(
                "Agent Performance Metrics",
                () ->
                        Assertions.assertTrue(
                                finalAction.pass(), "Action Fail: " + finalAction.reasoning()),
                () ->
                        Assertions.assertTrue(
                                finalPlan.pass(), "Plan Fail: " + finalPlan.reasoning()),
                () ->
                        Assertions.assertTrue(
                                finalPrecision.pass(),
                                "Retrieval Fail: " + finalPrecision.reasoning()));
    }

    // --- 3. ROBUST METRICS (LLM-as-a-Judge) ---

    private GradingResult evaluateActionSemantics(String expectedQuery, String actualQuery) {
        if (actualQuery == null || actualQuery.isBlank()) {
            return new GradingResult(false, "Actual query is empty");
        }

        ChatClient judge = clientBuilder.build();

        // FIX: Define the JSON format as a variable to avoid template parsing errors
        String jsonFormat = "{ \"pass\": boolean, \"reasoning\": \"string\" }";

        return judge.prompt()
                .system(
                        "You are a Search Query Syntax Expert. Compare two queries for SEMANTIC"
                                + " equivalence.")
                .user(
                        u ->
                                u.text(
                                                """
                        EXPECTED QUERY: {expected}
                        ACTUAL QUERY:   {actual}

                        TASK:
                        Do these two queries retrieve the same dataset?
                        - Ignore differences in whitespace or quoting style (e.g. ' vs ").
                        - Ignore field aliases IF they are common conventions (e.g. 'app' vs 'service').
                        - The LOGIC (AND/OR) and VALUES must match.

                        Respond with valid JSON: {jsonFormat}
                        """)
                                        .param("expected", expectedQuery)
                                        .param("actual", actualQuery)
                                        .param("jsonFormat", jsonFormat)) // Pass the schema here
                .call()
                .entity(GradingResult.class);
    }

    private GradingResult evaluatePlanRecall(List<String> expectedSteps, List<String> actualSteps) {
        if (actualSteps == null || actualSteps.isEmpty()) {
            return new GradingResult(false, "Actual plan is empty");
        }

        String expectedStr = String.join("\n", expectedSteps);
        String actualStr = String.join("\n", actualSteps);

        // FIX: Define the JSON format as a variable
        String jsonFormat = "{ \"pass\": boolean, \"reasoning\": \"string\" }";

        ChatClient judge = clientBuilder.build();
        return judge.prompt()
                .system(
                        "You are a Senior QA Auditor. Verify that the remediation plan covers all"
                                + " required actions.")
                .user(
                        u ->
                                u.text(
                                                """
                        GROUND TRUTH STEPS:
                        {expected}

                        AGENT GENERATED STEPS:
                        {actual}

                        TASK:
                        Calculate the "Recall" of key facts.
                        1. Does the Agent's plan include ALL critical actions mentioned in the Ground Truth?
                        2. It is acceptable if the Agent rephrases steps, provided the meaning is preserved.
                        3. Fail ONLY if a critical step (e.g. "Flush Redis", "Restart Pod") is completely missing or wrong.

                        Respond with valid JSON: {jsonFormat}
                        """)
                                        .param("expected", expectedStr)
                                        .param("actual", actualStr)
                                        .param("jsonFormat", jsonFormat)) // Pass the schema here
                .call()
                .entity(GradingResult.class);
    }

    private GradingResult calculateRetrievalPrecision(
            EvaluationCase testCase, AnalysisResponse response) {
        if (response.citations() == null || response.citations().isEmpty()) {
            return new GradingResult(false, "FAIL: No documents retrieved.");
        }
        boolean allSourcesMatch =
                response.citations().stream()
                        .allMatch(source -> source.equalsIgnoreCase(testCase.serviceName()));

        if (allSourcesMatch) {
            return new GradingResult(
                    true, "PASS: All retrieved fragments belong to " + testCase.serviceName());
        } else {
            return new GradingResult(
                    false, "FAIL: Context Pollution Detected. Retrieved: " + response.citations());
        }
    }

    private String mapAlertToScenario(String serviceName, String alertHeader) {
        String key = serviceName + "::" + alertHeader;
        if (key.contains("Connection Timeout")) return "inventory-db-timeout";
        if (key.contains("5xx Error Rate") && serviceName.contains("inventory"))
            return "inventory-stock-mismatch";
        if (key.contains("5xx Error Rate") && serviceName.contains("payment"))
            return "payment-500-npe";
        if (key.contains("Gateway Latency")) return "payment-latency";
        return "healthy";
    }

    // --- 4. REPORTING LOGIC (Restored) ---

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

    private String formatReportEntry(EvaluationReportEntry entry) {
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
            if (ar != null) {
                rawJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ar);
            }
        } catch (Exception ignored) {
        }

        // Handle cases where response might be null due to crash
        String actualQuery = (ar != null) ? ar.investigationQuery() : "NULL";
        String evidence =
                (ar != null && ar.evidence() != null) ? ar.evidence().keySet().toString() : "NONE";
        String remediation = (ar != null) ? String.valueOf(ar.remediationSteps()) : "NULL";

        return """
               === TEST CASE: %s ===
               SERVICE: %s
               SCENARIO: %s

               [INPUT]
               User Query: %s

               [EXPECTED]
               Lucene Query: %s
               Remediation: %s

               [ACTUAL]
               Lucene Query: %s
               Evidence Keys: %s
               Remediation: %s

               [METRICS]
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
                        tc.userIssue(),
                        tc.expectedLuceneQuery(),
                        tc.expectedRemediation(),
                        actualQuery,
                        evidence,
                        remediation,
                        entry.precision().pass() ? "PASS" : "FAIL",
                        entry.precision().reasoning(),
                        entry.action().pass() ? "PASS" : "FAIL",
                        entry.action().reasoning(),
                        entry.faithfulness().pass() ? "PASS" : "FAIL",
                        entry.faithfulness().reasoning(),
                        crashInfo,
                        rawJson);
    }

    private record EvaluationReportEntry(
            EvaluationCase testCase,
            AnalysisResponse agentResponse,
            AgentConfig config,
            GradingResult precision,
            GradingResult action,
            GradingResult faithfulness,
            Exception exception) {}
}
