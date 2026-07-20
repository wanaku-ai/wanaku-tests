package ai.wanaku.test.router;

import java.time.Duration;
import org.awaitility.Awaitility;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.managers.HttpCapabilityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class CapabilityResilienceITCase extends RouterTestBase {

    private HttpCapabilityManager resilienceCapability;

    @BeforeEach
    void assumeFullStackAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(isHttpToolServiceAvailable())
                .as("HTTP tool service JAR must be available")
                .isTrue();
    }

    @AfterEach
    void stopResilienceCapability() {
        if (resilienceCapability != null) {
            try {
                resilienceCapability.stop();
            } catch (Exception e) {
                // ignore
            }
            resilienceCapability = null;
        }
    }

    @DisplayName("Detect that a capability is no longer registered after it stops")
    @Test
    void shouldDetectCapabilityStoppage() throws Exception {
        OidcCredentials oidcCredentials = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            oidcCredentials = keycloakManager.getServiceCredentials();
        }

        resilienceCapability = new HttpCapabilityManager(config);
        resilienceCapability.prepare(new TargetConfiguration(
                "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials));
        resilienceCapability.start("resilience-test");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> routerClient.isCapabilityRegistered("http"));

        assertThat(routerClient.isCapabilityRegistered("http")).isTrue();

        resilienceCapability.stop();
        resilienceCapability = null;

        String serviceToken = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            serviceToken = keycloakManager.getMcpToken();
        }
        routerClient.deregisterCapability("http", serviceToken);

        assertThat(routerClient.isCapabilityRegistered("http")).isFalse();
    }

    @DisplayName("Capability can re-register after being stopped and restarted")
    @Test
    void shouldReRegisterAfterRestart() throws Exception {
        OidcCredentials oidcCredentials = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            oidcCredentials = keycloakManager.getServiceCredentials();
        }

        resilienceCapability = new HttpCapabilityManager(config);
        resilienceCapability.prepare(new TargetConfiguration(
                "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials));
        resilienceCapability.start("resilience-restart-test");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> routerClient.isCapabilityRegistered("http"));

        resilienceCapability.stop();

        String serviceToken = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            serviceToken = keycloakManager.getMcpToken();
        }
        routerClient.deregisterCapability("http", serviceToken);

        resilienceCapability = new HttpCapabilityManager(config);
        resilienceCapability.prepare(new TargetConfiguration(
                "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials));
        resilienceCapability.start("resilience-restart-test-2");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> routerClient.isCapabilityRegistered("http"));

        assertThat(routerClient.isCapabilityRegistered("http")).isTrue();
    }
}
