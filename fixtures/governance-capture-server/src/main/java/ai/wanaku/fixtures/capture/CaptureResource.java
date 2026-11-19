package ai.wanaku.fixtures.capture;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * REST view over the capture fixture's invocation counters. Independent of the MCP protocol so
 * governance tests can read the counters even when a request was denied at the router and therefore
 * never produced any MCP traffic to this server.
 */
@Path("/capture")
public class CaptureResource {

    @Inject
    CaptureState state;

    @GET
    @Path("/counts")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Integer> counts() {
        return Map.of(
                "toolCalls", state.getToolCalls(),
                "resourceReads", state.getResourceReads(),
                "promptGets", state.getPromptGets());
    }

    @GET
    @Path("/last-tool-call")
    @Produces(MediaType.APPLICATION_JSON)
    public JsonNode lastToolCall() {
        return state.getLastToolCall();
    }

    /** Non-secret observation labels; this endpoint never returns received headers. */
    @GET
    @Path("/authorization")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> authorization() {
        return Map.of("tool", state.getToolAuthorization(), "prompt", state.getPromptAuthorization());
    }
}
