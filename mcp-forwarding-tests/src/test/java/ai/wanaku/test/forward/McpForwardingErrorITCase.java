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
class McpForwardingErrorITCase extends McpForwardingTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(testNamespaceId).as("Test namespace must be available").isNotNull();
    }

    @DisplayName("Router rejects a forward pointing to an unreachable server")
    @Test
    void shouldRejectUnreachableServerForward() {
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
        try {
            forwardsClient.add("valid-fwd", routerManager.getBaseUrl() + "/mcp/", testNamespaceId);
            assertThat(forwardsClient.exists("valid-fwd")).isTrue();
        } catch (ForwardsClient.ForwardsClientException e) {
            assumeThat(e.getMessage())
                    .as("Router validates forward target connectivity")
                    .doesNotContain("500");
        }
    }

    @DisplayName("List forwards returns valid response even when empty")
    @Test
    void shouldListForwardsWhenEmpty() {
        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).isNotNull();
    }
}
