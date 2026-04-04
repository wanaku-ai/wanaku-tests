package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.HttpCapabilityManager;
import ai.wanaku.test.managers.KeycloakManager;
import ai.wanaku.test.managers.RouterManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

/**
 * Abstract base class for Wanaku integration tests.
 * Provides layered lifecycle management:
 * - Suite-scoped: Keycloak, Router (shared across all tests)
 * - Test-scoped: HttpToolService, McpClient (fresh per test)
 */
public abstract class BaseIntegrationTest {

    // Dynamic logger - uses actual test class name instead of BaseIntegrationTest
    private static Logger LOG = LoggerFactory.getLogger(BaseIntegrationTest.class);

    // Suite-scoped resources (shared across all tests)
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
    static void setupSuiteInfrastructure(TestInfo testInfo) throws Exception {
        // Use actual test class for logging
        Class<?> testClass = testInfo.getTestClass().orElse(BaseIntegrationTest.class);
        String testClassName = testClass.getSimpleName();
        LOG = LoggerFactory.getLogger(testClass);
        LOG.info("=== Setting up suite infrastructure ===");

        // Set default Awaitility timeout to handle MCP client timeout (10s) + buffer
        Awaitility.setDefaultTimeout(Duration.ofSeconds(15));

        // Create isolated temp directory for this test suite
        tempDataDir = Files.createTempDirectory("wanaku-test-");
        LOG.debug("Created temp directory: {}", tempDataDir);

        // Load configuration (finds JARs automatically)
        TestConfiguration baseConfig = TestConfiguration.fromSystemProperties();
        config = TestConfiguration.builder()
                .artifactsDir(baseConfig.getArtifactsDir())
                .routerJarPath(baseConfig.getRouterJarPath())
                .httpToolServiceJarPath(baseConfig.getHttpToolServiceJarPath())
                .fileProviderJarPath(baseConfig.getFileProviderJarPath())
                .camelCapabilityJarPath(baseConfig.getCamelCapabilityJarPath())
                .tempDataDir(tempDataDir)
                .defaultTimeout(baseConfig.getDefaultTimeout())
                .build();

        LOG.debug("Router JAR: {}", config.getRouterJarPath());
        LOG.debug("HTTP Capability JAR: {}", config.getHttpToolServiceJarPath());

        // Check if we should skip infrastructure setup (for unit tests of test-common)
        if (shouldSkipInfrastructure()) {
            LOG.info("Skipping infrastructure setup (no JARs available)");
            return;
        }

        // Start Keycloak
        keycloakManager = new KeycloakManager();
        try {
            keycloakManager.start();
        } catch (Exception e) {
            LOG.warn("Keycloak startup failed, Router will run without authentication: {}", e.getMessage());
        }

        // Start Router
        if (config.getRouterJarPath() != null
                && config.getRouterJarPath().toFile().exists()) {
            routerManager = new RouterManager(config);
            routerManager.prepare();
            routerManager.start(testClassName);
            LOG.info("Router started on port {}", routerManager.getHttpPort());
        } else {
            LOG.warn("Router JAR not found at {}, skipping Router startup", config.getRouterJarPath());
        }

        LOG.info("=== Suite infrastructure ready ===");
    }

    @BeforeEach
    void setupTestInfrastructure(TestInfo testInfo) throws IOException {
        testName = testInfo.getDisplayName();
        String testMethodName = testInfo.getTestMethod().map(m -> m.getName()).orElse("unknown");
        LOG.info("[{}] >>> {}", testMethodName, testName);

        // Create RouterClient and McpClient
        if (routerManager != null && routerManager.isRunning()) {
            routerClient = new RouterClient(routerManager.getBaseUrl());

            // Create MCP client with authentication token (requires wanaku-mcp-client scope)
            try {
                String accessToken = null;
                if (keycloakManager != null && keycloakManager.isRunning()) {
                    accessToken = keycloakManager.getMcpToken();
                    LOG.debug("Obtained MCP access token with wanaku-mcp-client scope");
                }
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
            httpCapabilityManager.prepare(
                    "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials);

            // Set log context for structured logging
            String profile = getLogProfile();
            String testClassName =
                    testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");
            httpCapabilityManager.setLogContext(profile, testClassName, testMethodName);

            httpCapabilityManager.start(testName);

            // Wait for HTTP Capability to register with Router
            LOG.debug("Waiting for HTTP Capability registration...");
            Awaitility.await()
                    .pollInterval(Duration.ofMillis(200))
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

    @AfterAll
    static void teardownSuiteInfrastructure() {
        LOG.info("=== Tearing down suite infrastructure ===");

        // Stop Router
        if (routerManager != null) {
            routerManager.stop();
            routerManager = null;
        }

        // Stop Keycloak
        if (keycloakManager != null) {
            keycloakManager.stop();
            keycloakManager = null;
        }

        // Cleanup temp directory
        if (tempDataDir != null) {
            try {
                deleteRecursively(tempDataDir);
            } catch (IOException e) {
                LOG.warn("Failed to cleanup temp directory: {}", e.getMessage());
            }
        }

        LOG.info("=== Suite infrastructure teardown complete ===");
    }

    /**
     * Checks if infrastructure setup should be skipped.
     * Override this in subclasses for different behavior.
     */
    protected static boolean shouldSkipInfrastructure() {
        Path artifactsDir = Path.of(System.getProperty("wanaku.test.artifacts.dir", "artifacts"));
        return !Files.exists(artifactsDir) || !hasJars(artifactsDir);
    }

    private static boolean hasJars(Path dir) {
        try {
            return Files.list(dir).anyMatch(p -> {
                // Check for standalone JAR files
                if (p.toString().endsWith(".jar")) {
                    return true;
                }
                // Check for Quarkus app directories containing quarkus-run.jar
                if (Files.isDirectory(p)) {
                    Path quarkusRunJar = p.resolve("quarkus-run.jar");
                    return Files.exists(quarkusRunJar);
                }
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.list(path).forEach(p -> {
                try {
                    deleteRecursively(p);
                } catch (IOException e) {
                    LOG.warn("Failed to delete: {}", p);
                }
            });
        }
        Files.deleteIfExists(path);
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
