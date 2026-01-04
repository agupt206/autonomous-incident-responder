package com.example.responder.eval;

import java.util.List;

public record EvaluationCase(
        String id,
        String serviceName,
        String userIssue,
        String expectedAlertHeader, // The ground truth failure type
        String expectedLuceneQuery, // The ground truth query
        List<String> expectedRemediation // The ground truth remediation steps
        ) {}
