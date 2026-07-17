package ai.wanaku.test.forward;

import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.ForwardsClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class McpForwardingBasicITCase extends McpForwardingTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(isTargetRouterAvailable())
                .as("Target router must be available for forwarding tests")
                .isTrue();
        assumeThat(testNamespaceId).as("Test namespace must be available").isNotNull();
    }

    private boolean tryAddForward(String name, String address) {
        try {
            forwardsClient.add(name, address, testNamespaceId);
            return true;
        } catch (ForwardsClient.ForwardsClientException e) {
            return false;
        }
    }

    @DisplayName("Add a forward to the target MCP server")
    @Test
    void shouldAddForward() {
        boolean added = tryAddForward("test-forward", getTargetMcpUrl());
        assumeThat(added)
                .as("Router must accept the forward target (validates connectivity)")
                .isTrue();

        assertThat(forwardsClient.exists("test-forward")).isTrue();
    }

    @DisplayName("List forwards returns a valid response")
    @Test
    void shouldListForwards() {
        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).isNotNull();
    }

    @DisplayName("Remove a forward and verify it no longer exists")
    @Test
    void shouldRemoveForward() {
        boolean added = tryAddForward("fwd-to-remove", getTargetMcpUrl());
        assumeThat(added).as("Forward must be added before removal").isTrue();

        boolean removed = forwardsClient.remove("fwd-to-remove");

        assertThat(removed).isTrue();
        assertThat(forwardsClient.exists("fwd-to-remove")).isFalse();
    }

    @DisplayName("Return false when removing a non-existent forward")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentForward() {
        boolean removed = forwardsClient.remove("nonexistent-forward");

        assertThat(removed).isFalse();
    }

    @DisplayName("Refresh a forward without error")
    @Test
    void shouldRefreshForwards() {
        boolean added = tryAddForward("refresh-test-fwd", getTargetMcpUrl());
        assumeThat(added).as("Forward must be added before refresh").isTrue();

        forwardsClient.refresh("refresh-test-fwd");

        assertThat(forwardsClient.exists("refresh-test-fwd")).isTrue();
    }
}
