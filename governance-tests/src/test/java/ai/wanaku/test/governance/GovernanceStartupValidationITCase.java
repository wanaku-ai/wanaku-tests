package ai.wanaku.test.governance;

import java.nio.file.Files;
import java.time.Duration;
import java.util.stream.Stream;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.WanakuServerManager;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invalid governance and evaluator startup configuration must stop the server. Each case starts a
 * server with one invalid setting and expects the process to exit on its own with a non-zero code and
 * a diagnostic that names the problem. The diagnostic must not disclose the configured LLM credential.
 */
@Timeout(120)
class GovernanceStartupValidationITCase extends GovernanceTestBase {

    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(30);

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of(
                        "global disabled without reason",
                        GovernancePolicies.posture("disabled", "deny", "deny", "basic", null),
                        null,
                        "disabled governance scope 'global' requires a reason"),
                Arguments.of(
                        "namespace disabled with a blank reason",
                        governanceWithNamespace(
                                "paused",
                                MAPPER.createObjectNode()
                                        .put("mode", "disabled")
                                        .put("disabled_reason", "  ")),
                        null,
                        "disabled governance scope 'paused' requires a reason"),
                Arguments.of(
                        "empty namespace name",
                        governanceWithNamespace("", MAPPER.createObjectNode().put("no_match", "allow")),
                        null,
                        "governance namespace must not be empty"),
                Arguments.of(
                        "unknown posture setting",
                        "{\"default\":{\"mode\":\"enforce\",\"no_mtch\":\"allow\"}}",
                        null,
                        "unknown field `no_mtch`"),
                Arguments.of(
                        "unknown governance section setting",
                        "{\"default\":{\"mode\":\"enforce\"},\"fallback\":{\"mode\":\"audit\"}}",
                        null,
                        "unknown field `fallback`"),
                Arguments.of(
                        "unknown namespace override setting",
                        governanceWithNamespace(
                                "observed", MAPPER.createObjectNode().put("modes", "audit")),
                        null,
                        "unknown field `modes`"),
                Arguments.of(
                        "unknown enforcement mode",
                        GovernancePolicies.posture("observe", "deny", "deny", "basic", null),
                        null,
                        "unknown variant `observe`"),
                Arguments.of(
                        "invalid evaluator startup failure policy",
                        null,
                        "evaluator_startup_failure: maybe",
                        "evaluator startup failed: startup_policy_invalid"),
                Arguments.of(
                        "startup evaluator with a missing processor",
                        null,
                        "evaluators: " + missingProcessorEvaluators(),
                        "evaluator startup failed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void invalidStartupConfigurationStopsTheServer(
            String scenario, String governanceJson, String extraConfig, String diagnostic) throws Exception {
        TestConfiguration config = baseConfig.toBuilder()
                .governanceEnabled(true)
                .mcpIdFilterEnabled(true)
                .actionPolicyJson(GovernancePolicies.policy())
                .governanceJson(governanceJson)
                .extraWanakuConfig(extraConfig)
                .llmConnectionUrl(llmStub.getConnectionUrl())
                .build();
        server = new WanakuServerManager(config);
        server.prepare();

        int exitCode = server.startExpectingExit(getClass().getSimpleName(), EXIT_TIMEOUT);

        String log = Files.readString(server.getLogFile().toPath());
        assertThat(exitCode).as("Exit code for '%s'. Log:%n%s", scenario, log).isNotZero();
        assertThat(log).as("Diagnostic for '%s'", scenario).contains(diagnostic);
        assertThat(log)
                .as("The startup diagnostic must not disclose the LLM credential")
                .doesNotContain(WanakuTestConstants.TEST_LLM_CONNECTION_SECRET);
    }

    private static String governanceWithNamespace(String namespace, ObjectNode override) {
        ObjectNode governance = MAPPER.createObjectNode();
        governance.set("default", GovernancePolicies.postureNode("enforce", "deny", "deny", "basic", null));
        governance.putObject("namespaces").set(namespace, override);
        return governance.toString();
    }

    private static String missingProcessorEvaluators() {
        ObjectNode evaluator = MAPPER.createObjectNode().put("name", "missing-processor");
        evaluator.putObject("trigger").put("method", "tools/call");
        evaluator
                .putObject("engine")
                .put("type", "llm")
                .put("operation", "classify")
                .put("prompt", "Classify the safety of this MCP request.")
                .put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        evaluator.putObject("processor").put("path", "/nonexistent/wanaku-tests/missing-processor.wasm");
        return MAPPER.createArrayNode().add(evaluator).toString();
    }
}
