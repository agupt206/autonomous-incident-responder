package com.example.responder.model;

public record IncidentRequest(String serviceName, String issue, String timeWindow // e.g., "1h"
        ) {}
