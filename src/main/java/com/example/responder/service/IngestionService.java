package com.example.responder.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document; // Verify import
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;

    // Load the file we just created
    @Value("classpath:runbooks/payment-gateway.txt")
    private Resource runbookResource;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingestRunbooks() {
        log.info(">>> Loading Runbooks into Vector Store...");

        // 1. Read the file
        TextReader textReader = new TextReader(runbookResource);
        List<Document> documents = textReader.get();

        // 2. Split into chunks (AI can't read whole books at once)
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> splitDocuments = splitter.apply(documents);

        // 3. Store in Elasticsearch
        vectorStore.add(splitDocuments);

        log.info(">>> Ingestion Complete! Added {} documents.", splitDocuments.size());
    }
}
