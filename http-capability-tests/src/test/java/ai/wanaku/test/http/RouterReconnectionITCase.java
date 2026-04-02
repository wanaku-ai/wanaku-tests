package ai.wanaku.test.http;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.fixtures.TestFixtures;
import ai.wanaku.test.managers.CamelCapabilityManager;
import ai.wanaku.test.managers.ResourceProviderManager;
import ai.wanaku.test.model.HttpToolConfig;
import ai.wanaku.test.model.ResourceConfig;
import ai.wanaku.test.model.ToolInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;


@DisplayName("Router Reconnection Integration Tests")
class RouterReconnectionITCase extends HttpCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(RouterReconnectionITCase.class);
    private static final Path FIXTURES_TARGET_DIR = Path.of("target", "test-fixtures");

    private ResourceProviderManager resourceProviderManager;
    private CamelCapabilityManager camelCapabilityManager;

    @BeforeEach
    void assumeAllComponentsAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
        assumeThat(isHttpToolServiceAvailable())
                .as("HTTP capability must be available")
                .isTrue();
        assumeThat(config.getFileProviderJarPath())
                .as("File provider JAR must be available")
                .isNotNull();
        assumeThat(config.getFileProviderJarPath().toFile().exists())
                .as("File provider JAR must exist")
                .isTrue();
        assumeThat(config.getCamelCapabilityJarPath())
                .as("Camel capability JAR must be available")
                .isNotNull();
        assumeThat(config.getCamelCapabilityJarPath().toFile().exists())
                .as("Camel capability JAR must exist")
                .isTrue();
    }

    @DisplayName("All capabilities should reconnect after router restart")
    @Test
    void shouldReconnectAllCapabilitiesAfterRouterRestart() throws Exception {
        
        LOG.info("=== Step 1: Router and HTTP capability already running ===");

        
        HttpToolConfig httpTool = HttpToolConfig.builder()
                .name("test-http-tool")
                .description("Test HTTP tool for reconnection")
                .uri("https://httpbin.org/get")
                .method("GET")
                .build();
        routerClient.registerTool(httpTool);
        assertThat(routerClient.toolExists("test-http-tool")).isTrue();
        LOG.info("HTTP tool registered: test-http-tool");

        
        LOG.info("=== Step 2: Starting resource provider ===");
        OidcCredentials oidcCredentials = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            oidcCredentials = keycloakManager.getServiceCredentials();
        }

        resourceProviderManager = new ResourceProviderManager(config);
        resourceProviderManager.prepare(
                "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials);
        resourceProviderManager.setLogContext("file-provider", "RouterReconnectionITCase", "file-provider");
        resourceProviderManager.start("RouterReconnectionITCase");

        
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> routerClient.isCapabilityRegistered("file"));
        LOG.info("Resource provider registered");

        
        Path testFile = tempDataDir.resolve("test-resource.txt");
        java.nio.file.Files.writeString(testFile, "Test content for reconnection");
        ResourceConfig resourceConfig = ResourceConfig.builder()
                .location(testFile.toUri().toString())
                .name("test-resource")
                .description("Test resource for reconnection")
                .mimeType("text/plain")
                .build();
        routerClient.exposeResource(resourceConfig);
        assertThat(routerClient.resourceExists("test-resource")).isTrue();
        LOG.info("Resource registered: test-resource");

        
        LOG.info("=== Step 3: Starting camel integration capability ===");
        Path fixtureDir = TestFixtures.load("simple-tool", FIXTURES_TARGET_DIR);
        Path routesRef = fixtureDir.resolve("routes.camel.yaml");
        Path rulesRef = fixtureDir.resolve("rules.yaml");

        camelCapabilityManager = new CamelCapabilityManager(config);
        camelCapabilityManager.prepare(
                "simple-tool",
                "localhost",
                routerManager.getHttpPort(),
                routerManager.getGrpcPort(),
                oidcCredentials,
                "file://" + routesRef.toAbsolutePath(),
                rulesRef.toFile().exists() ? "file://" + rulesRef.toAbsolutePath() : null,
                null);
        camelCapabilityManager.setLogContext("camel-capability", "RouterReconnectionITCase", "simple-tool");
        camelCapabilityManager.start("simple-tool");

        
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> routerClient.isCapabilityRegistered("simple-tool"));
        LOG.info("Camel capability registered: simple-tool");

        
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> routerClient.listTools().stream().anyMatch(t -> "simple-tool".equals(t.getType())));
        LOG.info("Camel tools registered");

        
        LOG.info("=== Step 4: Verifying all capabilities are registered ===");
        assertThat(routerClient.isCapabilityRegistered("http")).isTrue();
        assertThat(routerClient.isCapabilityRegistered("file")).isTrue();
        assertThat(routerClient.isCapabilityRegistered("simple-tool")).isTrue();

        List<ToolInfo> toolsBeforeRestart = routerClient.listTools();
        assertThat(toolsBeforeRestart).hasSizeGreaterThanOrEqualTo(2); 
        LOG.info("Tools before restart: {}", toolsBeforeRestart.size());

        int resourcesBeforeRestart = routerClient.listResources().size();
        assertThat(resourcesBeforeRestart).isGreaterThanOrEqualTo(1); 
        LOG.info("Resources before restart: {}", resourcesBeforeRestart);

        
        LOG.info("=== Step 5: Stopping router ===");
        routerManager.stop();

        Thread.sleep(2000);
        LOG.info("Router stopped");

        
        LOG.info("=== Step 6: Restarting router ===");
        routerManager.prepare(); // Re-allocate ports if needed
        routerManager.start("RouterReconnectionITCase-restart");
        LOG.info("Router restarted on port {}", routerManager.getHttpPort());

        
        routerClient = new ai.wanaku.test.client.RouterClient(routerManager.getBaseUrl());

        
        LOG.info("=== Step 7: Verifying capabilities reconnect ===");

        
        LOG.info("Waiting for HTTP capability to reconnect...");
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(routerClient.isCapabilityRegistered("http"))
                        .as("HTTP capability should reconnect")
                        .isTrue());
        LOG.info("✓ HTTP capability reconnected");

        
        LOG.info("Waiting for resource provider to reconnect...");
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(routerClient.isCapabilityRegistered("file"))
                        .as("File provider should reconnect")
                        .isTrue());
        LOG.info("✓ Resource provider reconnected");

        
        LOG.info("Waiting for Camel capability to reconnect...");
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(routerClient.isCapabilityRegistered("simple-tool"))
                        .as("Camel capability should reconnect")
                        .isTrue());
        LOG.info("✓ Camel capability reconnected");

        
        LOG.info("=== Step 8: Verifying tools and resources after reconnection ===");

    
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<ToolInfo> toolsAfterRestart = routerClient.listTools();
                    assertThat(toolsAfterRestart)
                            .as("Tools should be re-registered after reconnection")
                            .hasSizeGreaterThanOrEqualTo(toolsBeforeRestart.size());
                });

        List<ToolInfo> toolsAfterRestart = routerClient.listTools();
        LOG.info("Tools after restart: {}", toolsAfterRestart.size());

       
        assertThat(routerClient.toolExists("test-http-tool"))
                .as("HTTP tool should persist after router restart")
                .isTrue();
        LOG.info("✓ HTTP tool persisted");

       
        assertThat(toolsAfterRestart.stream().anyMatch(t -> "simple-tool".equals(t.getType())))
                .as("Camel tools should be re-registered")
                .isTrue();
        LOG.info("✓ Camel tools re-registered");

        
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    int resourcesAfterRestart = routerClient.listResources().size();
                    assertThat(resourcesAfterRestart)
                            .as("Resources should be re-registered after reconnection")
                            .isGreaterThanOrEqualTo(resourcesBeforeRestart);
                });

        int resourcesAfterRestart = routerClient.listResources().size();
        LOG.info("Resources after restart: {}", resourcesAfterRestart);

        
        assertThat(routerClient.resourceExists("test-resource"))
                .as("Resource should persist after router restart")
                .isTrue();
        LOG.info("✓ Resource persisted");

        LOG.info("=== ✓ All capabilities successfully reconnected after router restart ===");

        
        if (camelCapabilityManager != null) {
            try {
                String deregToken = null;
                if (keycloakManager != null && keycloakManager.isRunning()) {
                    deregToken = keycloakManager.getMcpToken();
                }
                routerClient.deregisterCapability("simple-tool", deregToken);
            } catch (Exception e) {
                LOG.warn("Failed to deregister Camel capability: {}", e.getMessage());
            }
            camelCapabilityManager.stop();
        }

        if (resourceProviderManager != null) {
            resourceProviderManager.stop();
        }
    }

    @AfterEach
    void cleanupAdditionalManagers() throws IOException {
        
        if (camelCapabilityManager != null) {
            try {
                camelCapabilityManager.stop();
            } catch (Exception e) {
                LOG.warn("Failed to stop Camel capability: {}", e.getMessage());
            }
            camelCapabilityManager = null;
        }

        if (resourceProviderManager != null) {
            try {
                resourceProviderManager.stop();
            } catch (Exception e) {
                LOG.warn("Failed to stop resource provider: {}", e.getMessage());
            }
            resourceProviderManager = null;
        }
    }
}

