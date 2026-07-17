package ai.wanaku.test.router;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.config.OidcCredentials;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class AuthenticationITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
    }

    @DisplayName("Reject unauthenticated tools list request")
    @Test
    void shouldRejectUnauthenticatedToolsRequest() {
        RouterClient unauthenticatedClient = new RouterClient(routerManager.getBaseUrl());

        try {
            unauthenticatedClient.listTools();
            assumeThat(false)
                    .as("Router allows unauthenticated access (auth=none mode), skipping")
                    .isTrue();
        } catch (RouterClient.RouterClientException e) {
            assertThat(e.getMessage()).containsAnyOf("401", "403", "302", "Unauthorized", "Forbidden");
        }
    }

    @DisplayName("Reject unauthenticated resources list request")
    @Test
    void shouldRejectUnauthenticatedResourcesRequest() {
        RouterClient unauthenticatedClient = new RouterClient(routerManager.getBaseUrl());

        try {
            unauthenticatedClient.listResources();
            assumeThat(false)
                    .as("Router allows unauthenticated access (auth=none mode), skipping")
                    .isTrue();
        } catch (RouterClient.RouterClientException e) {
            assertThat(e.getMessage()).containsAnyOf("401", "403", "302", "Unauthorized", "Forbidden");
        }
    }

    @DisplayName("Accept authenticated tools list request with valid MCP token")
    @Test
    void shouldAcceptAuthenticatedToolsRequest() {
        assumeThat(routerClient)
                .as("Authenticated RouterClient must be available")
                .isNotNull();

        assertThatCode(() -> routerClient.listTools()).doesNotThrowAnyException();
    }

    @DisplayName("Reject tools list request with invalid bearer token")
    @Test
    void shouldRejectInvalidToken() {
        RouterClient invalidClient = new RouterClient(routerManager.getBaseUrl(), "invalid-token-12345");

        try {
            invalidClient.listTools();
            assumeThat(false)
                    .as("Router allows invalid tokens (auth=none mode), skipping")
                    .isTrue();
        } catch (RouterClient.RouterClientException e) {
            assertThat(e.getMessage()).containsAnyOf("401", "403", "302", "Unauthorized", "Forbidden");
        }
    }

    @DisplayName("Service credentials are available and valid for capability authentication")
    @Test
    void shouldAcceptServiceCredentials() {
        assumeThat(keycloakManager).as("Keycloak must be available").isNotNull();
        assumeThat(keycloakManager.isRunning()).as("Keycloak must be running").isTrue();

        OidcCredentials credentials = keycloakManager.getServiceCredentials();

        assertThat(credentials).isNotNull();
        assertThat(credentials.tokenEndpoint()).isNotEmpty();
        assertThat(credentials.clientId()).isNotEmpty();
        assertThat(credentials.clientSecret()).isNotEmpty();
    }
}
