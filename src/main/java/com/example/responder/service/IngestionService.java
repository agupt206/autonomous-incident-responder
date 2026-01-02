package com.example.responder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver; // Import 1
import org.springframework.core.io.support.ResourcePatternResolver; // Import 2
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
        // 1. Use a Pattern Resolver to find ALL .txt files
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:runbooks/*.txt");

        for (Resource resource : resources) {
            log.info(">>> Processing Runbook: {}", resource.getFilename());

            // 2. Read and Split
            var textReader = new TextReader(resource);
            var splitter = new TokenTextSplitter();
            var documents = splitter.apply(textReader.get());

            // 3. Store in Elasticsearch
            vectorStore.accept(documents);
            log.info(">>> Ingested {} documents from {}", documents.size(), resource.getFilename());
        }
        log.info(">>> Global Ingestion Complete!");
    }
}
