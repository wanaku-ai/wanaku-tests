package ai.wanaku.test.camel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkiverse.mcp.server.ToolResponse;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.DataStoreClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.fixtures.TestFixtures;
import ai.wanaku.test.managers.CamelCapabilityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

/**
 * Base class for Camel Integration Capability (CIC) tests.
 * Extends BaseIntegrationTest with CIC-specific lifecycle management.
 *
 * <p>Unlike HTTP capability tests where the capability starts automatically in @BeforeEach,
 * CIC tests start capabilities explicitly via {@link #startCapability} because each test
 * may need different route/rules configurations.
 *
 * <p>All CIC instances started during a test are automatically stopped in @AfterEach.
 */
public abstract class CamelCapabilityTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(CamelCapabilityTestBase.class);

    private static final Path FIXTURES_TARGET_DIR = Path.of("target", "test-fixtures");

    protected final List<CamelCapabilityManager> camelManagers = new ArrayList<>();
    protected DataStoreClient dataStoreClient;

    @BeforeEach
    void setupCamelTestInfrastructure(TestInfo testInfo) throws IOException {
        Files.createDirectories(FIXTURES_TARGET_DIR);
        if (routerClient != null) {
            String dsToken = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                dsToken = keycloakManager.getMcpToken();
            }
            dataStoreClient = new DataStoreClient(routerManager.getBaseUrl(), dsToken);
        }
    }

    @AfterEach
    void teardownCamelInfrastructure() {
        // Deregister all CIC instances from Router before stopping processes.
        // CIC does not deregister itself on SIGTERM (no JVM shutdown hook),
        // so without this the Router keeps pinging dead processes.
        String deregToken = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            try {
                deregToken = keycloakManager.getMcpToken();
            } catch (Exception e) {
                LOG.warn("Failed to get token for deregistration: {}", e.getMessage());
            }
        }
        for (CamelCapabilityManager manager : camelManagers) {
            if (routerClient != null && manager.getName() != null) {
                try {
                    routerClient.deregisterCapability(manager.getName(), deregToken);
                } catch (Exception e) {
                    LOG.warn("Failed to deregister CIC '{}': {}", manager.getName(), e.getMessage());
                }
            }
        }

        // Stop all CIC instances
        for (CamelCapabilityManager manager : camelManagers) {
            try {
                manager.stop();
            } catch (Exception e) {
                LOG.warn("Failed to stop CIC instance: {}", e.getMessage());
            }
        }
        camelManagers.clear();

        // Clear Data Store entries
        if (dataStoreClient != null) {
            try {
                dataStoreClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear Data Store: {}", e.getMessage());
            }
        }

        // Clear all tools and resources
        if (routerClient != null) {
            try {
                routerClient.clearAllTools();
            } catch (Exception e) {
                LOG.warn("Failed to clear tools: {}", e.getMessage());
            }
            try {
                routerClient.clearAllResources();
            } catch (Exception e) {
                LOG.warn("Failed to clear resources: {}", e.getMessage());
            }
        }
    }

    /**
     * Starts a CIC instance with the given fixture directory (no variable substitution).
     *
     * @param serviceName  the service name for Router registration
     * @param fixtureName  the fixture directory name under src/test/resources/fixtures/
     * @return the started CamelCapabilityManager
     */
    protected CamelCapabilityManager startCapability(String serviceName, String fixtureName) throws Exception {
        Path fixtureDir = TestFixtures.load(fixtureName, FIXTURES_TARGET_DIR);
        return startCapabilityFromDir(serviceName, fixtureDir);
    }

    /**
     * Starts a CIC instance with the given fixture directory and variable substitution.
     *
     * @param serviceName  the service name for Router registration
     * @param fixtureName  the fixture directory name under src/test/resources/fixtures/
     * @param vars         placeholder variables to substitute (e.g., ${JDBC_URL})
     * @return the started CamelCapabilityManager
     */
    protected CamelCapabilityManager startCapability(String serviceName, String fixtureName, Map<String, String> vars)
            throws Exception {
        Path fixtureDir = TestFixtures.load(fixtureName, FIXTURES_TARGET_DIR, vars);
        return startCapabilityFromDir(serviceName, fixtureDir);
    }

    /**
     * Starts a CIC instance with datastore:// references (no local files needed).
     *
     * @param serviceName the service name for Router registration
     * @param routesRef   datastore:// URI for routes (e.g., "datastore://routes.camel.yaml")
     * @param rulesRef    datastore:// URI for rules (nullable)
     * @param depsRef     datastore:// URI for dependencies (nullable)
     * @return the started CamelCapabilityManager
     */
    protected CamelCapabilityManager startCapabilityFromDataStore(
            String serviceName, String routesRef, String rulesRef, String depsRef) throws IOException {

        OidcCredentials oidcCredentials = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            oidcCredentials = keycloakManager.getServiceCredentials();
        }

        CamelCapabilityManager manager = new CamelCapabilityManager(config);
        manager.prepare(
                serviceName,
                new TargetConfiguration(
                        "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials),
                routesRef,
                rulesRef,
                depsRef);

        manager.setLogContext("camel-capability", getClass().getSimpleName(), serviceName);
        manager.start(serviceName);

        waitForCapabilityReady(serviceName, manager);

        camelManagers.add(manager);
        return manager;
    }

    /**
     * Starts a CIC instance from an already-resolved fixture directory.
     * Can be used with TestFixtures output or with custom directories (e.g., for invalid syntax tests).
     *
     * @param serviceName the service name for Router registration
     * @param fixtureDir  directory containing routes.camel.yaml, rules.yaml, and optionally dependencies.txt
     * @return the started CamelCapabilityManager
     */
    protected CamelCapabilityManager startCapabilityFromDir(String serviceName, Path fixtureDir) throws IOException {
        Path routesRef = fixtureDir.resolve("routes.camel.yaml");
        Path rulesRef = fixtureDir.resolve("rules.yaml");
        Path depsRef = fixtureDir.resolve("dependencies.txt");

        OidcCredentials oidcCredentials = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            oidcCredentials = keycloakManager.getServiceCredentials();
        }

        CamelCapabilityManager manager = new CamelCapabilityManager(config);
        manager.prepare(
                serviceName,
                new TargetConfiguration(
                        "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials),
                "file://" + routesRef.toAbsolutePath(),
                rulesRef.toFile().exists() ? "file://" + rulesRef.toAbsolutePath() : null,
                depsRef.toFile().exists() ? "file://" + depsRef.toAbsolutePath() : null);

        manager.setLogContext("camel-capability", getClass().getSimpleName(), serviceName);
        manager.start(serviceName);

        waitForCapabilityReady(serviceName, manager);

        camelManagers.add(manager);
        return manager;
    }

    private void waitForCapabilityReady(String serviceName, CamelCapabilityManager manager) {
        // Wait for capability registration
        LOG.debug("Waiting for CIC '{}' to register with Router...", serviceName);
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    if (!manager.isRunning()) {
                        String logPath = manager.getLogFile() != null
                                ? manager.getLogFile().getAbsolutePath()
                                : "unknown";
                        throw new IllegalStateException("CIC '" + serviceName + "' process exited (exit code: "
                                + manager.getExitCode() + ") before registration completed."
                                + " Log file: " + logPath);
                    }
                    return routerClient.isCapabilityRegistered(serviceName);
                });
        LOG.info("CIC '{}' is registered with Router", serviceName);

        // Wait for tools/resources to be registered (CIC registers them asynchronously
        // after the capability service itself is registered)
        LOG.debug("Waiting for CIC '{}' tools/resources to appear in Router...", serviceName);
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    if (!manager.isRunning()) {
                        throw new IllegalStateException(
                                "CIC '" + serviceName + "' process exited before tools/resources registered.");
                    }
                    boolean hasTools = routerClient.listTools().stream().anyMatch(t -> serviceName.equals(t.getType()));
                    boolean hasResources =
                            routerClient.listResources().stream().anyMatch(r -> serviceName.equals(r.getType()));
                    return hasTools || hasResources;
                });
        LOG.info("CIC '{}' tools/resources are available", serviceName);

        // Reconnect MCP client so it picks up newly registered tools/resources.
        // The MCP Streamable HTTP transport may cache tool metadata from the
        // initial connection; a fresh session ensures the client sees the latest state.
        reconnectMcpClient();
    }

    private void reconnectMcpClient() {
        if (mcpClient == null) {
            return;
        }
        try {
            mcpClient.disconnect();
        } catch (Exception e) {
            LOG.debug("MCP disconnect during reconnect: {}", e.getMessage());
        }
        try {
            String accessToken = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                accessToken = keycloakManager.getMcpToken();
            }
            mcpClient = new McpTestClient(routerManager.getBaseUrl(), accessToken);
            mcpClient.connect();
            LOG.debug("MCP client reconnected");
        } catch (Exception e) {
            LOG.warn("Failed to reconnect MCP client: {}", e.getMessage());
        }
    }

    protected void assertToolCallWithRetry(
            String toolName, Map<String, Object> args, Consumer<ToolResponse> assertions) {
        java.util.concurrent.atomic.AtomicBoolean loggedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    try {
                        mcpClient.when().toolsCall(toolName, args, assertions).thenAssertResults();
                    } catch (AssertionError | Exception e) {
                        if (!loggedOnce.getAndSet(true)) {
                            LOG.warn("Tool call '{}' failed, listing available MCP tools for diagnostics...", toolName);
                            try {
                                mcpClient
                                        .when()
                                        .toolsList()
                                        .withAssert(page -> LOG.info(
                                                "Available MCP tools: {}",
                                                page.tools().stream()
                                                        .map(t -> t.name())
                                                        .toList()))
                                        .send()
                                        .thenAssertResults();
                            } catch (Exception listErr) {
                                LOG.warn("Failed to list MCP tools: {}", listErr.getMessage());
                            }
                        }
                        throw e;
                    }
                });
    }

    protected void assertResourceReadWithRetry(String resourceUri, Runnable assertion) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(3))
                .untilAsserted(assertion::run);
    }

    /**
     * Checks if the CIC JAR is available for testing.
     */
    protected boolean isCamelCapabilityAvailable() {
        return config != null
                && config.getCamelCapabilityJarPath() != null
                && config.getCamelCapabilityJarPath().toFile().exists();
    }

    /**
     * Checks if the Data Store API is accessible.
     */
    protected boolean isDataStoreAvailable() {
        return dataStoreClient != null && dataStoreClient.isAvailable();
    }

    protected String readFixtureFromClasspath(String relativePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/" + relativePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Fixture not found on classpath: fixtures/" + relativePath);
            }
            return new String(is.readAllBytes());
        }
    }

    @Override
    protected String getLogProfile() {
        return "camel-capability";
    }
}
