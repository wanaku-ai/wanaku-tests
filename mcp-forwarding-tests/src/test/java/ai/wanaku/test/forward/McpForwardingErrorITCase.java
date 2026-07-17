package ai.wanaku.test.forward;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.ForwardsClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class McpForwardingErrorITCase extends McpForwardingTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(testNamespaceId).as("Test namespace must be available").isNotNull();
    }

    @DisplayName("Adding a forward with an unreachable target is handled by the Router")
    @Test
    void shouldHandleUnreachableServerForward() {
        try {
            forwardsClient.add("unreachable-fwd", "http://localhost:1/mcp/", testNamespaceId);
            assertThat(forwardsClient.exists("unreachable-fwd")).isTrue();
        } catch (ForwardsClient.ForwardsClientException e) {
            assertThat(e.getMessage()).contains("500");
        }
    }

    @DisplayName("Adding a forward with a valid target succeeds")
    @Test
    void shouldAddForwardWithValidTarget() {
        forwardsClient.add("valid-fwd", routerManager.getBaseUrl() + "/mcp/", testNamespaceId);

        assertThat(forwardsClient.exists("valid-fwd")).isTrue();
    }

    @DisplayName("Clear all forwards removes all entries")
    @Test
    void shouldClearAllForwards() {
        forwardsClient.add("clear-fwd", routerManager.getBaseUrl() + "/mcp/", testNamespaceId);
        assertThat(forwardsClient.list()).isNotEmpty();

        forwardsClient.clearAll();

        assertThat(forwardsClient.list()).isEmpty();
    }
}
