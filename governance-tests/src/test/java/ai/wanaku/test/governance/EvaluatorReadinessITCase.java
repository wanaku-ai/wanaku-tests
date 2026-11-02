package ai.wanaku.test.governance;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.config.TestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Evaluator readiness ({@code GET /api/v1/evaluators/status}) and the bounded
 * {@code evaluator_governance} counters of {@code GET /api/v1/metrics}.
 *
 * <p>Each state is produced explicitly: {@code unconfigured} before any evaluator exists,
 * {@code ready} after activation, {@code degraded} after an LLM failure, {@code ready} again after a
 * successful evaluation or a new revision, {@code invalid} when the startup runtime cannot load, and
 * {@code disabled} when the posture disables the namespace.
 */
@Timeout(120)
class EvaluatorReadinessITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "readiness";
    private static final String EVALUATOR = "readiness-safety";
    private static final String DENY_MARKER = "readiness-please-block";
    private static final String DISABLED_REASON = "Readiness integration test";
    private static final Set<String> MODES = Set.of("enforce", "audit", "disabled");
    private static final Set<String> OUTCOMES = Set.of(
            "enforced_allow",
            "enforced_deny",
            "audit_allow",
            "audit_deny",
            "audit_skipped",
            "no_match_allow",
            "no_match_deny",
            "fail_open",
            "fail_closed",
            "disabled");

    /** Bootstrap YAML appended for the current test; {@code null} for most tests. */
    private String extraConfig;

    @Override
    protected void checkAdditionalRequirements(TestConfiguration config) {
        assumeThat(config.getEvaluatorWasmPath() != null && Files.exists(config.getEvaluatorWasmPath()))
                .as("A compiled safety-review WASM is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();
    }

    @Test
    void readinessFollowsFailuresRecoveryAndNewRevisions() throws Exception {
        startGovernedServer(
                GovernancePolicies.policy(), GovernancePolicies.posture("enforce", "allow", "deny", "basic", null));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        EvaluatorClient evaluators = new EvaluatorClient(server.getBaseUrl(), null);
        assertStatus(evaluators.status(NAMESPACE), "unconfigured", "no_evaluators_configured");

        long firstRevision = activate(evaluators);
        JsonNode ready = evaluators.status(NAMESPACE);
        assertStatus(ready, "ready", "configuration_ready");
        assertThat(ready.path("revision_id").asLong()).isEqualTo(firstRevision);
        assertStatus(evaluators.status("another-namespace"), "unconfigured", "no_evaluators_configured");

        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            llmStub.failWithStatus(500);
            assertThat(call(http, "readiness-failure")
                            .path("error")
                            .path("code")
                            .asInt())
                    .isEqualTo(-32001);
            JsonNode degraded = evaluators.status(NAMESPACE);
            assertStatus(degraded, "degraded", "evaluation_failed");
            assertThat(degraded.path("revision_id").asLong()).isEqualTo(firstRevision);
            assertThat(degraded.toString())
                    .as("The status must expose a safe reason code, not the raw engine error")
                    .doesNotContain("500", WanakuTestConstants.TEST_LLM_CONNECTION_SECRET);
            assertStatus(evaluators.status("another-namespace"), "unconfigured", "no_evaluators_configured");

            // A successful evaluation clears the failure state of the evaluator.
            llmStub.failWithStatus(0);
            assertThat(call(http, "readiness-recovered").has("result")).isTrue();
            assertStatus(evaluators.status(NAMESPACE), "ready", "configuration_ready");

            // A new active revision starts with its own runtime status.
            llmStub.failWithStatus(500);
            assertThat(call(http, "readiness-failure-again")
                            .path("error")
                            .path("code")
                            .asInt())
                    .isEqualTo(-32001);
            assertStatus(evaluators.status(NAMESPACE), "degraded", "evaluation_failed");
            long secondRevision = activate(evaluators);
            assertThat(secondRevision).isNotEqualTo(firstRevision);
            JsonNode renewed = evaluators.status(NAMESPACE);
            assertStatus(renewed, "ready", "configuration_ready");
            assertThat(renewed.path("revision_id").asLong()).isEqualTo(secondRevision);

            assertThat(captureCounts().toolCalls()).isEqualTo(1);
            Map<String, Long> counters = governanceCounters(audit.metrics());
            assertThat(counters).containsEntry("enforce/fail_closed", 2L).containsEntry("enforce/enforced_allow", 1L);
        }
    }

    @Test
    void auditCountersRecordWouldBeOutcomesWhileTrafficContinues() throws Exception {
        ObjectNode governance = MAPPER.createObjectNode();
        governance.set("default", GovernancePolicies.postureNode("audit", "deny", "deny", "full", null));
        ObjectNode namespaces = governance.putObject("namespaces");
        namespaces.putObject("basic-audit").put("audit_level", "basic");
        namespaces.putObject("paused").put("mode", "disabled").put("disabled_reason", DISABLED_REASON);
        startGovernedServer(GovernancePolicies.policy(), governance.toString());
        for (String namespace : List.of(NAMESPACE, "basic-audit", "paused")) {
            registerCaptureForward(namespace);
            connect(namespace);
        }
        // One evaluator for every namespace, on tools/call only.
        var registered = new EvaluatorClient(server.getBaseUrl(), null)
                .updateEvaluators(EvaluatorConfigs.llm(
                                EVALUATOR, "tools/call", null, "classify", baseConfig.getEvaluatorWasmPath(), null)
                        .toString());
        assertThat(registered.statusCode())
                .as("Register evaluator: %s", registered.body())
                .isEqualTo(200);
        llmStub.setDenyMarker(DENY_MARKER);

        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            // Setup traffic (initialize, discovery) in the disabled namespace is counted too, so
            // compare the counters before and after the governed calls.
            Map<String, Long> before = governanceCounters(audit.metrics());
            // Full audit evaluates and would block; basic audit skips the LLM; disabled skips all.
            for (String namespace : List.of(NAMESPACE, "basic-audit", "paused")) {
                JsonNode response = AuditRequests.send(
                        http,
                        server.getMcpBaseUrl(),
                        namespace,
                        AuditRequests.tool("counters-" + namespace, CAPTURE_TOOL, DENY_MARKER),
                        "counters-" + namespace);
                assertThat(response.has("result"))
                        .as("%s: %s", namespace, response)
                        .isTrue();
            }
            // No evaluator matches prompts/get, so audit records the would-be no-match denial.
            ObjectNode prompt = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", "counters-no-match")
                    .put("method", "prompts/get");
            prompt.putObject("params")
                    .put("name", CAPTURE_PROMPT)
                    .putObject("arguments")
                    .put("topic", "counters");
            assertThat(AuditRequests.send(http, server.getMcpBaseUrl(), NAMESPACE, prompt, "counters-no-match")
                            .has("result"))
                    .isTrue();

            assertThat(captureCounts()).isEqualTo(new CaptureCounts(3, 0, 1));
            assertThat(llmStub.getCallCount()).isEqualTo(1);

            JsonNode metrics = audit.metrics();
            Map<String, Long> delta = new HashMap<>(governanceCounters(metrics));
            before.forEach((key, count) -> delta.merge(key, -count, Long::sum));
            delta.values().removeIf(count -> count == 0);
            assertThat(delta)
                    .isEqualTo(Map.of(
                            "audit/audit_deny", 1L,
                            "audit/audit_skipped", 1L,
                            "audit/no_match_deny", 1L,
                            "disabled/disabled", 1L));
            assertThat(metrics.path("evaluator_governance").toString())
                    .as("Governance counters must not use namespace, evaluator, or request dimensions")
                    .doesNotContain(NAMESPACE, "basic-audit", "paused", EVALUATOR, "counters-");

            EvaluatorClient evaluators = new EvaluatorClient(server.getBaseUrl(), null);
            assertStatus(evaluators.status(NAMESPACE), "ready", "configuration_ready");
            assertStatus(evaluators.status("paused"), "disabled", "governance_disabled");
        }
    }

    @Test
    void invalidStartupRuntimeBlocksEnforcedTrafficWhenStartupFailureIsDeny() throws Exception {
        ObjectNode governance = MAPPER.createObjectNode();
        // on_failure: allow must not open an invalid runtime: the unavailable state always blocks.
        governance.set("default", GovernancePolicies.postureNode("enforce", "allow", "allow", "basic", null));
        ObjectNode namespaces = governance.putObject("namespaces");
        namespaces.putObject("observed").put("mode", "audit");
        namespaces.putObject("paused").put("mode", "disabled").put("disabled_reason", DISABLED_REASON);
        ObjectNode evaluator = EvaluatorConfigs.llm(
                EVALUATOR,
                "tools/call",
                null,
                "classify",
                baseConfig.getEvaluatorWasmPath().resolveSibling("missing-readiness-processor.wasm"),
                null);
        extraConfig = "evaluator_startup_failure: deny\nevaluators: " + evaluator.path("evaluators");
        startGovernedServer(GovernancePolicies.policy(), governance.toString());
        for (String namespace : List.of(NAMESPACE, "observed", "paused")) {
            registerCaptureForward(namespace);
            connect(namespace);
        }

        EvaluatorClient evaluators = new EvaluatorClient(server.getBaseUrl(), null);
        JsonNode invalid = evaluators.status(NAMESPACE);
        assertThat(invalid.path("state").asText()).isEqualTo("invalid");
        assertThat(invalid.path("reason_code").asText()).isNotBlank();
        assertThat(evaluators.status("observed").path("state").asText()).isEqualTo("invalid");
        assertStatus(evaluators.status("paused"), "disabled", "governance_disabled");

        try (HttpClient http = HttpClient.newHttpClient()) {
            JsonNode blocked = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool("invalid-enforce", CAPTURE_TOOL, "benign"),
                    "invalid-enforce");
            assertThat(blocked.path("error").path("code").asInt())
                    .as("Invalid runtime in enforce mode: %s", blocked)
                    .isEqualTo(-32001);
            assertThat(blocked.path("error").path("message").asText())
                    .isEqualTo("evaluator: " + invalid.path("reason_code").asText());
            assertThat(captureCounts().toolCalls()).isZero();
            for (String namespace : List.of("observed", "paused")) {
                assertThat(AuditRequests.send(
                                        http,
                                        server.getMcpBaseUrl(),
                                        namespace,
                                        AuditRequests.tool("invalid-" + namespace, CAPTURE_TOOL, "benign"),
                                        "invalid-" + namespace)
                                .has("result"))
                        .as("Namespace %s must continue with an invalid runtime", namespace)
                        .isTrue();
            }
            assertThat(captureCounts().toolCalls()).isEqualTo(2);
            assertThat(llmStub.getCallCount()).isZero();
        }
    }

    @Override
    protected void configureGovernedServer(TestConfiguration.Builder builder) {
        builder.extraWanakuConfig(extraConfig);
    }

    private long activate(EvaluatorClient evaluators) {
        var response = evaluators.updateEvaluators(EvaluatorConfigs.llm(
                        EVALUATOR, "tools/call", NAMESPACE, "classify", baseConfig.getEvaluatorWasmPath(), null)
                .toString());
        assertThat(response.statusCode())
                .as("Activate evaluator: %s", response.body())
                .isEqualTo(200);
        return response.body().path("revision").path("id").asLong();
    }

    private JsonNode call(HttpClient http, String correlation) throws Exception {
        return AuditRequests.send(
                http,
                server.getMcpBaseUrl(),
                NAMESPACE,
                AuditRequests.tool(correlation, CAPTURE_TOOL, "benign-payload"),
                correlation);
    }

    private static void assertStatus(JsonNode status, String state, String reason) {
        assertThat(status.path("state").asText()).as("Status: %s", status).isEqualTo(state);
        assertThat(status.path("reason_code").asText()).as("Status: %s", status).isEqualTo(reason);
    }

    /** Counters keyed by {@code mode/outcome}; every key must use the fixed dimension values. */
    private static Map<String, Long> governanceCounters(JsonNode metrics) {
        Map<String, Long> counters = new HashMap<>();
        metrics.path("evaluator_governance").forEach(entry -> {
            assertThat(entry.size()).as("Counter dimensions: %s", entry).isEqualTo(3);
            assertThat(MODES).contains(entry.path("mode").asText());
            assertThat(OUTCOMES).contains(entry.path("outcome").asText());
            counters.put(
                    entry.path("mode").asText() + "/" + entry.path("outcome").asText(),
                    entry.path("count").asLong());
        });
        return counters;
    }
}
