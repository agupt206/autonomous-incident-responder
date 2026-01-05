package com.example.responder.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Component
public class IngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info(">>> STARTING MANUAL INGESTION...");

        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:runbooks/*.md");

        for (Resource resource : resources) {
            String serviceKey = resource.getFilename().replace(".md", "").toLowerCase();

            // 1. Read content as raw string
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

            // 2. Manual Split by Horizontal Rule (--- or ----)
            // This guarantees we only split where YOU decided to split in the markdown
            String[] rawChunks = content.split("(?m)^-{3,}");

            List<Document> processedDocuments = new ArrayList<>();

            for (int i = 0; i < rawChunks.length; i++) {
                String chunkText = rawChunks[i].trim();
                if (chunkText.isEmpty()) continue;

                // 3. Create Document with Deterministic ID
                // "service-name_alert-index"
                Document doc = new Document(chunkText);
                Document finalDoc = doc.mutate()
                        .id(serviceKey + "_alert_" + i)
                        .metadata("service_name", serviceKey)
                        .build();

                processedDocuments.add(finalDoc);
            }

            // 4. Ingest
            vectorStore.accept(processedDocuments);
            log.info(">>> Ingested {} alerts for '{}' (Manual Split)", processedDocuments.size(), serviceKey);
        }
        log.info(">>> Global Ingestion Complete!");
    }
}