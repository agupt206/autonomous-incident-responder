package com.example.responder.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    // 1. Create the low-level connection to Docker
    @Bean
    public RestClient restClient() {
        return RestClient.builder(new HttpHost("localhost", 9200, "http")).build();
    }

    // 2. Create the VectorStore Bean
    @Bean
    public VectorStore vectorStore(RestClient restClient, EmbeddingModel embeddingModel) {

        var options = new ElasticsearchVectorStoreOptions();
        options.setIndexName("es-runbk-local");
        options.setDimensions(384);

        // Options: Define index name and force creation if missing
        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
    }
}
