package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.WanakuServerManager;

import org.junit.jupiter.api.extension.ExtensionContext;

public class SharedInfrastructure implements ExtensionContext.Store.CloseableResource {

    private static final Logger LOG = LoggerFactory.getLogger(SharedInfrastructure.class);

    private TestConfiguration config;
    private WanakuServerManager serverManager;
    private Path tempDataDir;

    SharedInfrastructure() {}

    void start() throws Exception {
        LOG.info("=== Starting shared infrastructure (once per module) ===");

        tempDataDir = Files.createTempDirectory("wanaku-test-");
        LOG.debug("Created shared temp directory: {}", tempDataDir);

        TestConfiguration baseConfig = TestConfiguration.fromSystemProperties();
        config = TestConfiguration.builder()
                .artifactsDir(baseConfig.getArtifactsDir())
                .serverBinaryPath(baseConfig.getServerBinaryPath())
                .camelCapabilityJarPath(baseConfig.getCamelCapabilityJarPath())
                .evaluatorWasmPath(baseConfig.getEvaluatorWasmPath())
                .tempDataDir(tempDataDir)
                .defaultTimeout(baseConfig.getDefaultTimeout())
                .mcpIdFilterEnabled(baseConfig.isMcpIdFilterEnabled())
                .forwardHeaders(baseConfig.getForwardHeaders())
                .build();

        LOG.debug("Server binary: {}", config.getServerBinaryPath());

        String externalMgmtPort = System.getProperty("wanaku.test.external.mgmt.port");
        String externalMcpPort = System.getProperty("wanaku.test.external.mcp.port");

        if (externalMgmtPort != null && externalMcpPort != null) {
            serverManager = WanakuServerManager.external(
                    config, Integer.parseInt(externalMgmtPort), Integer.parseInt(externalMcpPort));
            LOG.info("Using external server on management port {} and MCP port {}", externalMgmtPort, externalMcpPort);
            LOG.info("=== Shared infrastructure ready (external) ===");
            return;
        }

        if (config.getServerBinaryPath() == null
                || !config.getServerBinaryPath().toFile().exists()) {
            LOG.info("Server binary not available, skipping infrastructure setup");
            return;
        }

        serverManager = new WanakuServerManager(config);
        serverManager.prepare();
        serverManager.start("shared");
        LOG.info(
                "Wanaku server started on management port {} and MCP port {}",
                serverManager.getHttpPort(),
                serverManager.getMcpPort());

        LOG.info("=== Shared infrastructure ready ===");
    }

    @Override
    public void close() {
        LOG.info("=== Tearing down shared infrastructure ===");

        if (serverManager != null) {
            serverManager.stop();
        }

        if (tempDataDir != null) {
            try {
                deleteRecursively(tempDataDir);
            } catch (IOException e) {
                LOG.warn("Failed to cleanup shared temp directory: {}", e.getMessage());
            }
        }

        LOG.info("=== Shared infrastructure teardown complete ===");
    }

    public TestConfiguration getConfig() {
        return config;
    }

    public WanakuServerManager getServerManager() {
        return serverManager;
    }

    public Path getTempDataDir() {
        return tempDataDir;
    }

    public String getBaseUrl() {
        return serverManager != null ? serverManager.getBaseUrl() : null;
    }

    public String getMcpBaseUrl() {
        return serverManager != null ? serverManager.getMcpBaseUrl() : null;
    }

    public int getHttpPort() {
        return serverManager != null ? serverManager.getHttpPort() : -1;
    }

    public boolean isServerRunning() {
        return serverManager != null && serverManager.isRunning();
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (IOException e) {
                        LOG.warn("Failed to delete: {}", p);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
