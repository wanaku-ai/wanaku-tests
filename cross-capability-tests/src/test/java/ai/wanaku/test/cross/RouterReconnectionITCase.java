package ai.wanaku.test.cross;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.HttpToolConfig;
import ai.wanaku.test.model.ResourceConfig;
import ai.wanaku.test.model.ToolInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
class RouterReconnectionITCase extends CrossCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(RouterReconnectionITCase.class);

    @BeforeEach
    void assumeInfrastructureAvailable() {
        assumeTrue(isRouterAvailable(), "Router must be available");
        assumeTrue(isHttpToolServiceAvailable(), "HTTP capability must be available");
        assumeTrue(isFileProviderAvailable(), "File provider JAR must be available");
        assumeTrue(isCamelCapabilityAvailable(), "Camel capability JAR must be available");
    }

    @DisplayName("All capabilities should reconnect after router restart")
    @Test
    void shouldReconnectAllCapabilitiesAfterRouterRestart() throws Exception {
        startResourceProvider();
        startCamelCapability("simple-tool", "simple-tool");
        registerInitialCapabilities();
        verifyCapabilitiesRegistered();

        restartRouterWithSamePorts("RouterReconnectionITCase-restart");
        verifyCapabilitiesReconnected();
    }

    private void registerInitialCapabilities() throws Exception {
        LOG.info("Registering HTTP tool and file resource");

        routerClient.registerTool(HttpToolConfig.builder()
                .name("test-http-tool")
                .description("Test HTTP tool for reconnection")
                .uri("https://httpbin.org/get")
                .method("GET")
                .build());

        Path testFile = createTestFile("test-resource.txt", "Test content for reconnection");
        routerClient.exposeResource(ResourceConfig.builder()
                .location(testFile.toUri().toString())
                .name("test-resource")
                .description("Test resource for reconnection")
                .mimeType("text/plain")
                .build());
    }

    private void verifyCapabilitiesRegistered() {
        LOG.info("Verifying initial capability registration");

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(routerClient.isCapabilityRegistered("http")).isTrue());
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(routerClient.isCapabilityRegistered("file")).isTrue());
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(routerClient.isCapabilityRegistered("simple-tool"))
                        .isTrue());

        List<ToolInfo> toolsBeforeRestart = routerClient.listTools();
        assertThat(toolsBeforeRestart).anyMatch(tool -> "test-http-tool".equals(tool.getName()));
        assertThat(toolsBeforeRestart).anyMatch(tool -> "simple-greeting".equals(tool.getName()));

        assertThat(routerClient.resourceExists("test-resource")).isTrue();
    }

    private void verifyCapabilitiesReconnected() {
        LOG.info("Verifying capability reconnection after router restart");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(routerClient.isCapabilityRegistered("http")).isTrue());
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(routerClient.isCapabilityRegistered("file")).isTrue());
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(routerClient.isCapabilityRegistered("simple-tool"))
                        .isTrue());

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<ToolInfo> toolsAfterRestart = routerClient.listTools();
                    assertThat(toolsAfterRestart).anyMatch(tool -> "test-http-tool".equals(tool.getName()));
                    assertThat(toolsAfterRestart).anyMatch(tool -> "simple-greeting".equals(tool.getName()));
                });

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(routerClient.resourceExists("test-resource")).isTrue());
    }
}
