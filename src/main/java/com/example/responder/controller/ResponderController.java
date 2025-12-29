package com.example.responder.controller;

import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import com.example.responder.service.SreAgentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/incident")
public class ResponderController {

    private final SreAgentService agentService;

    public ResponderController(SreAgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public AnalysisResponse analyzeIncident(@RequestBody IncidentRequest request) {
        // In a real app, we would add logging here
        return agentService.analyze(request);
    }
}
