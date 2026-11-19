package ai.wanaku.test.router;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.MockMcpServerManager;
import ai.wanaku.test.managers.WanakuServerManager;
import ai.wanaku.test.stub.DeterministicLlmStub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Cross-feature regression coverage for independent evaluator and action-policy revision streams. */
@Timeout(180)
class RevisionStreamIsolationITCase {
    private static final Logger LOG = LoggerFactory.getLogger(RevisionStreamIsolationITCase.class);
    private DeterministicLlmStub llmStub;
    private MockMcpServerManager captureServer;
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private TestConfiguration config;
    private WanakuServerManager server;
    private EvaluatorClient evaluators;
    private ActionPolicyClient policies;

    @BeforeEach
    void startManagedServer() throws Exception {
        if (System.getProperty("wanaku.test.external.mgmt.port") != null) {
            LOG.warn("Skipping revision isolation: external mode cannot provide a dedicated managed server");
        }
        assumeThat(System.getProperty("wanaku.test.external.mgmt.port"))
                .as("Revision isolation requires a dedicated managed server; external mode is unsupported")
                .isNull();
        config = TestConfiguration.fromSystemProperties();
        if (config.getServerBinaryPath() == null || !Files.exists(config.getServerBinaryPath())) {
            LOG.warn("Skipping revision isolation: managed Wanaku server binary is unavailable");
        }
        assumeThat(config.getServerBinaryPath() != null && Files.exists(config.getServerBinaryPath()))
                .as("A managed Wanaku server binary is required")
                .isTrue();
        if (config.getEvaluatorWasmPath() == null || !Files.exists(config.getEvaluatorWasmPath())) {
            LOG.warn("Skipping revision isolation: compiled evaluator WASM is unavailable");
        }
        assumeThat(config.getEvaluatorWasmPath() != null && Files.exists(config.getEvaluatorWasmPath()))
                .as("A compiled evaluator WASM action is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();
        llmStub = new DeterministicLlmStub("please-block");
        llmStub.start();
        server = new WanakuServerManager(config.toBuilder()
                .llmConnectionUrl(llmStub.getConnectionUrl())
                .governanceEnabled(true)
                .mcpIdFilterEnabled(true)
                .governanceJson(
                        "{\"default\":{\"mode\":\"enforce\",\"no_match\":\"allow\",\"on_failure\":\"deny\",\"audit_level\":\"basic\"}}")
                .build());
        server.prepare();
        server.start("revision-stream-isolation");
        evaluators = new EvaluatorClient(server.getBaseUrl(), null);
        policies = new ActionPolicyClient(server.getBaseUrl());
    }

    @AfterEach
    void stopManagedServer() {
        if (captureServer != null) {
            captureServer.stop();
        }
        if (llmStub != null) {
            llmStub.stop();
        }
        if (policies != null) {
            policies.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Updates and stale preconditions affect only their own revision stream")
    void shouldKeepRevisionPreconditionsIndependent() throws Exception {
        long evaluatorFirst = updateEvaluator("evaluator-first", null);
        long policyFirst = updatePolicy("policy-first", null);
        JsonNode policyHistory = policyHistory();
        long evaluatorSecond = updateEvaluator("evaluator-second", evaluatorFirst);
        assertThat(policyHistory()).isEqualTo(policyHistory);
        assertThat(activePolicyId()).isEqualTo(policyFirst);

        // An evaluator update must not invalidate the policy's previously fetched token.
        long policySecond = updatePolicy("policy-second", policyFirst);
        long evaluatorThird = updateEvaluator("evaluator-third", evaluatorSecond);
        assertThat(evaluators
                        .updateEvaluators(
                                evaluatorPayload("stale-evaluator", evaluatorFirst, config.getEvaluatorWasmPath()))
                        .statusCode())
                .isEqualTo(409);
        assertThat(activeEvaluatorId()).isEqualTo(evaluatorThird);
        assertThat(activePolicyId()).isEqualTo(policySecond);
        assertThat(policies.updatePolicy(policy("stale-policy"), policyFirst).statusCode())
                .isEqualTo(409);
        assertThat(activePolicyId()).isEqualTo(policySecond);
        assertThat(activeEvaluatorId()).isEqualTo(evaluatorThird);
        assertThat(evaluators
                        .getRevision(evaluatorSecond)
                        .body()
                        .path("evaluators")
                        .get(0)
                        .path("name")
                        .asText())
                .isEqualTo("evaluator-second");
        assertThat(policies.getRevision(policySecond)
                        .body()
                        .path("policy")
                        .path("rules")
                        .get(0)
                        .path("id")
                        .asText())
                .isEqualTo("policy-second");
        assertThat(evaluatorSecond).isEqualTo(evaluatorFirst + 1);
        assertThat(evaluatorThird).isEqualTo(evaluatorSecond + 1);
        assertThat(policySecond).isEqualTo(policyFirst + 1);
    }

    @Test
    @DisplayName("Rollback in either stream preserves the other history and policy runtime")
    void shouldKeepRollbackIndependent() throws Exception {
        long evaluatorFirst = updateEvaluator("evaluator-first", null);
        long policyFirst = updatePolicy("policy-first", null);
        updateEvaluator("evaluator-second", null);
        long policySecond = updatePolicy("policy-second", null);
        JsonNode policyHistory = policyHistory();
        assertPromptDenied("policy-second");
        assertEvaluatorBlocks();
        var evaluatorRollback = evaluators.activateRevision(evaluatorFirst, "{}");
        assertThat(evaluatorRollback.statusCode()).isEqualTo(200);
        assertThat(evaluatorRollback.body().path("revision").path("id").asLong())
                .isGreaterThan(evaluatorFirst);
        assertThat(evaluatorRollback
                        .body()
                        .path("evaluators")
                        .get(0)
                        .path("name")
                        .asText())
                .isEqualTo("evaluator-first");
        assertThat(activePolicyId()).isEqualTo(policySecond);
        assertThat(policyHistory()).isEqualTo(policyHistory);
        assertPromptDenied("policy-second");

        JsonNode evaluatorHistory = evaluators.listRevisions();
        long evaluatorActive = activeEvaluatorId();
        var policyRollback = policies.activateRevision(policyFirst, policySecond);
        assertThat(policyRollback.statusCode()).isEqualTo(200);
        assertThat(policyRollback.body().path("revision").path("id").asLong()).isGreaterThan(policySecond);
        assertThat(activeEvaluatorId()).isEqualTo(evaluatorActive);
        assertThat(evaluators.listRevisions()).isEqualTo(evaluatorHistory);
        assertPromptDenied("policy-first");
        assertEvaluatorBlocks();
    }

    @Test
    @DisplayName("An unbuildable historical evaluator leaves both active runtimes intact")
    void shouldKeepBothRuntimesAfterFailedActivation() throws Exception {
        Path originalProcessor = temporaryDirectory.resolve("original.wasm");
        Files.copy(config.getEvaluatorWasmPath(), originalProcessor);
        var original = evaluators.updateEvaluators(evaluatorPayload("original", null, originalProcessor));
        assertThat(original.statusCode()).isEqualTo(200);
        long originalId = original.body().path("revision").path("id").asLong();
        long evaluatorActive = updateEvaluator("working", null);
        long policyActive = updatePolicy("working-policy", null);
        JsonNode policyHistory = policyHistory();
        assertPromptDenied("working-policy");
        assertEvaluatorBlocks();
        Files.delete(originalProcessor);

        assertThat(evaluators.activateRevision(originalId, "{}").statusCode()).isEqualTo(422);
        assertThat(activeEvaluatorId()).isEqualTo(evaluatorActive);
        assertThat(activePolicyId()).isEqualTo(policyActive);
        assertThat(policyHistory()).isEqualTo(policyHistory);
        assertThat(evaluators.listEvaluators().get(0).path("name").asText()).isEqualTo("working");
        assertPromptDenied("working-policy");
        assertEvaluatorBlocks();
    }

    @Test
    @DisplayName("A shared persistence directory recovers separate histories and active configurations")
    void shouldRecoverBothStreamsFromSharedStorage() throws Exception {
        updateEvaluator("old-evaluator", null);
        updatePolicy("old-policy", null);
        long evaluatorActive = updateEvaluator("persisted-evaluator", null);
        long policyActive = updatePolicy("persisted-policy", null);
        JsonNode evaluatorHistory = evaluators.listRevisions();
        JsonNode policyHistory = policyHistory();
        Path persistence = server.getPersistDirectory();
        assertThat(persistence.resolve("evaluator-revisions.json")).isRegularFile();
        assertThat(persistence.resolve("action-policy-revisions.json")).isRegularFile();
        assertThat(mapper.readTree(Files.readString(persistence.resolve("evaluator-revisions.json")))
                        .toString())
                .contains("persisted-evaluator")
                .doesNotContain("persisted-policy");
        assertThat(mapper.readTree(Files.readString(persistence.resolve("action-policy-revisions.json")))
                        .toString())
                .contains("persisted-policy")
                .doesNotContain("persisted-evaluator");
        assertPromptDenied("persisted-policy");
        assertEvaluatorBlocks();
        server.stopPreservingState();
        server.start("revision-stream-isolation-restarted");
        assertThat(activeEvaluatorId()).isEqualTo(evaluatorActive);
        assertThat(activePolicyId()).isEqualTo(policyActive);
        assertThat(evaluators.listRevisions()).isEqualTo(evaluatorHistory);
        assertThat(policyHistory()).isEqualTo(policyHistory);
        assertThat(evaluators
                        .getActiveRevision()
                        .body()
                        .path("evaluators")
                        .get(0)
                        .path("name")
                        .asText())
                .isEqualTo("persisted-evaluator");
        assertPromptDenied("persisted-policy");
        assertEvaluatorBlocks();
    }

    @Test
    @DisplayName("Each default 50-revision history bound evicts only its own oldest entries")
    void shouldRetainHistoriesIndependently() throws Exception {
        // Both upstream stores currently expose a fixed DEFAULT_MAX_HISTORY of 50.
        long evaluatorFirst = updateEvaluator("retention-evaluator-first", null);
        long policyFirst = updatePolicy("retention-policy-first", null);
        JsonNode policyHistory = policyHistory();
        long evaluatorActive = evaluatorFirst;
        for (int index = 0; index < 50; index++) {
            evaluatorActive = updateEvaluator("retention-evaluator-" + index, evaluatorActive);
        }
        assertThat(evaluators.listRevisions()).hasSize(50);
        assertThat(evaluators.getRevision(evaluatorFirst).statusCode()).isEqualTo(404);
        assertThat(activeEvaluatorId()).isEqualTo(evaluatorActive);
        assertThat(policyHistory()).isEqualTo(policyHistory);
        JsonNode evaluatorHistory = evaluators.listRevisions();
        long policyActive = policyFirst;
        for (int index = 0; index < 50; index++) {
            policyActive = updatePolicy("retention-policy-" + index, policyActive);
        }
        assertThat(policyHistory()).hasSize(50);
        assertThat(policies.getRevision(policyFirst).statusCode()).isEqualTo(404);
        assertThat(activePolicyId()).isEqualTo(policyActive);
        assertThat(evaluators.listRevisions()).isEqualTo(evaluatorHistory);
        assertThat(activeEvaluatorId()).isEqualTo(evaluatorActive);
    }

    private long updateEvaluator(String name, Long expected) {
        var response = evaluators.updateEvaluators(evaluatorPayload(name, expected, config.getEvaluatorWasmPath()));
        assertThat(response.statusCode())
                .as("Evaluator update: %s", response.body())
                .isEqualTo(200);
        return response.body().path("revision").path("id").asLong();
    }

    private String evaluatorPayload(String name, Long expected, Path processor) {
        ObjectNode root = mapper.createObjectNode();
        if (expected != null) {
            root.put("expected_revision", expected);
        }
        ObjectNode evaluator = root.putArray("evaluators").addObject();
        evaluator.put("name", name);
        evaluator.putObject("trigger").put("method", "tools/call");
        evaluator
                .putObject("engine")
                .put("type", "llm")
                .put("operation", "classify")
                .put("prompt", "Classify " + name)
                .put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        evaluator.putObject("processor").put("path", processor.toString());
        return root.toString();
    }

    private JsonNode policy(String name) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode rule = root.putArray("rules").addObject();
        rule.put("id", name)
                .put("effect", "deny")
                .put("reason_code", "policy_error")
                .put("message", name);
        rule.putObject("selectors")
                .put("operation", "prompts/get")
                .put("target_type", "prompt")
                .putObject("target_name")
                .put("matcher", "exact")
                .put("value", "capture_prompt");
        return root;
    }

    private long updatePolicy(String name, Long expected) throws Exception {
        var response = policies.updatePolicy(policy(name), expected);
        assertThat(response.statusCode())
                .as("Policy update: %s", response.body())
                .isEqualTo(200);
        return response.body().path("revision").path("id").asLong();
    }

    private long activeEvaluatorId() {
        var response = evaluators.getActiveRevision();
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body().path("revision").path("id").asLong();
    }

    private long activePolicyId() throws Exception {
        var response = policies.getActiveRevision();
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body().path("revision").path("id").asLong();
    }

    private JsonNode policyHistory() throws Exception {
        var response = policies.listRevisions();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().isArray()).isTrue();
        return response.body();
    }

    private void startCaptureForward() throws Exception {
        Path captureJar = Path.of("../fixtures/governance-capture-server/target/quarkus-app/quarkus-run.jar")
                .toAbsolutePath()
                .normalize();
        if (!Files.exists(captureJar)) {
            LOG.warn("Skipping revision runtime check: governance capture fixture is unavailable at {}", captureJar);
        }
        assumeThat(captureJar)
                .as("Governance capture fixture is required for revision runtime checks")
                .exists();
        if (captureServer == null) {
            captureServer = new MockMcpServerManager(captureJar, config);
            captureServer.prepare();
            captureServer.start("revision-stream-evaluator-capture");
            new NamespaceClient(server.getBaseUrl(), null).create("revision-runtime");
            new ForwardsClient(server.getBaseUrl(), null)
                    .add("revision-capture", captureServer.getMcpUrl(), "revision-runtime");
        }
    }

    private void assertEvaluatorBlocks() throws Exception {
        startCaptureForward();
        int callsBefore = llmStub.getCallCount();
        try (SessionIdProxy proxy = new SessionIdProxy(server.getMcpBaseUrl() + "/revision-runtime")) {
            proxy.start();
            McpTestClient client = new McpTestClient(proxy.getBaseUrl(), null);
            client.connect();
            try {
                // Forward discovery is asynchronous; wait only for the actual discovery operation.
                org.awaitility.Awaitility.await()
                        .atMost(config.getDefaultTimeout())
                        .untilAsserted(() -> client.when()
                                .toolsList(page ->
                                        assertThat(page.tools()).anyMatch(tool -> "capture_tool".equals(tool.name())))
                                .thenAssertResults());
                client.when()
                        .toolsCall("capture_tool")
                        .withArguments(Map.of("payload", "please-block"))
                        .withErrorAssert(error -> {
                            assertThat(error.code()).isEqualTo(-32001);
                            assertThat(error.message()).contains("blocked by evaluator");
                        })
                        .send()
                        .thenAssertResults();
                assertThat(llmStub.getCallCount()).isGreaterThan(callsBefore);
                assertThat(captureCounts().path("toolCalls").asInt()).isZero();
            } finally {
                client.disconnect();
            }
        }
    }

    private JsonNode captureCounts() throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(captureServer.getBaseUrl() + "/capture/counts"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }
    }

    private void assertPromptDenied(String message) throws Exception {
        startCaptureForward();
        try (SessionIdProxy proxy = new SessionIdProxy(server.getMcpBaseUrl() + "/revision-runtime")) {
            proxy.start();
            McpTestClient client = new McpTestClient(proxy.getBaseUrl(), null);
            client.connect();
            try {
                // Forwarded prompt discovery is asynchronous, including after a managed restart.
                org.awaitility.Awaitility.await()
                        .atMost(config.getDefaultTimeout())
                        .untilAsserted(() -> client.when()
                                .promptsList(page -> assertThat(page.prompts())
                                        .anyMatch(prompt -> "capture_prompt".equals(prompt.name())))
                                .thenAssertResults());
                client.when()
                        .promptsGet("capture_prompt")
                        .withArguments(Map.of("topic", "revision-isolation"))
                        .withErrorAssert(error -> {
                            assertThat(error.code()).isEqualTo(-32003);
                            assertThat(error.message()).contains(message);
                        })
                        .send()
                        .thenAssertResults();
                assertThat(captureCounts().path("promptGets").asInt()).isZero();
            } finally {
                client.disconnect();
            }
        }
    }
}
