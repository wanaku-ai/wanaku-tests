package ai.wanaku.test.router;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.WanakuTestConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class ServiceDiscoveryITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(routerClient).as("RouterClient must be available").isNotNull();
    }

    @DisplayName("List capabilities endpoint returns a successful response")
    @Test
    void shouldListCapabilities() throws Exception {
        String accessToken = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            accessToken = keycloakManager.getMcpToken();
        }

        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(routerManager.getBaseUrl() + WanakuTestConstants.ROUTER_CAPABILITIES_PATH))
                .GET()
                .timeout(Duration.ofSeconds(30));

        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
    }

    @DisplayName("Detect HTTP capability registration when service is running")
    @Test
    void shouldDetectCapabilityRegistration() {
        assumeThat(isHttpToolServiceAvailable())
                .as("HTTP tool service must be available")
                .isTrue();

        boolean registered = routerClient.isCapabilityRegistered("http");

        assertThat(registered).isTrue();
    }

    @DisplayName("Return false for unknown capability service name")
    @Test
    void shouldReturnFalseForUnknownCapability() {
        boolean registered = routerClient.isCapabilityRegistered("nonexistent-service");

        assertThat(registered).isFalse();
    }

    @DisplayName("Deregister a capability and verify it is no longer registered")
    @Disabled("Blocked on wanaku-ai/wanaku#1702: deregistration endpoint needs identity verification")
    @Test
    void shouldDeregisterCapability() {
        assumeThat(isHttpToolServiceAvailable())
                .as("HTTP tool service must be available")
                .isTrue();
        assumeThat(routerClient.isCapabilityRegistered("http"))
                .as("HTTP capability must be registered before deregistration test")
                .isTrue();

        String accessToken = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            accessToken = keycloakManager.getMcpToken();
        }

        boolean deregistered = routerClient.deregisterCapability("http", accessToken);

        assertThat(deregistered).isTrue();
        assertThat(routerClient.isCapabilityRegistered("http")).isFalse();
    }
}
