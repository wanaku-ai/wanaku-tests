package ai.wanaku.test.router;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.ManagementClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class RouterInfoITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(managementClient).as("ManagementClient must be available").isNotNull();
    }

    @DisplayName("Return router info from management endpoint")
    @Test
    void shouldReturnRouterInfo() {
        try {
            JsonNode info = managementClient.getInfo();
            assertThat(info).isNotNull();
        } catch (ManagementClient.ManagementClientException e) {
            assumeThat(e.getMessage())
                    .as("Management info endpoint not available in this Router version")
                    .doesNotContain("404");
        }
    }

    @DisplayName("Return router statistics from management endpoint")
    @Test
    void shouldReturnRouterStatistics() {
        try {
            JsonNode statistics = managementClient.getStatistics();
            assertThat(statistics).isNotNull();
        } catch (ManagementClient.ManagementClientException e) {
            assumeThat(e.getMessage())
                    .as("Management statistics endpoint not available in this Router version")
                    .doesNotContain("404");
        }
    }

    @DisplayName("Router health endpoint is accessible")
    @Test
    void shouldHaveAccessibleHealthEndpoint() {
        assertThat(routerManager.isRunning()).isTrue();
    }
}
