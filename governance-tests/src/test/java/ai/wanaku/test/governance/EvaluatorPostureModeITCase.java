package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.config.TestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The posture mode and audit level decide whether a local LLM evaluator runs and whether its verdict
 * affects traffic. Every call carries the deny marker, so an evaluated request always gets a
 * {@code red} (block) verdict from the deterministic LLM stub.
 *
 * <ul>
 *   <li>{@code enforce} calls the LLM and blocks.
 *   <li>{@code audit} with {@code basic} makes zero LLM calls and records {@code not_evaluated}.
 *   <li>{@code audit} with {@code full} calls the LLM, records the would-be block, and forwards.
 *   <li>{@code disabled} skips evaluation and records the disabled reason.
 * </ul>
 *
 * <p>Two more cases check that basic audit still runs a local (passthrough) processor, and that audit
 * suppresses the registry write of the {@code copy-tool-to-namespace} host function.
 */
@Timeout(120)
class EvaluatorPostureModeITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "evaluator-posture";
    private static final String EVALUATOR = "posture-safety";
    private static final String DENY_MARKER = "posture-please-block";
    private static final String DISABLED_REASON = "Evaluator posture integration test";

    @Override
    protected void checkAdditionalRequirements(TestConfiguration config) {
        assumeThat(config.getEvaluatorWasmPath() != null && Files.exists(config.getEvaluatorWasmPath()))
                .as("A compiled safety-review WASM is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();
    }

    static Stream<Arguments> postures() {
        return Stream.of(
                // mode, audit level, LLM calls, forwarded, reason code, evaluation status, would-be action
                Arguments.of("enforce", "basic", 1, 0, "evaluator_evaluated", "evaluated", "block"),
                Arguments.of("enforce", "full", 1, 0, "evaluator_evaluated", "evaluated", "block"),
                Arguments.of("audit", "basic", 0, 1, "evaluator_external_evaluation_disabled", "not_evaluated", null),
                Arguments.of("audit", "full", 1, 1, "evaluator_evaluated", "evaluated", "block"),
                Arguments.of("disabled", "full", 0, 1, "evaluator_disabled", "not_evaluated", null));
    }

    @ParameterizedTest(name = "mode={0}, audit_level={1}")
    @MethodSource("postures")
    void postureDecidesWhetherTheLlmRunsAndWhetherItsVerdictApplies(
            String mode,
            String auditLevel,
            int llmCalls,
            int forwarded,
            String reasonCode,
            String evaluationStatus,
            String wouldBeAction)
            throws Exception {
        String disabledReason = "disabled".equals(mode) ? DISABLED_REASON : null;
        startGovernedServer(
                GovernancePolicies.policy(),
                GovernancePolicies.posture(mode, "allow", "deny", auditLevel, disabledReason));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        registerEvaluator(EvaluatorConfigs.llm(
                EVALUATOR, "tools/call", NAMESPACE, "classify", baseConfig.getEvaluatorWasmPath(), null));
        llmStub.setDenyMarker(DENY_MARKER);

        String correlation = "evaluator-posture-" + mode + "-" + auditLevel;
        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode response = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool(correlation, CAPTURE_TOOL, DENY_MARKER),
                    correlation);
            if (forwarded == 0) {
                assertThat(response.path("error").path("code").asInt())
                        .as("Enforced block: %s", response)
                        .isEqualTo(-32001);
            } else {
                assertThat(response.has("result"))
                        .as("Mode %s must not block traffic: %s", mode, response)
                        .isTrue();
            }
            assertThat(captureCounts().toolCalls()).isEqualTo(forwarded);
            assertThat(llmStub.getCallCount())
                    .as("LLM calls in mode %s with audit_level %s", mode, auditLevel)
                    .isEqualTo(llmCalls);

            JsonNode event = AuditRequests.event(audit, correlation, "wanaku_evaluator");
            assertThat(event.path("reason_code").asText()).isEqualTo(reasonCode);
            JsonNode attributes = event.path("attributes");
            assertThat(attributes.path("enforcement_mode").asText()).isEqualTo(mode);
            assertThat(attributes.path("audit_level").asText()).isEqualTo(auditLevel);
            assertThat(attributes.path("evaluation_status").asText()).isEqualTo(evaluationStatus);
            assertThat(attributes.path("effective_action").asText()).isEqualTo(forwarded == 0 ? "block" : "pass");
            if (wouldBeAction == null) {
                assertThat(attributes.path("would_be_action").isNull())
                        .as("A skipped evaluation must not invent a would-be decision: %s", attributes)
                        .isTrue();
                assertThat(attributes.path("skipped").asBoolean()).isTrue();
            } else {
                assertThat(attributes.path("would_be_action").asText()).isEqualTo(wouldBeAction);
            }
            if ("disabled".equals(mode)) {
                assertThat(attributes.path("disabled_reason").asText()).isEqualTo(DISABLED_REASON);
            }
        }
    }

    @Test
    void basicAuditStillRunsLocalProcessorsWithoutLlmCalls() throws Exception {
        startGovernedServer(
                GovernancePolicies.policy(), GovernancePolicies.posture("audit", "allow", "deny", "basic", null));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        // The passthrough input has no safety "level", so the safety-review processor proposes
        // reject_malformed. Audit records that proposal and forwards the call.
        registerEvaluator(
                EvaluatorConfigs.passthrough(EVALUATOR, "tools/call", NAMESPACE, baseConfig.getEvaluatorWasmPath()));

        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode response = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool("basic-audit-local", CAPTURE_TOOL, DENY_MARKER),
                    "basic-audit-local");
            assertThat(response.has("result"))
                    .as("Audit response: %s", response)
                    .isTrue();
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
            assertThat(llmStub.getCallCount()).isZero();

            JsonNode attributes = AuditRequests.event(audit, "basic-audit-local", "wanaku_evaluator")
                    .path("attributes");
            assertThat(attributes.path("engine").asText()).isEqualTo("passthrough");
            assertThat(attributes.path("evaluation_status").asText()).isEqualTo("evaluated");
            assertThat(attributes.path("would_be_action").asText()).isEqualTo("reject_malformed");
            assertThat(attributes.path("effective_action").asText()).isEqualTo("pass");
        }
    }

    @Test
    void auditSuppressesRegistryWritesFromTheProcessor() throws Exception {
        Path assemblyFilter = baseConfig.getEvaluatorWasmPath().resolveSibling("assembly_filter_action.wasm");
        assumeThat(Files.exists(assemblyFilter))
                .as("The assembly-filter WASM must be built next to the safety action at %s", assemblyFilter)
                .isTrue();
        String target = "assembly-target";
        startGovernedServer(
                GovernancePolicies.policy(), GovernancePolicies.posture("audit", "allow", "deny", "full", null));
        // The capture tool lives in the shared default catalog; the target namespace has no tools.
        registerCaptureForward("default");
        new NamespaceClient(server.getBaseUrl(), null).create(target);
        connect(target);
        registerEvaluator(EvaluatorConfigs.llm(EVALUATOR, "tools/list", target, "filter", assemblyFilter, null));
        // The LLM approves the capture tool, so the assembly filter copies it into the target namespace.
        llmStub.respondWithContent("[\"" + CAPTURE_TOOL + "\"]");

        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            ObjectNode list = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", "audit-host-write")
                    .put("method", "tools/list");
            list.putObject("params");
            JsonNode response = AuditRequests.send(http, server.getMcpBaseUrl(), target, list, "audit-host-write");
            assertThat(response.has("result"))
                    .as("Audit response: %s", response)
                    .isTrue();
            assertThat(response.path("result").path("tools"))
                    .as("Audit must not change the tool list")
                    .isEmpty();
            assertThat(llmStub.getCallCount()).isEqualTo(1);

            JsonNode attributes = AuditRequests.event(audit, "audit-host-write", "wanaku_evaluator")
                    .path("attributes");
            assertThat(attributes.path("evaluation_status").asText()).isEqualTo("evaluated");
            assertThat(attributes.path("would_be_action").asText()).isEqualTo("filter_tools");
            assertThat(attributes.path("effective_action").asText()).isEqualTo("pass");
            assertThat(attributes.path("side_effects_suppressed").asBoolean()).isTrue();

            assertThat(toolStatus(http, CAPTURE_TOOL, "default")).isEqualTo(200);
            assertThat(toolStatus(http, CAPTURE_TOOL, target))
                    .as("Audit must not copy the tool into the target namespace")
                    .isEqualTo(404);
        }
    }

    private void registerEvaluator(ObjectNode config) {
        var registered = new EvaluatorClient(server.getBaseUrl(), null).updateEvaluators(config.toString());
        assertThat(registered.statusCode())
                .as("Register evaluator: %s", registered.body())
                .isEqualTo(200);
    }

    private int toolStatus(HttpClient http, String tool, String namespace) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        server.getBaseUrl() + WanakuTestConstants.TOOLS_PATH + "/" + tool + "?namespace=" + namespace))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
