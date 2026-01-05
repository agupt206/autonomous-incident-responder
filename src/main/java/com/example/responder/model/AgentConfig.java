package com.example.responder.model;

/**
 * Configuration for a specific agent analysis run. Allows A/B testing of retrieval and generation
 * parameters.
 *
 * @param topK Number of documents to retrieve from Vector Store.
 * @param minScore Minimum similarity score (0.0 to 1.0) to qualify as a match.
 * @param temperature LLM generation temperature (0.0 = deterministic, 1.0 = creative).
 * @param strictMetadataFiltering Metadata Filtering flag
 */
public record AgentConfig(
        int topK, double minScore, double temperature, boolean strictMetadataFiltering) {
    public static AgentConfig defaults() {
        return new AgentConfig(2, 0.0, 0.0, false);
    }
}
