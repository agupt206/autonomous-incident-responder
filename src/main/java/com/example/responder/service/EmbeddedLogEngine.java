package com.example.responder.service;

import com.example.responder.tools.ElfLogSearchTool;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmbeddedLogEngine {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedLogEngine.class);
    private Directory memoryIndex;
    private WhitespaceAnalyzer analyzer;

    @PostConstruct
    public void init() throws IOException {
        memoryIndex = new ByteBuffersDirectory();
        analyzer = new WhitespaceAnalyzer();
        // Start with a healthy state
        loadScenario("healthy");
    }

    /** Wipes the existing index and seeds new data based on the requested simulation scenario. */
    public void loadScenario(String scenarioName) {
        log.info(">>> SIMULATION: Switching Log Engine to Scenario: '{}'", scenarioName);
        try (IndexWriter writer = new IndexWriter(memoryIndex, new IndexWriterConfig(analyzer))) {
            writer.deleteAll();
            switch (scenarioName.toLowerCase()) {
                case "healthy" -> seedHealthy(writer);
                case "payment-500-npe" -> seedPaymentNPE(writer);
                case "payment-latency" -> seedPaymentLatency(writer);
                case "inventory-db-timeout" -> seedInventoryDbTimeout(writer);
                case "inventory-stock-mismatch" -> seedInventoryStockMismatch(writer);

                    // NEW SCENARIOS
                case "inventory-cache-inconsistency" -> seedInventoryCacheInconsistency(writer);
                case "payment-gateway-timeout" -> seedPaymentGatewayTimeout(writer);

                default -> log.warn("Unknown scenario '{}', leaving index empty.", scenarioName);
            }
            writer.commit();
        } catch (IOException e) {
            log.error("Failed to load scenario", e);
        }
    }

    // --- Scenario Data Factories ---

    private void seedHealthy(IndexWriter w) throws IOException {
        addLog(
                w,
                "payment-service",
                "INFO",
                "200",
                "opentracing-log",
                "tx-ok-1",
                "Payment processed successfully");
        addLog(
                w,
                "inventory-service",
                "INFO",
                "200",
                "opentracing-log",
                "tx-ok-2",
                "Stock updated");
    }

    private void seedInventoryCacheInconsistency(IndexWriter w) throws IOException {
        // Goal: DB is fine (UP), but app is complaining about cache misses or stale data
        // Query: service:"inventory-service" AND log.message:"Cache key miss" AND db.status:"UP"
        for (int i = 0; i < 20; i++) {
            Document doc = new Document();
            doc.add(new TextField("application.name", "inventory-service", Field.Store.YES));
            doc.add(
                    new TextField(
                            "log.message",
                            "WARN: Cache key miss for SKU-999. Fetching from DB.",
                            Field.Store.YES));
            doc.add(
                    new StringField(
                            "db.status", "UP", Field.Store.YES)); // Explicit field for the query
            doc.add(new StoredField("trace_id", "cache-miss-" + i));
            w.addDocument(doc);
        }
    }

    private void seedPaymentGatewayTimeout(IndexWriter w) throws IOException {
        // Goal: 504 errors and high latency
        // Query: service:"payment-service" AND status_code:504 AND metric:latency > 5000
        for (int i = 0; i < 15; i++) {
            Document doc = new Document();
            doc.add(new TextField("application.name", "payment-service", Field.Store.YES));
            doc.add(new TextField("status_code", "504", Field.Store.YES));
            doc.add(new StringField("metric", "latency", Field.Store.YES));
            // Lucene range query logic usually requires IntPoint, but here we simulate text match
            // for the demo
            // Or explicitly set value > 5000 as requested by the text parser logic
            doc.add(new StringField("value", "6500", Field.Store.YES));
            doc.add(
                    new TextField(
                            "log.message",
                            "Gateway Timeout awaiting upstream response",
                            Field.Store.YES));
            doc.add(new StoredField("trace_id", "gw-timeout-" + i));
            w.addDocument(doc);
        }
    }

    private void seedPaymentNPE(IndexWriter w) throws IOException {
        // Matches: status_code:[500 TO 599] AND log.level:ERROR
        for (int i = 0; i < 50; i++) {
            addLog(
                    w,
                    "payment-service",
                    "ERROR",
                    "500",
                    "opentracing-log",
                    "trace-npe-" + i,
                    "java.lang.NullPointerException at"
                            + " com.example.payment.Processor.process(Processor.java:42)");
        }
    }

    private void seedPaymentLatency(IndexWriter w) throws IOException {
        // Matches: metric:latency AND value > 2000
        // Note: Lucene text fields require careful handling for range queries on numbers.
        // For simplicity in this demo, we treat specific keywords or just add a log message field.
        for (int i = 0; i < 20; i++) {
            Document doc = new Document();
            doc.add(new TextField("application.name", "payment-service", Field.Store.YES));
            doc.add(new StringField("metric", "latency", Field.Store.YES));
            doc.add(
                    new StringField(
                            "value",
                            "5000",
                            Field.Store.YES)); // Simple string match for simulation
            doc.add(new StoredField("trace_id", "slow-tx-" + i));
            w.addDocument(doc);
        }
    }

    private void seedInventoryDbTimeout(IndexWriter w) throws IOException {
        // Matches: log.message:"Connection check failed" AND db.type:postgres
        for (int i = 0; i < 15; i++) {
            Document doc = new Document();
            doc.add(new TextField("application.name", "inventory-service", Field.Store.YES));
            doc.add(new StringField("db.type", "postgres", Field.Store.YES));
            doc.add(
                    new TextField(
                            "log.message",
                            "Connection check failed. HikariPool-1 - Connection is not available",
                            Field.Store.YES));
            doc.add(new StoredField("trace_id", "db-err-" + i));
            w.addDocument(doc);
        }
    }

    private void seedInventoryStockMismatch(IndexWriter w) throws IOException {
        // Matches: status_code:[500 TO 599] ... and specifically "StockCountMismatch"
        for (int i = 0; i < 5; i++) {
            addLog(
                    w,
                    "inventory-service",
                    "ERROR",
                    "500",
                    "opentracing-log",
                    "stock-err-" + i,
                    "CRITICAL: StockCountMismatchException: SKU-123 expected 5 but found 3");
        }
    }

    private void addLog(
            IndexWriter w,
            String app,
            String level,
            String status,
            String type,
            String traceId,
            String message)
            throws IOException {
        Document doc = new Document();
        doc.add(new TextField("application.name", app, Field.Store.YES));
        doc.add(new StringField("log.level", level, Field.Store.YES));
        doc.add(new StringField("type", type, Field.Store.YES));
        doc.add(new TextField("status_code", status, Field.Store.YES));
        doc.add(new TextField("log.message", message, Field.Store.YES)); // Added message field
        doc.add(new StoredField("trace_id", traceId));
        w.addDocument(doc);
    }

    public ElfLogSearchTool.Response executeSearch(String queryString) {
        try {
            // Always open a fresh reader to see the latest writes
            try (IndexReader reader = DirectoryReader.open(memoryIndex)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                QueryParser parser = new QueryParser("log.message", analyzer); // Default field

                Query query = parser.parse(queryString);
                TopDocs docs = searcher.search(query, 10);

                List<String> traceIds = new ArrayList<>();
                for (ScoreDoc scoreDoc : docs.scoreDocs) {
                    Document d = searcher.doc(scoreDoc.doc);
                    traceIds.add(d.get("trace_id"));
                }

                return new ElfLogSearchTool.Response(
                        (int) docs.totalHits.value,
                        traceIds,
                        List.of("simulated-pod-1", "simulated-pod-2"),
                        "Found " + docs.totalHits.value + " matches for query: " + queryString);
            }
        } catch (Exception e) {
            log.error("Lucene Query Failed", e);
            return new ElfLogSearchTool.Response(
                    0, List.of(), List.of(), "Query Error: " + e.getMessage());
        }
    }
}
