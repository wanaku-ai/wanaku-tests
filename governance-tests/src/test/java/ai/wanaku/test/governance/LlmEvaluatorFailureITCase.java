package ai.wanaku.test.governance;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import java.util.stream.Stream;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.config.TestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Failures of a local LLM evaluator follow {@code on_failure} in enforce mode. The deterministic LLM
 * stub produces each failure: an HTTP error, an empty result, a response slower than the client
 * timeout, a refused connection, and a result that fails the evaluator result schema twice (the
 * first answer and the schema-correction retry).
 *
 * <p>{@code on_failure: deny} must block the call with {@code -32001} before it reaches the
 * upstream; {@code on_failure: allow} must forward it. Both record the safe failure reason code.
 */
@Timeout(120)
class LlmEvaluatorFailureITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "llm-failures";
    private static final String EVALUATOR = "llm-failure-safety";
    /** The server LLM client times out after 10 seconds; the stub holds each response longer. */
    private static final Duration SLOWER_THAN_CLIENT_TIMEOUT = Duration.ofSeconds(13);

    @Override
    protected void checkAdditionalRequirements(TestConfiguration config) {
        assumeThat(config.getEvaluatorWasmPath() != null && Files.exists(config.getEvaluatorWasmPath()))
                .as("A compiled safety-review WASM is required (set -D%s)", WanakuTestConstants.PROP_EVALUATOR_WASM)
                .isTrue();
    }

    static Stream<Arguments> failures() {
        return Stream.of("deny", "allow")
                .flatMap(onFailure -> Stream.of(
                        Arguments.of(onFailure, "http-error", "evaluator_llm_failed", 1),
                        Arguments.of(onFailure, "empty-result", "evaluator_llm_failed", 1),
                        Arguments.of(onFailure, "timeout", "evaluator_remote_timeout", 1),
                        Arguments.of(onFailure, "unavailable", "evaluator_remote_unavailable", 0),
                        Arguments.of(onFailure, "schema-rejected", "evaluator_schema_invalid", 2)));
    }

    @ParameterizedTest(name = "on_failure={0}: {1}")
    @MethodSource("failures")
    void llmFailuresFollowTheFailurePolicy(String onFailure, String scenario, String reasonCode, int llmCalls)
            throws Exception {
        startGovernedServer(
                GovernancePolicies.policy(), GovernancePolicies.posture("enforce", "allow", onFailure, "basic", null));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);

        ObjectNode schema = null;
        if ("schema-rejected".equals(scenario)) {
            // The stub verdict has only "level", so a required "reason" rejects every answer.
            schema = MAPPER.createObjectNode().put("type", "object");
            schema.putArray("required").add("level").add("reason");
        }
        var registered = new EvaluatorClient(server.getBaseUrl(), null)
                .updateEvaluators(EvaluatorConfigs.llm(
                                EVALUATOR,
                                "tools/call",
                                NAMESPACE,
                                "classify",
                                baseConfig.getEvaluatorWasmPath(),
                                schema)
                        .toString());
        assertThat(registered.statusCode())
                .as("Register evaluator: %s", registered.body())
                .isEqualTo(200);

        switch (scenario) {
            case "http-error" -> llmStub.failWithStatus(500);
            case "empty-result" -> llmStub.respondWithContent("");
            case "timeout" -> llmStub.delayResponses(SLOWER_THAN_CLIENT_TIMEOUT);
            case "unavailable" -> llmStub.stop();
            case "schema-rejected" -> { // The default verdict content does not match the schema.
            }
            default -> throw new IllegalArgumentException(scenario);
        }

        String correlation = "llm-failure-" + scenario;
        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            JsonNode response = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    NAMESPACE,
                    AuditRequests.tool(correlation, CAPTURE_TOOL, "benign-payload"),
                    correlation);
            if ("deny".equals(onFailure)) {
                assertThat(response.path("error").path("code").asInt())
                        .as("Fail-closed response: %s", response)
                        .isEqualTo(-32001);
                assertThat(response.path("error").path("message").asText())
                        .isEqualTo("evaluator: " + reasonCode.substring("evaluator_".length()));
                assertThat(captureCounts().toolCalls()).isZero();
            } else {
                assertThat(response.has("result"))
                        .as("Fail-open response: %s", response)
                        .isTrue();
                assertThat(captureCounts().toolCalls()).isEqualTo(1);
            }
            assertThat(llmStub.getCallCount()).as("LLM calls for %s", scenario).isEqualTo(llmCalls);

            JsonNode event = AuditRequests.event(audit, correlation, "wanaku_evaluator");
            assertThat(event.path("evaluator").asText()).isEqualTo(EVALUATOR);
            assertThat(event.path("reason_code").asText()).isEqualTo(reasonCode);
            JsonNode attributes = event.path("attributes");
            assertThat(attributes.path("failure").asBoolean()).isTrue();
            assertThat(attributes.path("engine").asText()).isEqualTo("llm");
            assertThat(attributes.path("evaluation_status").asText()).isEqualTo("error");
            String action = "deny".equals(onFailure) ? "block" : "pass";
            assertThat(attributes.path("would_be_action").asText()).isEqualTo(action);
            assertThat(attributes.path("effective_action").asText()).isEqualTo(action);
            assertThat(event.toString())
                    .as("The audit event must not disclose the LLM credential")
                    .doesNotContain(WanakuTestConstants.TEST_LLM_CONNECTION_SECRET);
        }
    }
}
