package com.example.responder.tools;

import com.example.responder.service.EmbeddedLogEngine;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElfLogSearchTool
        implements Function<ElfLogSearchTool.Request, ElfLogSearchTool.Response> {

    private static final Logger log = LoggerFactory.getLogger(ElfLogSearchTool.class);
    private final EmbeddedLogEngine searchEngine;

    public ElfLogSearchTool(EmbeddedLogEngine searchEngine) {
        this.searchEngine = searchEngine;
    }

    public record Request(String query, String timeWindow) {}

    public record Response(
            int matchCount,
            List<String> sampleTraceIds,
            List<String> affectedPods,
            String summary) {}

    @Override
    public Response apply(Request request) {
        log.info(">>> TOOL EXECUTION: Searching ELF Logs with query: [{}]", request.query());
        return searchEngine.executeSearch(request.query());
    }
}
