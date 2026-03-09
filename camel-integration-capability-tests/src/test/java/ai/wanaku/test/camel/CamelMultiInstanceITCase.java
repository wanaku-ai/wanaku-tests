package ai.wanaku.test.camel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for running multiple CIC instances simultaneously.
 * Cross-fixture by design — verifies the "one JAR, many roles" pattern.
 *
 * <p>Uses: simple-tool/, multi-instance-tool/, file-resource/ fixtures.
 */
@QuarkusTest
class CamelMultiInstanceITCase extends CamelCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(CamelMultiInstanceITCase.class);

    @BeforeEach
    void assertInfrastructureAvailable() {
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();
    }

    @DisplayName("Run 2 CIC tool instances simultaneously and invoke each independently")
    @Test
    void shouldRunMultipleToolsSimultaneously() throws Exception {
        startCapability("tool-a-svc", "simple-tool");
        startCapability("tool-b-svc", "multi-instance-tool");

        mcpClient
                .when()
                .toolsList()
                .withAssert(page -> {
                    LOG.debug("=== MCP toolsList response [multi-instance]: {}", page.tools());
                    assertThat(page.tools()).anyMatch(tool -> "simple-greeting".equals(tool.name()));
                    assertThat(page.tools()).anyMatch(tool -> "multi-tool".equals(tool.name()));
                })
                .send()
                .thenAssertResults();

        mcpClient
                .when()
                .toolsCall("simple-greeting")
                .withArguments(Map.of())
                .withAssert(response -> {
                    LOG.debug("=== MCP toolsCall response [simple-greeting]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content().get(0).asText().text()).contains("Hello from CIC!");
                })
                .send()
                .thenAssertResults();

        mcpClient
                .when()
                .toolsCall("multi-tool")
                .withArguments(Map.of())
                .withAssert(response -> {
                    LOG.debug("=== MCP toolsCall response [multi-tool]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content().get(0).asText().text()).contains("Response from tool instance");
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Run CIC tool and file resource simultaneously")
    @Test
    void shouldRunToolAndResourceSimultaneously() throws Exception {
        startCapability("tool-svc", "simple-tool");

        Path testFile = tempDataDir.resolve("multi-resource.txt");
        Files.writeString(testFile, "Multi-instance resource content");

        String resourceServiceName = "resource-svc";
        startCapability(
                resourceServiceName,
                "file-resource",
                Map.of(
                        "FILE_DIR", testFile.getParent().toString(),
                        "FILE_NAME", testFile.getFileName().toString(),
                        "RESOURCE_NAME", "test-file-resource"));

        mcpClient
                .when()
                .toolsList()
                .withAssert(page -> {
                    LOG.debug("=== MCP toolsList response [tool+resource]: {}", page.tools());
                    assertThat(page.tools()).anyMatch(tool -> "simple-greeting".equals(tool.name()));
                })
                .send()
                .thenAssertResults();

        mcpClient
                .when()
                .resourcesList()
                .withAssert(page -> {
                    LOG.debug("=== MCP resourcesList response [test-file-resource]: {}", page.resources());
                    assertThat(page.resources()).anyMatch(r -> "test-file-resource".equals(r.name()));
                })
                .send()
                .thenAssertResults();

        mcpClient
                .when()
                .toolsCall("simple-greeting")
                .withArguments(Map.of())
                .withAssert(response -> {
                    LOG.debug("=== MCP toolsCall response [simple-greeting]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content().get(0).asText().text()).contains("Hello from CIC!");
                })
                .send()
                .thenAssertResults();

        String resourceUri = resourceServiceName + "://test-file-resource";
        mcpClient
                .when()
                .resourcesRead(resourceUri)
                .withAssert(response -> {
                    LOG.debug("=== MCP resourcesRead response [test-file-resource]: {}", response.contents());
                    assertThat(response.contents()).isNotEmpty();
                    assertThat(response.contents().get(0).asText().text()).contains("Multi-instance resource content");
                })
                .send()
                .thenAssertResults();
    }
}
