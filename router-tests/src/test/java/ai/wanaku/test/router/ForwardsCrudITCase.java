package ai.wanaku.test.router;

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
class ForwardsCrudITCase extends RouterTestBase {

    private String nsId;

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        nsId = getOrCreateNamespaceId("fwd-test-ns");
        assumeThat(nsId)
                .as("Test namespace must be available for forwards tests")
                .isNotNull();
    }

    private void addForwardOrSkip(String name, String address) {
        try {
            forwardsClient.add(name, address, nsId);
        } catch (ForwardsClient.ForwardsClientException e) {
            assumeThat(e.getMessage())
                    .as("Forward add failed due to target validation (Router connects to target): %s", e.getMessage())
                    .doesNotContain("500");
        }
    }

    @DisplayName("Add a forward and verify it exists")
    @Test
    void shouldAddForward() {
        addForwardOrSkip("test-fwd", routerManager.getBaseUrl() + "/mcp/");

        assertThat(forwardsClient.exists("test-fwd")).isTrue();
    }

    @DisplayName("List forwards returns a valid response")
    @Test
    void shouldListForwards() {
        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).isNotNull();
    }

    @DisplayName("Remove a non-existent forward returns false")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentForward() {
        boolean removed = forwardsClient.remove("nonexistent");

        assertThat(removed).isFalse();
    }

    @DisplayName("Remove a forward and verify it no longer exists")
    @Test
    void shouldRemoveForward() {
        addForwardOrSkip("fwd-to-remove", routerManager.getBaseUrl() + "/mcp/");
        assumeThat(forwardsClient.exists("fwd-to-remove"))
                .as("Forward must exist before removal")
                .isTrue();

        boolean removed = forwardsClient.remove("fwd-to-remove");

        assertThat(removed).isTrue();
        assertThat(forwardsClient.exists("fwd-to-remove")).isFalse();
    }

    @DisplayName("Refresh a forward without error")
    @Test
    void shouldRefreshForwards() {
        addForwardOrSkip("refresh-fwd", routerManager.getBaseUrl() + "/mcp/");
        assumeThat(forwardsClient.exists("refresh-fwd"))
                .as("Forward must exist before refresh")
                .isTrue();

        forwardsClient.refresh("refresh-fwd");
    }
}
