package ai.wanaku.test.router;

import ai.wanaku.test.client.ManagementClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class RouterInfoITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(managementClient).as("ManagementClient must be available").isNotNull();
    }

    @DisplayName("Return router info with name and version from management endpoint")
    @Test
    void shouldReturnRouterInfo() {
        try {
            JsonNode info = managementClient.getInfo();
            assertThat(info).isNotNull();
            assertThat(info.has("name"))
                    .as("Info response must contain 'name' field")
                    .isTrue();
            assertThat(info.get("name").isTextual())
                    .as("'name' field must be a text value")
                    .isTrue();
            assertThat(info.get("name").asText())
                    .as("Server name must not be blank")
                    .isNotBlank();
            assertThat(info.has("version"))
                    .as("Info response must contain 'version' field")
                    .isTrue();
            assertThat(info.get("version").isTextual())
                    .as("'version' field must be a text value")
                    .isTrue();
            assertThat(info.get("version").asText())
                    .as("Server version must not be blank")
                    .isNotBlank();
        } catch (ManagementClient.ManagementClientException e) {
            if (e.getStatusCode() == 404) {
                assumeThat(false)
                        .as("Management info endpoint not available in this Router version")
                        .isTrue();
            }
            throw e;
        }
    }

    @DisplayName("Return router statistics from management endpoint")
    @Test
    void shouldReturnRouterStatistics() {
        try {
            JsonNode statistics = managementClient.getStatistics();
            assertThat(statistics).isNotNull();
        } catch (ManagementClient.ManagementClientException e) {
            if (e.getStatusCode() == 404) {
                assumeThat(false)
                        .as("Management statistics endpoint not available in this Router version")
                        .isTrue();
            }
            throw e;
        }
    }

    @DisplayName("Router health endpoint is accessible")
    @Test
    void shouldHaveAccessibleHealthEndpoint() {
        assertThat(isServerRunning()).isTrue();
    }
}
