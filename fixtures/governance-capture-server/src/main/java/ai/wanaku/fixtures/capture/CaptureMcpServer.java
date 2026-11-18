package ai.wanaku.fixtures.capture;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptFilter;
import io.quarkiverse.mcp.server.PromptManager.PromptInfo;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceFilter;
import io.quarkiverse.mcp.server.ResourceManager.ResourceInfo;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.vertx.core.http.HttpServerRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Upstream MCP server used by the governance tests. Each governed operation records that it was
 * actually reached (via {@link CaptureState}), so a test can distinguish "request forwarded to
 * upstream" from "request stopped at the router by governance". The entity names are stable so
 * static action-policy rules can select them by name.
 */
@Singleton
public class CaptureMcpServer implements ToolFilter, ResourceFilter, PromptFilter {

    static final String RESOURCE_URI = "capture://resource/data";

    @Inject
    CaptureState state;

    @Inject
    ObjectMapper mapper;

    @Inject
    HttpServerRequest request;

    private static boolean isolatedCatalog() {
        return Boolean.getBoolean("capture.isolated-catalog");
    }

    @Override
    public boolean test(ToolInfo tool, FilterContext context) {
        return "capture_isolated_tool".equals(tool.name()) == isolatedCatalog();
    }

    @Override
    public boolean test(ResourceInfo resource, FilterContext context) {
        return !isolatedCatalog();
    }

    @Override
    public boolean test(PromptInfo prompt, FilterContext context) {
        return !isolatedCatalog();
    }

    @Tool(name = "capture_isolated_tool", description = "Namespace isolation capture tool")
    String captureIsolatedTool() {
        return "capture_isolated_tool invoked (count=" + state.incrementToolCalls() + ")";
    }

    @Tool(name = "capture_tool", description = "Records the invocation and echoes the payload")
    String captureTool(@ToolArg(description = "Arbitrary payload echoed back") String payload) {
        String authorizationEcho = state.recordToolAuthorization(request.getHeader("Authorization"), isLoopbackRequest());
        int count = state.incrementToolCalls();
        return "capture_tool invoked (count=" + count + ") payload=" + payload + authorizationEcho;
    }

    @Tool(name = "capture_typed_tool", description = "Records arbitrary JSON arguments")
    String captureTypedTool(@ToolArg(description = "Optional JSON value", required = false) Object value)
            throws JsonProcessingException {
        state.incrementToolCalls();
        return mapper.writeValueAsString(value);
    }

    @Tool(name = "capture_star*tool", description = "Literal star name for matcher tests")
    String captureStarTool() {
        return "literal star tool invoked (count=" + state.incrementToolCalls() + ")";
    }

    @Tool(name = "capture_question?tool", description = "Literal question mark name for matcher tests")
    String captureQuestionTool() {
        return "literal question tool invoked (count=" + state.incrementToolCalls() + ")";
    }

    @Resource(uri = RESOURCE_URI, mimeType = "text/plain")
    String captureResource() {
        int count = state.incrementResourceReads();
        return "capture resource read (count=" + count + ")";
    }

    @Resource(uri = "capture://resource/database", mimeType = "text/plain")
    String captureDatabaseResource() {
        return captureResource();
    }

    @Resource(uri = "capture://resource/%64ata", mimeType = "text/plain")
    String captureEncodedResource() {
        return captureResource();
    }

    @Prompt(name = "capture_prompt", description = "Records the invocation and returns a message")
    PromptMessage capturePrompt(@PromptArg(description = "Arbitrary topic") String topic) {
        String authorizationEcho = state.recordPromptAuthorization(request.getHeader("Authorization"), isLoopbackRequest());
        int count = state.incrementPromptGets();
        return PromptMessage.withUserRole("capture_prompt invoked (count=" + count + ") topic=" + topic
                + authorizationEcho);
    }

    private boolean isLoopbackRequest() {
        String remote = request.remoteAddress().hostAddress();
        return "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
    }

    @Prompt(name = "capture_optional_prompt", description = "Records a prompt with optional arguments")
    PromptMessage captureOptionalPrompt(@PromptArg(description = "Optional topic", required = false) String topic) {
        return capturePrompt(topic);
    }
}
