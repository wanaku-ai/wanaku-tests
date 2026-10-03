package ai.wanaku.test.governance;

import java.util.Map;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.McpTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end action-policy lifecycle coverage for wanaku-ai/wanaku#1903.
 *
 * <p>This intentionally reuses the focused selector, precedence, validation, and revision suites
 * instead of reproducing their matrices. It proves the public process boundary can carry one policy
 * through startup, runtime activation, rejected updates, rollback, and restart while real MCP
 * traffic observes each state.
 */
class ActionPolicyLifecycleITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "lifecycle";
    private static final int STATIC_DENY_CODE = -32003;
    private static final String DENY_MESSAGE = "lifecycle policy denies this operation";
    private static final String INVALID_FIELD = "unknown field";

    @DisplayName("Action-policy activation, rollback, and persisted restart preserve real MCP behavior")
    @Test
    void runsCompletePolicyLifecycleAcrossProcessRestart() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);

        try (ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl())) {
            McpTestClient client = connect(NAMESPACE);

            JsonNode baselinePolicy = MAPPER.readTree(GovernancePolicies.policy());
            ActionPolicyClient.Response baseline = policies.updatePolicy(baselinePolicy);
            assertThat(baseline.statusCode())
                    .as("Initial policy activation: %s", baseline.body())
                    .isEqualTo(200);
            long baselineRevision = revisionId(baseline.body());
            assertThat(revisionId(policies.getActiveRevision().body()))
                    .isEqualTo(baselineRevision);

            assertAllowedOperations(client);
            CaptureCounts afterAllow = captureCounts();
            assertThat(afterAllow).isEqualTo(new CaptureCounts(1, 1, 1));

            JsonNode denyPolicy = MAPPER.readTree(GovernancePolicies.policy(
                    GovernancePolicies.denyTool(
                            "lifecycle-tool-deny", CAPTURE_TOOL, DENY_MESSAGE),
                    GovernancePolicies.denyResourceByUri(
                            "lifecycle-resource-deny", CAPTURE_RESOURCE_URI, DENY_MESSAGE),
                    GovernancePolicies.denyPrompt(
                            "lifecycle-prompt-deny", CAPTURE_PROMPT, DENY_MESSAGE)));

            ActionPolicyClient.Response denied = policies.updatePolicy(denyPolicy, baselineRevision);
            assertThat(denied.statusCode())
                    .as("Runtime deny update: %s", denied.body())
                    .isEqualTo(200);
            long denyRevision = revisionId(denied.body());
            assertThat(denyRevision).isGreaterThan(baselineRevision);

            assertDeniedOperations(client);
            assertThat(captureCounts())
                    .as("Static denials must not reach the capture service")
                    .isEqualTo(afterAllow);

            ObjectNode invalidPolicy = (ObjectNode) denyPolicy.deepCopy();
            ((ObjectNode) invalidPolicy.path("rules").get(0)).put("unexpected", true);
            ActionPolicyClient.Response rejected = policies.updatePolicy(invalidPolicy, denyRevision);
            assertThat(rejected.statusCode())
                    .as("Invalid policy must be rejected: %s", rejected.body())
                    .isEqualTo(400);
            assertThat(rejected.body().toString())
                    .as("Invalid-policy response should explain the schema problem")
                    .contains(INVALID_FIELD);

            ActionPolicyClient.Response activeAfterReject = policies.getActiveRevision();
            assertThat(activeAfterReject.statusCode()).isEqualTo(200);
            assertThat(revisionId(activeAfterReject.body())).isEqualTo(denyRevision);
            assertThat(activeAfterReject.body().path("policy")).isEqualTo(denyPolicy);

            assertDiscoveryRemainsAvailable(client);
            assertDeniedOperations(client);
            assertThat(captureCounts())
                    .as("A rejected candidate must not change active behavior")
                    .isEqualTo(afterAllow);

            ActionPolicyClient.Response rollback =
                    policies.activateRevision(baselineRevision, denyRevision);
            assertThat(rollback.statusCode())
                    .as("Rollback activation: %s", rollback.body())
                    .isEqualTo(200);
            long rollbackRevision = revisionId(rollback.body());
            assertThat(rollbackRevision)
                    .as("Rollback must create a new immutable revision")
                    .isGreaterThan(denyRevision);
            assertThat(rollback.body().path("policy")).isEqualTo(baselinePolicy);

            assertAllowedOperations(client);
            CaptureCounts afterRollback = captureCounts();
            assertThat(afterRollback).isEqualTo(new CaptureCounts(2, 2, 2));

            client.disconnect();
            server.stopPreservingState();
            server.start(getClass().getSimpleName() + "-restart");

            ensureCaptureForward();
            McpTestClient restartedClient = connect(NAMESPACE);

            ActionPolicyClient.Response activeAfterRestart = policies.getActiveRevision();
            assertThat(activeAfterRestart.statusCode()).isEqualTo(200);
            assertThat(revisionId(activeAfterRestart.body()))
                    .as("Restart must retain the active rollback revision")
                    .isEqualTo(rollbackRevision);
            assertThat(activeAfterRestart.body().path("policy"))
                    .isEqualTo(baselinePolicy);

            JsonNode revisionsAfterRestart = policies.listRevisions().body();
            assertThat(revisionsAfterRestart.isArray()).isTrue();
            assertThat(revisionsAfterRestart.size())
                    .as("Baseline, denied, and rollback revisions must survive restart")
                    .isEqualTo(3);
            assertThat(revisionsAfterRestart)
                    .anyMatch(revision -> revision.path("id").asLong() == rollbackRevision
                            && "active".equals(revision.path("status").asText()));

            assertAllowedOperations(restartedClient);
            assertThat(captureCounts())
                    .as("Post-restart allowed operations must still reach the capture service")
                    .isEqualTo(new CaptureCounts(3, 3, 3));
        }
    }

    private void assertAllowedOperations(McpTestClient client) {
        client.when()
                .toolsList(page -> assertThat(page.tools()).anyMatch(tool -> CAPTURE_TOOL.equals(tool.name())))
                .thenAssertResults();

        client.when()
                .resourcesList(page -> assertThat(page.resources())
                        .anyMatch(resource -> CAPTURE_RESOURCE_URI.equals(resource.uri())))
                .thenAssertResults();

        client.when()
                .promptsList(page -> assertThat(page.prompts())
                        .anyMatch(prompt -> CAPTURE_PROMPT.equals(prompt.name())))
                .thenAssertResults();

        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "lifecycle-allow"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        client.when()
                .resourcesRead(CAPTURE_RESOURCE_URI)
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();

        client.when()
                .promptsGet(CAPTURE_PROMPT)
                .withArguments(Map.of("topic", "lifecycle-allow"))
                .withAssert(response -> assertThat(response.isError()).isFalse())
                .send()
                .thenAssertResults();
    }

    private void assertDeniedOperations(McpTestClient client) {
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "lifecycle-denied"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();

        client.when()
                .resourcesRead(CAPTURE_RESOURCE_URI)
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();

        client.when()
                .promptsGet(CAPTURE_PROMPT)
                .withArguments(Map.of("topic", "lifecycle-denied"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(STATIC_DENY_CODE);
                    assertThat(error.message()).contains(DENY_MESSAGE);
                })
                .send()
                .thenAssertResults();
    }

    private void assertDiscoveryRemainsAvailable(McpTestClient client) {
        client.when()
                .toolsList(page -> assertThat(page.tools()).anyMatch(tool -> CAPTURE_TOOL.equals(tool.name())))
                .thenAssertResults();

        client.when()
                .resourcesList(page -> assertThat(page.resources())
                        .anyMatch(resource -> CAPTURE_RESOURCE_URI.equals(resource.uri())))
                .thenAssertResults();

        client.when()
                .promptsList(page -> assertThat(page.prompts())
                        .anyMatch(prompt -> CAPTURE_PROMPT.equals(prompt.name())))
                .thenAssertResults();
    }

    private void ensureCaptureForward() throws Exception {
        var forwards = new ai.wanaku.test.client.ForwardsClient(server.getBaseUrl(), null);
        String forwardName = "capture-fwd-" + NAMESPACE;
        if (forwards.exists(forwardName)) {
            forwards.refresh(forwardName);
        } else {
            forwards.add(forwardName, captureServer.getMcpUrl(), NAMESPACE);
        }
        waitForToolDiscovery(NAMESPACE, CAPTURE_TOOL);
    }

    private static long revisionId(JsonNode responseBody) {
        long id = responseBody.path("revision").path("id").asLong(-1);
        assertThat(id).as("Management response must contain a numeric revision id").isPositive();
        return id;
    }
}
