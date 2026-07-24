package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Path;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.HttpCapabilityManager;
import ai.wanaku.test.managers.KeycloakManager;
import ai.wanaku.test.managers.RouterManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Abstract base class for Wanaku integration tests.
 * Provides layered lifecycle management:
 * - Module-scoped: Keycloak, Router (shared across all test classes via {@link SharedInfrastructureExtension})
 * - Test-scoped: HttpToolService, McpClient (fresh per test)
 */
@ExtendWith({SharedInfrastructureExtension.class, SkipThresholdExtension.class})
public abstract class BaseIntegrationTest {

    private static Logger LOG = LoggerFactory.getLogger(BaseIntegrationTest.class);

    // Module-scoped resources (populated by SharedInfrastructureExtension, shared across all test classes)
    protected static TestConfiguration config;
    protected static KeycloakManager keycloakManager;
    protected static RouterManager routerManager;
    protected static Path tempDataDir;

    // Test-scoped resources (fresh per test)
    protected HttpCapabilityManager httpCapabilityManager;
    protected McpTestClient mcpClient;
    protected RouterClient routerClient;
    protected String testName;

    @BeforeAll
    static void setupSuiteInfrastructure(TestInfo testInfo) {
        Class<?> testClass = testInfo.getTestClass().orElse(BaseIntegrationTest.class);
        LOG = LoggerFactory.getLogger(testClass);
        LOG.info("=== Test class starting: {} (reusing shared infrastructure) ===", testClass.getSimpleName());
    }

    @BeforeEach
    void setupTestInfrastructure(TestInfo testInfo) throws IOException {
        testName = testInfo.getDisplayName();
        String testMethodName = testInfo.getTestMethod().map(m -> m.getName()).orElse("unknown");
        LOG.info("[{}] >>> {}", testMethodName, testName);

        // Create RouterClient and McpClient
        if (routerManager != null && routerManager.isRunning()) {
            String accessToken = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                accessToken = keycloakManager.getMcpToken();
                LOG.debug("Obtained MCP access token with wanaku-mcp-client scope");
            }

            routerClient = new RouterClient(routerManager.getBaseUrl(), accessToken);

            // Create MCP client with authentication token (requires wanaku-mcp-client scope)
            try {
                mcpClient = new McpTestClient(routerManager.getBaseUrl(), accessToken);
                mcpClient.connect();
                LOG.debug("MCP client connected");
            } catch (Exception e) {
                LOG.warn("Failed to connect MCP client: {}", e.getMessage());
                mcpClient = null;
            }
        }

        // Start HTTP Capability (test-scoped by default)
        if (config.getHttpToolServiceJarPath() != null
                && config.getHttpToolServiceJarPath().toFile().exists()
                && routerManager != null
                && routerManager.isRunning()) {

            // Get OIDC credentials from Keycloak for capability registration
            OidcCredentials oidcCredentials = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                oidcCredentials = keycloakManager.getServiceCredentials();
            }

            httpCapabilityManager = new HttpCapabilityManager(config);
            httpCapabilityManager.prepare(new TargetConfiguration(
                    "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials));

            // Set log context for structured logging
            String profile = getLogProfile();
            String testClassName =
                    testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");
            httpCapabilityManager.setLogContext(profile, testClassName, testMethodName);

            httpCapabilityManager.start(testName);

            // Wait for HTTP Capability to register with Router
            LOG.debug("Waiting for HTTP Capability registration...");
            Awaitility.await()
                    .pollInterval(WanakuTestConstants.DEFAULT_REGISTRATION_POLL_INTERVAL)
                    .until(() -> routerClient.isCapabilityRegistered("http"));
            LOG.debug("HTTP Capability is registered");
        }

        LOG.debug("Test infrastructure ready: {}", testName);
    }

    @AfterEach
    void teardownTestInfrastructure() throws IOException {
        LOG.debug("Tearing down test: {}", testName);

        // Stop HTTP Capability
        if (httpCapabilityManager != null) {
            httpCapabilityManager.stop();
            httpCapabilityManager = null;
        }

        // Disconnect MCP client
        if (mcpClient != null) {
            try {
                mcpClient.disconnect();
            } catch (Exception e) {
                LOG.warn("Failed to disconnect MCP client: {}", e.getMessage());
            }
            mcpClient = null;
        }

        // Clear all tools from Router
        if (routerClient != null) {
            try {
                routerClient.clearAllTools();
            } catch (Exception e) {
                LOG.warn("Failed to clear tools: {}", e.getMessage());
            }
        }

        LOG.debug("Test teardown complete: {}", testName);
    }

    /**
     * Checks if the Router is available for testing.
     */
    protected boolean isRouterAvailable() {
        return routerManager != null && routerManager.isRunning();
    }

    /**
     * Checks if the HTTP Capability is available for testing.
     * This checks preconditions (JAR exists + Router running) since the actual
     * httpCapabilityManager is created in @BeforeEach which runs after @EnabledIf.
     */
    protected boolean isHttpToolServiceAvailable() {
        // Check if already running (for tests that run after @BeforeEach)
        if (httpCapabilityManager != null && httpCapabilityManager.isRunning()) {
            return true;
        }
        // Check preconditions (for @EnabledIf which runs before @BeforeEach)
        return isRouterAvailable()
                && config != null
                && config.getHttpToolServiceJarPath() != null
                && config.getHttpToolServiceJarPath().toFile().exists();
    }

    /**
     * Checks if the full stack (Router + HTTP Capability) is available for testing.
     */
    protected boolean isFullStackAvailable() {
        return isRouterAvailable() && isHttpToolServiceAvailable();
    }

    /**
     * Checks if the MCP client can be connected for testing.
     * This checks preconditions (Router running) since the actual mcpClient
     * is created in @BeforeEach which runs after @EnabledIf.
     */
    protected boolean isMcpClientAvailable() {
        // Check if already connected (for tests that run after @BeforeEach)
        if (mcpClient != null) {
            return true;
        }
        // Check preconditions (for @EnabledIf which runs before @BeforeEach)
        return isRouterAvailable();
    }

    /**
     * Gets the log profile name for structured logging.
     * Override in subclasses to provide capability-specific profile names.
     *
     * @return the profile name (e.g., "http-capability")
     */
    protected String getLogProfile() {
        return "default";
    }
}
