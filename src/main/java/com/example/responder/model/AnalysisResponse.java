package com.example.responder.model;

import java.util.List;

public record AnalysisResponse(
        String failureType, // e.g., "Network", "Database", "Application"
        String rootCauseHypothesis, // The AI's best guess
        List<String> suggestedSteps, // The strict list of commands from the runbook
        boolean requiresEscalation // True/False flag
        ) {}
