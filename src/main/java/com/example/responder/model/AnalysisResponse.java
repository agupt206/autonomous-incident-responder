package com.example.responder.model;

import java.util.List;
import java.util.Map;

public record AnalysisResponse(
        String failureType,
        String rootCauseHypothesis,
        String investigationQuery,      // The Lucene query extracted from the runbook
        Map<String, Object> evidence,   // Structured data (TraceIDs, counts)
        String responsibleTeam,
        List<String> remainingSteps,
        boolean requiresEscalation
) {}