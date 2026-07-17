package ai.wanaku.test.router;

import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
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

    @DisplayName("Add a forward and verify it exists")
    @Test
    void shouldAddForward() {
        forwardsClient.add("test-fwd", routerManager.getBaseUrl() + "/mcp/", nsId);

        assertThat(forwardsClient.exists("test-fwd")).isTrue();
    }

    @DisplayName("Add 3 forwards and verify all are present in the list")
    @Test
    void shouldListForwards() {
        forwardsClient.add("fwd-alpha", routerManager.getBaseUrl() + "/mcp/", nsId);
        forwardsClient.add("fwd-beta", routerManager.getBaseUrl() + "/mcp/", nsId);
        forwardsClient.add("fwd-gamma", routerManager.getBaseUrl() + "/mcp/", nsId);

        List<JsonNode> forwards = forwardsClient.list();

        assertThat(forwards).hasSizeGreaterThanOrEqualTo(3);
    }

    @DisplayName("Add a forward, remove it, and verify it no longer exists")
    @Test
    void shouldRemoveForward() {
        forwardsClient.add("fwd-to-remove", routerManager.getBaseUrl() + "/mcp/", nsId);
        assertThat(forwardsClient.exists("fwd-to-remove")).isTrue();

        boolean removed = forwardsClient.remove("fwd-to-remove");

        assertThat(removed).isTrue();
        assertThat(forwardsClient.exists("fwd-to-remove")).isFalse();
    }

    @DisplayName("Return false when removing a forward that does not exist")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentForward() {
        boolean removed = forwardsClient.remove("nonexistent");

        assertThat(removed).isFalse();
    }

    @DisplayName("Add a forward, call refresh, and verify no error occurs")
    @Test
    void shouldRefreshForwards() {
        forwardsClient.add("refresh-fwd", routerManager.getBaseUrl() + "/mcp/", nsId);

        forwardsClient.refresh("refresh-fwd");
    }
}
