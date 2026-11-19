package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.RawMcpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Real-process coverage of wanaku:tool-handler@1.0.0 and governed host dispatch (#333). */
@Timeout(180)
class WasmToolHandlerITCase extends GovernanceTestBase {
    private static final String NAMESPACE = "wasm-handler";
    private static final String HANDLERS = "/api/v1/tool-handlers";
    private static final String TARGET = "capture_structured_tool";

    @TempDir
    Path components;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void startHandlerServer() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        HttpResponse<String> response = management("GET", HANDLERS, null);
        if (response.statusCode() == 404) {
            LOG.warn(
                    "WASM tool-handler management is unavailable in server binary {}",
                    baseConfig.getServerBinaryPath());
            assumeThat(response.statusCode())
                    .as("Server must support WASM tool handlers")
                    .isNotEqualTo(404);
        }
        assertStatus(response, 200);
        registerCaptureForward(NAMESPACE);
    }

    @AfterEach
    void closeHttpClient() {
        http.close();
    }

    @Test
    void returnsCompleteResultsAndListsRuntimeTools() throws Exception {
        JsonNode expected = MAPPER.readTree(
                """
                {"content":[{"type":"text","text":"héllo"},{"type":"image","data":"AA==","mimeType":"image/png"}],
                 "structuredContent":{"value":[true,0,null,"0"]},"isError":false,"_meta":{"fixture":true}}
                """);
        ObjectNode handler = definition("result-handler", WasmHandlerFixtures.result(components, "result", expected));
        handler.withObject("/tool").put("title", "Runtime result");
        activate(handler);
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            JsonNode listed = tools(client);
            assertThat(listed).anySatisfy(tool -> {
                assertThat(tool.path("name").asText()).isEqualTo("result-handler");
                assertThat(tool.path("title").asText()).isEqualTo("Runtime result");
                assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
            });
            assertThat(success(call(client, "result-handler", MAPPER.createObjectNode())))
                    .isEqualTo(expected);
            assertThat(captureCounts().toolCalls()).isZero();
        }
    }

    @Test
    void dispatchPreservesTypedArgumentsAndToolErrors() throws Exception {
        ObjectNode proxy = proxy("proxy", NAMESPACE, TARGET);
        ObjectNode errorProxy = proxy("error-proxy", NAMESPACE, "capture_error_tool");
        activate(proxy, errorProxy);
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            JsonNode arguments = MAPPER.readTree("{\"value\":{\"nested\":[true,0,null,\"0\",{},[]]}}");
            JsonNode result = success(call(client, "proxy", arguments));
            assertThat(result.get("structuredContent")).isEqualTo(arguments);
            assertThat(result.path("content").size()).isEqualTo(2);
            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("capture structured result");
            assertThat(result.path("content").get(1).path("text").asText()).isEqualTo("capture structured result end");
            HttpResponse<String> captured = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(captureServer.getBaseUrl() + "/capture/last-tool-call"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertStatus(captured, 200);
            assertThat(MAPPER.readTree(captured.body()).get("arguments")).isEqualTo(arguments);
            JsonNode error = success(call(client, "error-proxy", MAPPER.createObjectNode()));
            assertThat(error.path("isError").asBoolean()).isTrue();
            assertThat(error.path("content").get(0).path("text").asText()).isEqualTo("capture tool execution failed");
            assertThat(captureCounts().toolCalls()).isEqualTo(2);
        }
    }

    @Test
    void targetPolicyDenialPreventsInvocation() throws Exception {
        activate(proxy("proxy", NAMESPACE, TARGET));
        try (ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl());
                AuditClient audit = new AuditClient(server.getBaseUrl(), null);
                RawMcpClient client = connectRaw(NAMESPACE)) {
            var policy = GovernancePolicies.denyTool("deny-target", TARGET, "WASM target denied.");
            assertThat(policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy(policy)))
                            .statusCode())
                    .isEqualTo(200);
            JsonNode response = call(client, "proxy", MAPPER.readTree("{\"value\":true}"));
            assertError(response, -32003, "denied");
            assertThat(response.path("error").path("message").asText()).contains("WASM target denied.");
            assertThat(captureCounts().toolCalls()).isZero();
            assertThat(audit.listEvents(Map.of("namespace", NAMESPACE, "target", TARGET))
                            .path("events"))
                    .anySatisfy(event -> {
                        assertThat(event.path("target").asText()).isEqualTo(TARGET);
                        assertThat(event.path("decision").asText()).isEqualTo("block");
                        JsonNode ancestry = event.path("attributes").path("handler_ancestry");
                        assertThat(ancestry).hasSize(1);
                        assertThat(ancestry.get(0).path("namespace").asText()).isEqualTo(NAMESPACE);
                        assertThat(ancestry.get(0).path("name").asText()).isEqualTo("proxy");
                        assertThat(ancestry.get(0).path("binding_id").asText()).isNotBlank();
                    });
        }
    }

    @Test
    void targetValidationPreventsInvocation() throws Exception {
        activate(proxy("proxy", NAMESPACE, "capture_tool"));
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            JsonNode response = call(client, "proxy", MAPPER.readTree("{\"payload\":false}"));
            assertError(response, -32602, "invalid_arguments");
            assertThat(captureCounts().toolCalls()).isZero();
            JsonNode recovered = success(call(client, "proxy", MAPPER.readTree("{\"payload\":\"valid\"}")));
            assertThat(recovered.path("content").get(0).path("text").asText()).contains("payload=valid");
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
        }
    }

    @Test
    void scopedCatalogPreservesIdentitySchemaAndProvenance() throws Exception {
        String otherNamespace = "wasm-other";
        registerCaptureForward(otherNamespace);
        ObjectNode first = definition(
                "inspect", WasmHandlerFixtures.host(components, "catalog-first", "catalog", NAMESPACE, TARGET));
        ObjectNode second = definition(
                "inspect", WasmHandlerFixtures.host(components, "catalog-second", "catalog", otherNamespace, TARGET));
        second.withObject("/tool").put("namespace", otherNamespace);
        second.putArray("allowed_namespaces").add(otherNamespace);
        activate(first, second);
        for (String namespace : List.of(NAMESPACE, otherNamespace)) {
            try (RawMcpClient client = connectRaw(namespace)) {
                JsonNode catalog = success(call(client, "inspect", MAPPER.createObjectNode()))
                        .path("structuredContent");
                assertThat(catalog.path("identity").path("namespace").asText()).isEqualTo(namespace);
                assertThat(catalog.path("identity").path("name").asText()).isEqualTo(TARGET);
                assertThat(catalog.path("forward_id").asText()).isEqualTo("capture-fwd-" + namespace);
                assertThat(catalog.path("definition").path("name").asText()).isEqualTo(TARGET);
                assertThat(catalog.path("definition")
                                .path("inputSchema")
                                .path("type")
                                .asText())
                        .isEqualTo("object");
                assertThat(catalog.path("definition")
                                .path("inputSchema")
                                .path("properties")
                                .has("value"))
                        .isTrue();
                assertThat(tools(client)).anySatisfy(tool -> {
                    assertThat(tool.path("name").asText()).isEqualTo(TARGET);
                    assertThat(catalog.path("definition").get("inputSchema")).isEqualTo(tool.get("inputSchema"));
                });
            }
        }
        assertThat(captureCounts().toolCalls()).isZero();
    }

    @Test
    void replacementRemovalAndRestartPreserveLifecycle() throws Exception {
        JsonNode firstResult = MAPPER.readTree("{\"content\":[],\"structuredContent\":{\"version\":1}}");
        ObjectNode initial = definition("lifecycle", WasmHandlerFixtures.result(components, "first", firstResult));
        activate(initial);
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            assertThat(success(call(client, "lifecycle", MAPPER.createObjectNode())))
                    .isEqualTo(firstResult);
            ObjectNode invalid = initial.deepCopy()
                    .put("path", components.resolve("absent.wasm").toString());
            assertStatus(management("PUT", HANDLERS, MAPPER.createArrayNode().add(invalid)), 400);
            assertThat(success(call(client, "lifecycle", MAPPER.createObjectNode())))
                    .isEqualTo(firstResult);
            JsonNode secondResult = MAPPER.readTree("{\"content\":[],\"structuredContent\":{\"version\":2}}");
            activate(definition("lifecycle", WasmHandlerFixtures.result(components, "second", secondResult)));
            assertThat(success(call(client, "lifecycle", MAPPER.createObjectNode())))
                    .isEqualTo(secondResult);
        }
        server.stopPreservingState();
        server.start(getClass().getSimpleName());
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            assertThat(success(call(client, "lifecycle", MAPPER.createObjectNode()))
                            .path("structuredContent")
                            .path("version")
                            .asInt())
                    .isEqualTo(2);
            assertStatus(management("GET", HANDLERS + "/" + NAMESPACE + "/lifecycle", null), 200);
            assertStatus(management("DELETE", HANDLERS + "/" + NAMESPACE + "/lifecycle", null), 200);
            assertStatus(management("GET", HANDLERS + "/" + NAMESPACE + "/lifecycle", null), 404);
            JsonNode removed = call(client, "lifecycle", MAPPER.createObjectNode());
            assertThat(removed.has("result")).isFalse();
            assertThat(removed.path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(tools(client))
                    .noneMatch(tool -> "lifecycle".equals(tool.path("name").asText()));
        }
        server.stopPreservingState();
        server.start(getClass().getSimpleName());
        assertStatus(management("GET", HANDLERS + "/" + NAMESPACE + "/lifecycle", null), 404);
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            assertThat(tools(client))
                    .noneMatch(tool -> "lifecycle".equals(tool.path("name").asText()));
        }
    }

    @Test
    void missingGrantDeniesDispatch() throws Exception {
        ObjectNode handler = proxy("ungranted", NAMESPACE, TARGET);
        handler.putArray("allowed_namespaces");
        activate(handler);
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            assertError(call(client, "ungranted", MAPPER.readTree("{\"value\":true}")), -32003, "denied");
            assertThat(captureCounts().toolCalls()).isZero();
        }
    }

    @Test
    void executionLimitsFailWithoutPoisoningServer() throws Exception {
        JsonNode result = MAPPER.readTree("{\"content\":[]}");
        Path good = WasmHandlerFixtures.result(components, "bounded", result);
        String looping = Files.readString(good)
                .replace("(i32.store (i32.const 0)", "(loop $forever (br $forever))\n      (i32.store (i32.const 0)");
        Path loop = WasmHandlerFixtures.write(components, "loop", looping);
        ObjectNode fuel = definition("fuel", loop);
        fuel.putObject("limits").put("fuel", 1000);
        ObjectNode memory = definition("memory", good);
        memory.putObject("limits").put("memory_bytes", 65536);
        ObjectNode deadline = definition("deadline", loop);
        deadline.putObject("limits").put("fuel", Long.MAX_VALUE).put("timeout_ms", 50);
        activate(fuel, memory, deadline, definition("healthy", good));
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            assertError(call(client, "fuel", MAPPER.createObjectNode()), -32603, "limit");
            assertError(call(client, "memory", MAPPER.createObjectNode()), -32603, "execution");
            assertError(call(client, "deadline", MAPPER.createObjectNode()), -32603, "timeout");
            assertThat(success(call(client, "healthy", MAPPER.createObjectNode())))
                    .isEqualTo(result);
        }
    }

    @Test
    void recursiveDispatchFailsPromptly() throws Exception {
        ObjectNode recursive = proxy("recursive", NAMESPACE, "recursive");
        recursive.putObject("limits").put("timeout_ms", 2000).put("max_concurrent", 1);
        activate(recursive);
        try (RawMcpClient client = connectRaw(NAMESPACE)) {
            JsonNode response = call(client, "recursive", MAPPER.createObjectNode());
            assertError(response, -32603, "limit");
            assertThat(response.path("error").path("message").asText()).contains("recurs");
            assertThat(captureCounts().toolCalls()).isZero();
        }
    }

    private ObjectNode definition(String name, Path component) {
        ObjectNode definition = MAPPER.createObjectNode().put("path", component.toString());
        ObjectNode tool = definition
                .putObject("tool")
                .put("name", name)
                .put("description", "WASM integration fixture")
                .put("uri", "")
                .put("type", "wasm")
                .put("namespace", NAMESPACE);
        tool.putObject("inputSchema").put("type", "object");
        definition.putArray("allowed_namespaces").add(NAMESPACE);
        return definition;
    }

    private ObjectNode proxy(String name, String namespace, String target) throws Exception {
        return definition(name, WasmHandlerFixtures.host(components, name, "dispatch", namespace, target));
    }

    private void activate(ObjectNode... definitions) throws Exception {
        assertStatus(management("PUT", HANDLERS, MAPPER.valueToTree(definitions)), 200);
    }

    private HttpResponse<String> management(String method, String path, JsonNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertStatus(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(status);
    }

    private RawMcpClient connectRaw(String namespace) throws Exception {
        RawMcpClient client = new RawMcpClient(server.getMcpBaseUrl() + "/" + namespace + "/mcp");
        try {
            client.initialize();
            return client;
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    private JsonNode call(RawMcpClient client, String name, JsonNode arguments) throws Exception {
        ObjectNode params = MAPPER.createObjectNode().put("name", name);
        params.set("arguments", arguments);
        return request(client, "tools/call", params, "call-" + name);
    }

    private static JsonNode request(RawMcpClient client, String method, JsonNode params, String id) throws Exception {
        ObjectNode request =
                MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        request.set("params", params);
        HttpResponse<String> response = client.send(request.toString());
        assertStatus(response, 200);
        JsonNode envelope = MAPPER.readTree(response.body());
        assertThat(envelope.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(envelope.get("id")).isEqualTo(MAPPER.valueToTree(id));
        return envelope;
    }

    private static JsonNode success(JsonNode envelope) {
        assertThat(envelope.has("error")).as("Response: %s", envelope).isFalse();
        assertThat(envelope.path("result").isObject()).isTrue();
        return envelope.get("result");
    }

    private static JsonNode tools(RawMcpClient client) throws Exception {
        JsonNode tools = success(request(client, "tools/list", MAPPER.createObjectNode(), "list"))
                .path("tools");
        assertThat(tools.isArray()).isTrue();
        return tools;
    }

    private static void assertError(JsonNode envelope, int code, String kind) {
        assertThat(envelope.has("result")).as("Response: %s", envelope).isFalse();
        assertThat(envelope.path("error").path("code").asInt())
                .as("Response: %s", envelope)
                .isEqualTo(code);
        assertThat(envelope.path("error").path("data").path("kind").asText())
                .as("Response: %s", envelope)
                .isEqualTo(kind);
    }
}
