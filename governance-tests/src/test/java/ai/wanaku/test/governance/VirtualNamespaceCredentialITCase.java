package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.managers.MockMcpServerManager;
import ai.wanaku.test.managers.WanakuServerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

/** Credential scope follows concrete ownership even when the caller uses a virtual endpoint. */
@Timeout(120)
class VirtualNamespaceCredentialITCase extends GovernanceTestBase {
    private static final String OWNER = "credential-owner";
    private static final String VIRTUAL = "credential-view";
    private static final String FORWARD = "credential-owner-forward";
    private static final String BINDING = "credential-owner-binding";
    private static final String SECRET_VARIABLE = "WANAKU_VIRTUAL_TEST_TOKEN";
    private static final String SECRET = "synthetic-virtual-test-token-337";
    private static final String OTHER_OWNER = "other-credential-owner";
    private static final String OTHER_FORWARD = "other-credential-forward";
    private static final String OTHER_BINDING = "other-credential-binding";
    private static final String OTHER_SECRET_VARIABLE = "WANAKU_VIRTUAL_OTHER_TOKEN";
    private static final String OTHER_SECRET = "synthetic-virtual-other-token-337";

    @Override
    protected void configureCaptureServer(MockMcpServerManager capture) {
        capture.addSystemProperty("capture.synthetic-authorization", "true");
        capture.addEnvironmentVariable("CAPTURE_SYNTHETIC_PRIMARY_TOKEN", SECRET);
        capture.addEnvironmentVariable("CAPTURE_SYNTHETIC_SECONDARY_TOKEN", OTHER_SECRET);
    }

    @Test
    @DisplayName("Concrete-scoped binding permits qualified tools and prompts through a virtual endpoint")
    void shouldUseConcreteCredentialScopeForVirtualRequests() throws Exception {
        startCredentialServer(OWNER);
        registerVirtualForward();

        McpTestClient virtual = connect("virtual-ns/" + VIRTUAL);
        virtual.when()
                .toolsCall(OWNER + "/" + CAPTURE_TOOL)
                .withArguments(Map.of("payload", "virtual-tool"))
                .withAssert(response -> {
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content().getFirst().asText().text())
                            .contains("virtual-tool", "<redacted>")
                            .doesNotContain(SECRET);
                })
                .send()
                .thenAssertResults();
        virtual.when()
                .promptsGet(OWNER + "/" + CAPTURE_PROMPT, Map.of("topic", "virtual-prompt"), response -> {
                    assertThat(response.messages()).hasSize(1);
                    assertThat(response.messages().getFirst().content().asText().text())
                            .contains("virtual-prompt", "<redacted>")
                            .doesNotContain(SECRET);
                })
                .thenAssertResults();

        assertThat(captureCounts()).isEqualTo(new CaptureCounts(1, 0, 1));
        assertUpstreamAuthorization("primary");
        assertCredentialDecisions("allow", 2);

