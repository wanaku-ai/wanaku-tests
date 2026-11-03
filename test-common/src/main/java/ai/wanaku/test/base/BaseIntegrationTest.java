package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.WanakuServerManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({SharedInfrastructureExtension.class, SkipThresholdExtension.class})
public abstract class BaseIntegrationTest {

    private static Logger LOG = LoggerFactory.getLogger(BaseIntegrationTest.class);

    protected static TestConfiguration config;
    protected static WanakuServerManager serverManager;
    protected static Path tempDataDir;

    protected McpTestClient mcpClient;
    protected RouterClient routerClient;
    protected String testName;
    private SessionIdProxy mcpProxy;

    @BeforeAll
    static void setupSuiteInfrastructure(TestInfo testInfo) {
        Class<?> testClass = testInfo.getTestClass().orElse(BaseIntegrationTest.class);
        LOG = LoggerFactory.getLogger(testClass);
        LOG.info("=== Test class starting: {} (reusing shared infrastructure) ===", testClass.getSimpleName());
    }

    @BeforeEach
    void setupTestInfrastructure(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        String testMethodName = testInfo.getTestMethod().map(m -> m.getName()).orElse("unknown");
        LOG.info("[{}] >>> {}", testMethodName, testName);

        if (isServerRunning()) {
            routerClient = new RouterClient(getServerBaseUrl(), null);

            try {
                mcpProxy = new SessionIdProxy(getServerMcpBaseUrl() + "/default");
                mcpProxy.start();
                mcpClient = new McpTestClient(mcpProxy.getBaseUrl(), null);
                mcpClient.connect();
                LOG.debug("MCP client connected via proxy to {}/default", getServerMcpBaseUrl());
            } catch (Exception e) {
                LOG.warn("Failed to connect MCP client: {}", e.getMessage());
                mcpClient = null;
                if (mcpProxy != null) {
                    mcpProxy.close();
                    mcpProxy = null;
                }
            }
        }

        LOG.debug("Test infrastructure ready: {}", testName);
    }

    @AfterEach
    void teardownTestInfrastructure() {
        LOG.debug("Tearing down test: {}", testName);

        if (mcpClient != null) {
            try {
                mcpClient.disconnect();
            } catch (Exception e) {
                LOG.warn("Failed to disconnect MCP client: {}", e.getMessage());
            }
            mcpClient = null;
        }
        if (mcpProxy != null) {
            mcpProxy.close();
            mcpProxy = null;
        }

        if (routerClient != null) {
            try {
                routerClient.clearAllTools();
            } catch (Exception e) {
                LOG.warn("Failed to clear tools: {}", e.getMessage());
            }
        }

        LOG.debug("Test teardown complete: {}", testName);
    }

    protected boolean isServerRunning() {
        return serverManager != null && serverManager.isRunning();
    }

    protected String getServerBaseUrl() {
        return serverManager != null ? serverManager.getBaseUrl() : null;
    }

    protected String getServerMcpBaseUrl() {
        return serverManager != null ? serverManager.getMcpBaseUrl() : null;
    }

    protected int getServerHttpPort() {
        return serverManager != null ? serverManager.getHttpPort() : -1;
    }

    protected boolean isMcpClientAvailable() {
        if (mcpClient != null) {
            return true;
        }
        return isServerRunning();
    }

    protected String getLogProfile() {
        return "default";
    }
}
