package ai.wanaku.test.forward;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.managers.HttpCapabilityManager;
import ai.wanaku.test.managers.RouterManager;
import ai.wanaku.test.model.HttpToolConfig;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public abstract class McpForwardingTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(McpForwardingTestBase.class);

    protected static RouterManager targetRouterManager;
    protected static RouterClient targetRouterClient;
    protected static HttpCapabilityManager targetHttpCapability;
    protected ForwardsClient forwardsClient;
    protected NamespaceClient namespaceClient;
    protected String testNamespaceId;

    @BeforeAll
    static void startTargetRouter() throws IOException {
        if (config == null || config.getRouterJarPath() == null) {
            LOG.warn("Router JAR not available, skipping target router setup");
            return;
        }

        targetRouterManager = new RouterManager(config);
        targetRouterManager.prepare();
        targetRouterManager.start("forwarding-target");

        String accessToken = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            accessToken = keycloakManager.getMcpToken();
        }

        targetRouterClient = new RouterClient(targetRouterManager.getBaseUrl(), accessToken);

        if (config.getHttpToolServiceJarPath() != null
                && config.getHttpToolServiceJarPath().toFile().exists()) {
            OidcCredentials oidcCredentials = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                oidcCredentials = keycloakManager.getServiceCredentials();
            }

            targetHttpCapability = new HttpCapabilityManager(config);
            targetHttpCapability.prepare(new TargetConfiguration(
                    "localhost",
                    targetRouterManager.getHttpPort(),
                    targetRouterManager.getGrpcPort(),
                    oidcCredentials));
            targetHttpCapability.start("forwarding-target-http");

            targetRouterClient.registerTool(HttpToolConfig.builder()
                    .name("forwarded-tool")
                    .description("A tool available via forwarding")
                    .uri("https://httpbin.org/get")
                    .build());
        }
    }

    @BeforeEach
    void setupForwardingClients(TestInfo testInfo) {
        if (routerManager != null && routerManager.isRunning()) {
            String accessToken = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                accessToken = keycloakManager.getMcpToken();
            }
            forwardsClient = new ForwardsClient(routerManager.getBaseUrl(), accessToken);
            namespaceClient = new NamespaceClient(routerManager.getBaseUrl(), accessToken);
            try {
                List<JsonNode> namespaces = namespaceClient.list();
                for (JsonNode ns : namespaces) {
                    if (ns.has("name") && "fwd-test-ns".equals(ns.get("name").asText())) {
                        testNamespaceId = ns.get("id").asText();
                        break;
                    }
                }
                if (testNamespaceId == null) {
                    testNamespaceId = namespaceClient.create("fwd-test-ns", "fwd-test-ns");
                }
            } catch (Exception e) {
                LOG.warn("Failed to setup test namespace: {}", e.getMessage());
            }
        }
    }

    @AfterEach
    void cleanupForwards() {
        if (forwardsClient != null) {
            try {
                forwardsClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear forwards: {}", e.getMessage());
            }
        }
    }

    @AfterAll
    static void stopTargetRouter() {
        if (targetHttpCapability != null) {
            try {
                targetHttpCapability.stop();
            } catch (Exception e) {
                LOG.warn("Failed to stop target HTTP capability: {}", e.getMessage());
            }
            targetHttpCapability = null;
        }
        if (targetRouterManager != null) {
            try {
                targetRouterManager.stop();
            } catch (Exception e) {
                LOG.warn("Failed to stop target router: {}", e.getMessage());
            }
            targetRouterManager = null;
        }
        targetRouterClient = null;
    }

    protected boolean isTargetRouterAvailable() {
        return targetRouterManager != null && targetRouterManager.isRunning();
    }

    protected String getTargetMcpUrl() {
        return targetRouterManager.getBaseUrl() + "/mcp/";
    }

    @Override
    protected String getLogProfile() {
        return "mcp-forwarding";
    }
}
