package com.example.responder.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// --- CHANGED IMPORTS ---
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

// -----------------------

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

            // --- CHANGE START: Use MarkdownDocumentReader ---

            // 1. Configure the reader to respect structure
            MarkdownDocumentReaderConfig config =
                    MarkdownDocumentReaderConfig.builder()
                            .withHorizontalRuleCreateDocument(true) // Split by '---' if present
                            .withIncludeCodeBlock(
                                    true) // CRITICAL: Keep Lucene queries inside the text
                            // TODO: uncomment after implementing RAG eval
                            //.withAdditionalMetadata("filename", resource.getFilename())
                            .build();

            // 2. Read and Parse
            // The reader automatically splits based on headers (#, ##) and structure
            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
            List<org.springframework.ai.document.Document> documents = reader.get();

            // 3. Inject Domain Metadata (Service Name)
            String serviceKey = resource.getFilename().replace(".md", "").toLowerCase();
            for (var doc : documents) {
                doc.getMetadata().put("service_name", serviceKey);
            }
            // --- CHANGE END ---

            vectorStore.accept(documents);
            log.info(">>> Ingested {} documents from {}", documents.size(), resource.getFilename());
        }
        log.info(">>> Global Ingestion Complete!");
    }
}
