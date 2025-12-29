package com.example.responder.controller;

import com.example.responder.model.AnalysisResponse;
import com.example.responder.model.IncidentRequest;
import com.example.responder.service.SreAgentService;
import com.example.responder.service.SystemStateService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/incident")
public class ResponderController {

    private final SreAgentService agentService;
    private final SystemStateService systemStateService;

    public ResponderController(SreAgentService agentService, SystemStateService systemStateService) {
        this.agentService = agentService;
        this.systemStateService = systemStateService;
    }

    @PostMapping
    public AnalysisResponse analyzeIncident(@RequestBody IncidentRequest request) {
        // In a real app, we would add logging here
        return agentService.analyze(request);
    }

    // NEW: The Chaos Switch
    // POST /api/incident/simulate?service=payment-service&healthy=false
    @PostMapping("/simulate")
    public String setSystemHealth(@RequestParam String service, @RequestParam boolean healthy) {
        systemStateService.setHealth(service, healthy);
        return "Simulated State Update: " + service + " is now " + (healthy ? "HEALTHY" : "BROKEN");
    }
}
