package com.example.responder.service;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class IngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) throws Exception {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:runbooks/*.md");

        for (Resource resource : resources) {
            // 1. Configure Reader
            MarkdownDocumentReaderConfig config =
                    MarkdownDocumentReaderConfig.builder()
                            .withHorizontalRuleCreateDocument(true)
                            .withIncludeCodeBlock(true)
                            .build();

            // 2. Read and Parse
            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
            List<Document> rawDocuments = reader.get();

            // 3. Process Documents (Assign Deterministic IDs)
            List<Document> processedDocuments = new ArrayList<>();
            String serviceKey = resource.getFilename().replace(".md", "").toLowerCase();

            for (int i = 0; i < rawDocuments.size(); i++) {
                Document originalDoc = rawDocuments.get(i);

                // FIX: Use mutate() to create a new Document with a deterministic ID
                // and inject the service_name metadata.
                Document newDoc = originalDoc.mutate()
                        .id(serviceKey + "_chunk_" + i)
                        .metadata("service_name", serviceKey)
                        .build();

                processedDocuments.add(newDoc);
            }

            // 4. Ingest
            vectorStore.accept(processedDocuments);
            log.info(">>> Ingested {} documents for '{}' (Idempotent)", processedDocuments.size(), serviceKey);
        }
        log.info(">>> Global Ingestion Complete!");
    }
}