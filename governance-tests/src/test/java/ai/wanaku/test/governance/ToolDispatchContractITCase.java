package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.RawMcpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

/** Establishes the ordinary dispatch contract that WASM handler dispatch must also preserve (#333). */
@Timeout(180)
class ToolDispatchContractITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "dispatch-contract";
    private static final String STRUCTURED_TOOL = "capture_structured_tool";
    private static final String ERROR_TOOL = "capture_error_tool";

    @BeforeEach
    void startGovernedInfrastructure() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
    }

    @Test
    @DisplayName("Dispatch preserves typed structured results and ordered MCP content")
    void preservesStructuredResults() throws Exception {
        try (RawMcpClient client = new RawMcpClient(server.getMcpBaseUrl() + "/" + NAMESPACE + "/mcp")) {
            client.initialize();
            int calls = 0;
            for (String json : List.of(
                    "0",
                    "\"0\"",
                    "false",
                    "\"\"",
                    "[]",
                    "{}",
                    "[null,false,0,\"0\"]",
                    "{\"nested\":{\"flag\":false}}")) {
                JsonNode value = MAPPER.readTree(json);
                JsonNode response = call(client, STRUCTURED_TOOL, value, "structured-" + calls);
                assertThat(response.has("error")).as("Response: %s", response).isFalse();
                JsonNode result = response.path("result");
                assertThat(result.path("isError").asBoolean()).isFalse();
                assertThat(result.get("structuredContent"))
                        .isEqualTo(MAPPER.createObjectNode().set("value", value));
                assertThat(result.path("content").size()).isEqualTo(2);
                assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
                assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("capture structured result");
                assertThat(result.path("content").get(1).path("type").asText()).isEqualTo("text");
                assertThat(result.path("content").get(1).path("text").asText())
                        .isEqualTo("capture structured result end");
                ObjectNode expectedCall = MAPPER.createObjectNode().put("name", STRUCTURED_TOOL);
                expectedCall.putObject("arguments").set("value", value);
                assertThat(lastToolCall()).isEqualTo(expectedCall);
                assertThat(captureCounts().toolCalls()).isEqualTo(++calls);
            }
        }
    }

    @Test
    @DisplayName("Tool execution errors remain MCP results and differ from protocol errors")
    void distinguishesExecutionAndProtocolErrors() throws Exception {
        try (RawMcpClient client = new RawMcpClient(server.getMcpBaseUrl() + "/" + NAMESPACE + "/mcp")) {
            client.initialize();
            JsonNode execution = call(client, ERROR_TOOL, null, "tool-error");
            assertThat(execution.has("error")).isFalse();
            assertThat(execution.path("result").path("isError").asBoolean()).isTrue();
            assertThat(execution
                            .path("result")
                            .path("content")
                            .get(0)
                            .path("text")
                            .asText())
                    .isEqualTo("capture tool execution failed");
            assertThat(captureCounts().toolCalls()).isEqualTo(1);

            JsonNode protocol = call(client, "missing_dispatch_target", null, "protocol-error");
            assertThat(protocol.has("result")).isFalse();
            assertThat(protocol.path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(captureCounts().toolCalls()).isEqualTo(1);

            JsonNode recovery = call(client, STRUCTURED_TOOL, MAPPER.valueToTree("recovery"), "recovery");
            assertThat(recovery.has("error")).isFalse();
            assertThat(recovery.path("result")
                            .path("structuredContent")
                            .path("value")
                            .asText())
                    .isEqualTo("recovery");
            assertThat(captureCounts().toolCalls()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Target denial prevents execution and does not replace captured arguments")
    void deniedTargetDoesNotExecute() throws Exception {
        try (RawMcpClient client = new RawMcpClient(server.getMcpBaseUrl() + "/" + NAMESPACE + "/mcp");
                ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl())) {
            client.initialize();
            JsonNode allowed = call(client, STRUCTURED_TOOL, MAPPER.valueToTree("allowed"), "allowed");
            assertThat(allowed.has("error")).isFalse();
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
            JsonNode before = lastToolCall();

            ObjectNode deny = GovernancePolicies.denyTool("deny-dispatch-target", STRUCTURED_TOOL, "Target denied.");
            var activation = policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy(deny)));
            assertThat(activation.statusCode())
                    .as("Policy activation: %s", activation.body())
                    .isEqualTo(200);
            JsonNode denied = call(client, STRUCTURED_TOOL, MAPPER.valueToTree("must-not-arrive"), "denied");
            assertThat(denied.has("result")).isFalse();
            assertThat(denied.path("error").path("code").asInt()).isEqualTo(-32003);
            assertThat(denied.path("error").path("message").asText()).contains("Target denied.");
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
            assertThat(lastToolCall()).isEqualTo(before);
        }
    }

    private static JsonNode call(RawMcpClient client, String tool, JsonNode value, String id) throws Exception {
        ObjectNode request =
                MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call");
        ObjectNode params = request.putObject("params").put("name", tool);
        if (value != null) {
            params.putObject("arguments").set("value", value);
        }
        HttpResponse<String> response = client.send(request.toString());
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        JsonNode envelope = MAPPER.readTree(response.body());
        assertThat(envelope.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(envelope.get("id")).isEqualTo(MAPPER.valueToTree(id));
        return envelope;
    }

    private JsonNode lastToolCall() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(captureServer.getBaseUrl() + "/capture/last-tool-call"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as("Capture response: %s", response.body())
                    .isEqualTo(200);
            return MAPPER.readTree(response.body());
        }
    }
}
