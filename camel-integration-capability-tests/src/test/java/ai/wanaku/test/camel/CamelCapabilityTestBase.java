package ai.wanaku.test.camel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkiverse.mcp.server.ToolResponse;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.fixtures.TestFixtures;
import ai.wanaku.test.managers.CamelCapabilityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public abstract class CamelCapabilityTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(CamelCapabilityTestBase.class);
    private static final int MAX_REGISTER_RETRIES = 2;

    private static final Path FIXTURES_TARGET_DIR = Path.of("target", "test-fixtures");

    protected final List<CamelCapabilityManager> camelManagers = new ArrayList<>();
    private String currentNamespace = "default";

    @BeforeEach
    void setupCamelTestInfrastructure(TestInfo testInfo) throws IOException {
        Files.createDirectories(FIXTURES_TARGET_DIR);
    }

    @AfterEach
    void teardownCamelInfrastructure() {
        ForwardsClient forwardsClient = new ForwardsClient(getServerBaseUrl(), null);
        for (CamelCapabilityManager manager : camelManagers) {
            try {
                forwardsClient.remove(manager.getName());
            } catch (Exception e) {
                LOG.warn("Failed to remove forward {}: {}", manager.getName(), e.getMessage());
            }
            try {
                manager.stop();
            } catch (Exception e) {
                LOG.warn("Failed to stop CIC instance: {}", e.getMessage());
            }
        }
        camelManagers.clear();
    }

    protected CamelCapabilityManager startCapability(String serviceName, String fixtureName, String namespace)
            throws Exception {
        this.currentNamespace = namespace;

        String externalCicUrl = System.getProperty("wanaku.test.external.cic.url");
        if (externalCicUrl != null) {
            LOG.info("Using external CIC at {}", externalCicUrl);
            registerForwardWithRetry(serviceName, externalCicUrl, namespace);
            reconnectMcpClient(namespace);
            return null;
        }

        Path fixtureDir = TestFixtures.load(fixtureName, FIXTURES_TARGET_DIR);
        Path routesRef = fixtureDir.resolve("routes.camel.yaml");
        Path depsRef = fixtureDir.resolve("dependencies.txt");

        CamelCapabilityManager manager = new CamelCapabilityManager(config);
        manager.prepare(
                serviceName,
                "file://" + routesRef.toAbsolutePath(),
                depsRef.toFile().exists() ? "file://" + depsRef.toAbsolutePath() : null);

        manager.setLogContext("camel-capability", getClass().getSimpleName(), serviceName);
        manager.start(serviceName);

        registerForwardWithRetry(serviceName, manager.getMcpUrl(), namespace);
        reconnectMcpClient(namespace);

        camelManagers.add(manager);
        return manager;
    }

    protected void stopAndDeregister(CamelCapabilityManager manager, String serviceName) {
        if (manager != null) {
            manager.stop();
            camelManagers.remove(manager);
        }

        ForwardsClient forwardsClient = new ForwardsClient(getServerBaseUrl(), null);
        forwardsClient.remove(serviceName);
        LOG.info("Stopped CIC '{}' and removed forward", serviceName);

        reconnectMcpClient(currentNamespace);
    }

    private void registerForwardWithRetry(String name, String address, String namespace) {
        ForwardsClient forwardsClient = new ForwardsClient(getServerBaseUrl(), null);
        for (int attempt = 1; attempt <= MAX_REGISTER_RETRIES; attempt++) {
            // Each test method starts a new CIC under the same service name, so replace the earlier forward.
            forwardsClient.add(name, address, namespace, true);
            if (!routerClient.listTools().isEmpty()) {
                LOG.info("Forward '{}' registered, tools discovered (attempt {})", name, attempt);
                return;
            }
            LOG.debug("No tools discovered on attempt {}, retrying...", attempt);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("Forward '{}' registered after retries", name);
    }

    private void reconnectMcpClient(String namespace) {
        if (mcpClient != null) {
            try {
                mcpClient.disconnect();
            } catch (Exception e) {
                LOG.debug("MCP disconnect: {}", e.getMessage());
            }
            mcpClient = null;
        }
        try {
            String mcpBaseUrl = getServerMcpBaseUrl() + "/" + namespace;
            SessionIdProxy proxy = new SessionIdProxy(mcpBaseUrl);
            proxy.start();
            mcpClient = new McpTestClient(proxy.getBaseUrl(), null);
            mcpClient.connect();
        } catch (Exception e) {
            LOG.warn("Failed to reconnect MCP client: {}", e.getMessage());
        }
    }

    protected void assertToolCallWithRetry(
            String toolName, Map<String, Object> args, Consumer<ToolResponse> assertions) {
        mcpClient.when().toolsCall(toolName, args, assertions).thenAssertResults();
    }

    protected boolean isCamelCapabilityAvailable() {
        return config != null
                && config.getCamelCapabilityJarPath() != null
                && config.getCamelCapabilityJarPath().toFile().exists();
    }
}
