package com.example.responder.service;

import com.example.responder.tools.ElfLogSearchTool; // We will create this in Step 5
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

        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(analyzer);
        IndexWriter writer = new IndexWriter(memoryIndex, indexWriterConfig);

        // Seed Data: 1 Healthy Log
        addLog(writer, "payment-service", "INFO", "200", "opentracing-log", "tx-ok-1", "Transaction processed successfully");

        // Seed Data: 3 Broken Logs (Matching the Runbook criteria)
        addLog(writer, "payment-service", "ERROR", "500", "opentracing-log", "elf-700-1", "Connection timed out");
        addLog(writer, "payment-service", "ERROR", "502", "opentracing-log", "elf-700-2", "Bad Gateway");
        //addLog(writer, "payment-service", "ERROR", "500", "opentracing-log", "elf-700-3", "NullPointerException in processing");
        addLog(writer, "payment-service", "ERROR", "500", "opentracing-log", "elf-700-4", "PaymentDeclinedException: Insufficient funds");

        // addLog(writer, "inventory-service", "ERROR", "500", "opentracing-log", "elf-888");

        writer.close();
        log.info(">>> Embedded Lucene Index initialized with seed data.");
    }

    private void addLog(
            IndexWriter w, String app, String level, String status, String type, String traceId, String message)
            throws IOException {
        Document doc = new Document();
        // TextField = analyzed (good for text), StringField = exact match only
        doc.add(new TextField("application.name", app, Field.Store.YES));
        doc.add(new StringField("log.level", level, Field.Store.YES));
        doc.add(new StringField("type", type, Field.Store.YES));
        doc.add(new TextField("status_code", status, Field.Store.YES)); // For range queries
        doc.add(new StoredField("trace_id", traceId));
        doc.add(new TextField("message", message, Field.Store.YES));
        w.addDocument(doc);
    }

    public ElfLogSearchTool.Response executeSearch(String queryString) {
        try {
            IndexReader reader = DirectoryReader.open(memoryIndex);
            IndexSearcher searcher = new IndexSearcher(reader);

            // Allow searching on the "type" field by default
            QueryParser parser = new QueryParser("type", analyzer);
            Query query = parser.parse(queryString);

            TopDocs docs = searcher.search(query, 10); // Get top 10

            List<String> traceIds = new ArrayList<>();
            for (ScoreDoc scoreDoc : docs.scoreDocs) {
                Document d = searcher.doc(scoreDoc.doc);
                traceIds.add(d.get("trace_id"));
            }

            return new ElfLogSearchTool.Response(
                    (int) docs.totalHits.value,
                    traceIds,
                    List.of("payment-pod-a", "payment-pod-b"), // Simulated pod names
                    "Search successful. Found " + docs.totalHits.value + " matches.");

        } catch (Exception e) {
            log.error("Lucene Query Failed", e);
            return new ElfLogSearchTool.Response(
                    0, List.of(), List.of(), "Invalid Query Syntax: " + e.getMessage());
        }
    }
}
