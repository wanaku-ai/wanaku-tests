package ai.wanaku.test.governance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.client.McpTestClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An evaluator processor cannot change the routing metadata of a request (wanaku#2041). The
 * processor in {@code evaluator-set-metadata/set-metadata.wat} calls {@code response.set-metadata}
 * with one fixed key. A reserved routing key rejects the call with JSON-RPC {@code -32002}, and the
 * call does not reach the upstream. A custom key lets the call continue.
 */
@Timeout(120)
class EvaluatorMetadataITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "evaluator-metadata";
    private static final String EVALUATOR = "metadata-setter";
    private static final int MALFORMED_CODE = -32002;

    @TempDir
    Path components;

    @ParameterizedTest(name = "reserved key {0}")
    @ValueSource(strings = {"wanaku.namespace", "wanaku.virtual_namespace", "mcp.method", "mcp.name", "a2a.task"})
    void enforcedProcessorCannotSetReservedRoutingKey(String reservedKey) throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        registerMetadataEvaluator(reservedKey);

        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "route-me"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(MALFORMED_CODE);
                    assertThat(error.message()).isEqualTo("evaluator cannot change routing metadata");
                })
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls())
                .as("A rejected metadata change must not reach the upstream")
                .isZero();
    }

    @Test
    void enforcedProcessorCanSetCustomKey() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        registerMetadataEvaluator("wanaku.risk_level");

        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "route-me"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls()).isEqualTo(1);
    }

    @Test
    void auditModeIgnoresReservedKeyAndForwards() throws Exception {
        startGovernedServer(
                GovernancePolicies.policy(), GovernancePolicies.posture("audit", "allow", "deny", "full", null));
        registerCaptureForward(NAMESPACE);
        registerMetadataEvaluator("wanaku.namespace");

        McpTestClient client = connect(NAMESPACE);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "route-me"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        assertThat(captureCounts().toolCalls())
                .as("Audit mode does not apply processor actions, so the call reaches its original upstream")
                .isEqualTo(1);
    }

    private void registerMetadataEvaluator(String key) throws IOException {
        String wat = fixture()
                .replace("METADATA_KEY", key)
                .replace("KEY_LENGTH", Integer.toString(key.getBytes(StandardCharsets.UTF_8).length));
        Path processor = Files.writeString(components.resolve("set-metadata.wat"), wat);
        var registered = new EvaluatorClient(server.getBaseUrl(), null)
                .updateEvaluators(EvaluatorConfigs.passthrough(EVALUATOR, "tools/call", NAMESPACE, processor)
                        .toString());
        assertThat(registered.statusCode())
                .as("Register evaluator: %s", registered.body())
                .isEqualTo(200);
    }

    private static String fixture() throws IOException {
        try (InputStream stream =
                EvaluatorMetadataITCase.class.getResourceAsStream("/evaluator-set-metadata/set-metadata.wat")) {
            if (stream == null) {
                throw new IOException("Missing evaluator fixture: set-metadata.wat");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