        virtual.when()
                .toolsCall(OTHER_OWNER + "/" + CAPTURE_TOOL)
                .withArguments(Map.of("payload", "other-owner-tool"))
                .withAssert(response -> {
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content().getFirst().asText().text()).doesNotContain(SECRET, OTHER_SECRET);
                })
                .send()
                .thenAssertResults();
        virtual.when()
                .promptsGet(
                        OTHER_OWNER + "/" + CAPTURE_PROMPT,
                        Map.of("topic", "other-owner-prompt"),
                        response -> assertThat(response.messages()
                                        .getFirst()
                                        .content()
                                        .asText()
                                        .text())
                                .doesNotContain(SECRET, OTHER_SECRET))
                .thenAssertResults();
        assertUpstreamAuthorization("secondary");

        // The virtual view must not replace the concrete route or its credential scope.
        connect(OWNER)
                .when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "concrete-tool"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();
        assertThat(captureCounts()).isEqualTo(new CaptureCounts(3, 0, 2));
        assertThat(lastAuthorization().path("tool").asText())
                .as("Returning to the first owner must not reuse the second owner's cached credential")
                .isEqualTo("primary");
        assertCredentialDecisions("allow", 3);
    }

    @Test
    @DisplayName("Virtual-only credential restrictions cannot authorize requests owned by a concrete member")
    void shouldRejectVirtualNamespaceAsCredentialAuthorizationScope() throws Exception {
        startCredentialServer(VIRTUAL);
        registerVirtualForward();
        // First establish that discovery succeeded; the failure below must be invocation authorization.
        McpTestClient virtual = connect("virtual-ns/" + VIRTUAL);
        virtual.when()
                .toolsList(page ->
                        assertThat(page.tools()).anyMatch(tool -> (OWNER + "/" + CAPTURE_TOOL).equals(tool.name())))
                .thenAssertResults();

        try (HttpClient http = HttpClient.newHttpClient()) {
            JsonNode tool = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    "virtual-ns/" + VIRTUAL,
                    AuditRequests.tool("denied-tool", OWNER + "/" + CAPTURE_TOOL, "must-not-arrive"),
                    "virtual-credential-tool-denied");
            assertThat(tool.has("error"))
                    .as("Credential restriction must deny the tool: %s", tool)
                    .isTrue();

            ObjectNode prompt = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", "denied-prompt")
                    .put("method", "prompts/get");
            prompt.putObject("params")
                    .put("name", OWNER + "/" + CAPTURE_PROMPT)
                    .putObject("arguments")
                    .put("topic", "must-not-arrive");
            JsonNode result = AuditRequests.send(
                    http, server.getMcpBaseUrl(), "virtual-ns/" + VIRTUAL, prompt, "virtual-credential-prompt-denied");
            assertThat(result.has("error"))
                    .as("Credential restriction must deny the prompt: %s", result)
                    .isTrue();
        }

        assertThat(captureCounts()).isEqualTo(new CaptureCounts(0, 0, 0));
        assertUpstreamAuthorization("absent");
        // Broker events distinguish a credential rejection from policy or routing rejection.
        assertCredentialDecisions("block", 2);
    }

    private void startCredentialServer(String permittedNamespace) throws Exception {
        ObjectNode virtualRule =
                MAPPER.createObjectNode().put("id", "allow-virtual").put("effect", "allow");
        virtualRule.putObject("selectors").put("namespace", VIRTUAL);
        ObjectNode concreteRule =
                MAPPER.createObjectNode().put("id", "allow-concrete").put("effect", "allow");
        concreteRule.putObject("selectors").put("namespace", OWNER);
        ObjectNode otherRule =
                MAPPER.createObjectNode().put("id", "allow-other-concrete").put("effect", "allow");
        otherRule.putObject("selectors").put("namespace", OTHER_OWNER);
        ObjectNode governance = MAPPER.createObjectNode();
        governance.set("default", GovernancePolicies.postureNode("enforce", "deny", "deny", "basic", null));
        ObjectNode scoped = governance.putObject("namespaces");
        scoped.putObject(OWNER).put("no_match", "allow");
        scoped.putObject(VIRTUAL).put("no_match", "allow");
        scoped.putObject(OTHER_OWNER).put("no_match", "allow");
        String bindings =
                """
                bindings:
                  - id: %s
                    forward_id: %s
                    origin: "%s"
                    mechanism: {type: bearer}
                    secret_refs: ["env:%s"]
                    allowed_purposes: [invocation]
                    restrictions:
                      namespaces: [%s]
                    cache: {max_ttl_seconds: 60}
                  - id: %s
                    forward_id: %s
                    origin: "%s"
                    mechanism: {type: bearer}
                    secret_refs: ["env:%s"]
                    allowed_purposes: [invocation]
                    restrictions:
                      namespaces: [%s]
                    cache: {max_ttl_seconds: 60}
                """
                        .formatted(
                                BINDING,
                                FORWARD,
                                captureServer.getBaseUrl(),
                                SECRET_VARIABLE,
                                permittedNamespace,
                                OTHER_BINDING,
                                OTHER_FORWARD,
                                captureServer.getBaseUrl(),
                                OTHER_SECRET_VARIABLE,
                                OTHER_OWNER);
        server = new WanakuServerManager(baseConfig.toBuilder()
                .governanceEnabled(true)
                .mcpIdFilterEnabled(true)
                .actionPolicyJson(GovernancePolicies.policy(virtualRule, concreteRule, otherRule))
                .governanceJson(governance.toString())
                .llmConnectionUrl(llmStub.getConnectionUrl())
                .extraWanakuConfig(bindings)
                .build());
        server.prepare();
        server.addEnvironmentVariable(SECRET_VARIABLE, SECRET);
        server.addEnvironmentVariable(OTHER_SECRET_VARIABLE, OTHER_SECRET);
        server.start(getClass().getSimpleName());
    }

    private void registerVirtualForward() throws Exception {
        NamespaceClient namespaces = new NamespaceClient(server.getBaseUrl(), null);
        namespaces.create(OWNER);
        namespaces.create(OTHER_OWNER);
        try (HttpClient http = HttpClient.newHttpClient()) {
            ObjectNode forward = MAPPER.createObjectNode()
                    .put("name", FORWARD)
                    .put("namespace", OWNER)
                    .put("address", captureServer.getMcpUrl());
            forward.putObject("credentialBindings").put("invocation", BINDING);
            post(http, "/api/v1/forwards", forward);
            waitForToolDiscovery(OWNER, CAPTURE_TOOL);
            ObjectNode otherForward = MAPPER.createObjectNode()
                    .put("name", OTHER_FORWARD)
                    .put("namespace", OTHER_OWNER)
                    .put("address", captureServer.getMcpUrl());
            otherForward.putObject("credentialBindings").put("invocation", OTHER_BINDING);
            post(http, "/api/v1/forwards", otherForward);
            waitForToolDiscovery(OTHER_OWNER, CAPTURE_TOOL);
            ObjectNode virtual = MAPPER.createObjectNode().put("name", VIRTUAL);
            virtual.putArray("members").add(OWNER).add(OTHER_OWNER);
            post(http, "/api/v1/namespaces", virtual);
        }
    }

    private void post(HttpClient http, String path, JsonNode body) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s: %s", path, response.body())
                .isIn(200, 201);
    }

    private void assertCredentialDecisions(String decision, int expectedCount) {
        try (AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode events = audit.listEvents(Map.of("operation", "credential/invocation", "target", FORWARD))
                    .path("events");
            assertThat(events).hasSize(expectedCount);
            for (JsonNode event : events) {
                assertThat(event.path("filter").asText()).isEqualTo("wanaku_credentials");
                assertThat(event.path("decision").asText()).isEqualTo(decision);
                assertThat(event.path("target_type").asText()).isEqualTo("forward");
                assertThat(event.path("attributes").path("binding_id").asText()).isEqualTo(BINDING);
                assertThat(event.toString())
                        .doesNotContain(SECRET, OTHER_SECRET, "env:" + SECRET_VARIABLE, "env:" + OTHER_SECRET_VARIABLE);
            }
        }
    }

    private void assertUpstreamAuthorization(String expected) throws Exception {
        JsonNode authorization = lastAuthorization();
        assertThat(authorization.toString()).doesNotContain(SECRET, OTHER_SECRET, "Bearer ");
        assertThat(authorization.path("tool").asText())
                .as("Tool Authorization matches")
                .isEqualTo(expected);
        assertThat(authorization.path("prompt").asText())
                .as("Prompt Authorization matches")
                .isEqualTo(expected);
    }

    private JsonNode lastAuthorization() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(captureServer.getBaseUrl() + "/capture/authorization"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return MAPPER.readTree(response.body());
        }
    }
}
