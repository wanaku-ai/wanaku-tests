package ai.wanaku.test.resources;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.ResourceConfig;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for resource operations via MCP protocol.
 * MCP client availability is asserted (not assumed) — tests fail if MCP is unavailable.
 * File provider availability is assumed — tests skip if it is not present.
 */
@QuarkusTest
class McpResourceITCase extends ResourceTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(McpResourceITCase.class);

    @BeforeEach
    void checkInfrastructureAvailable() {
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();
        Assumptions.assumeTrue(isFileProviderAvailable(), "File provider must be available");
        assertThat(isMcpClientAvailable()).as("MCP client must be available").isTrue();
    }

    @DisplayName("Expose a resource and verify it appears via MCP resourcesList")
    @Test
    void shouldListResourcesViaMcp() throws Exception {
        // Given
        Path testFile = createTestFile("test-mcp-list.txt", "MCP list test");
        ResourceConfig config = ResourceConfig.builder()
                .name("mcp-list-resource")
                .location(testFile.toUri().toString())
                .mimeType("text/plain")
                .build();

        routerClient.exposeResource(config);

        // When / Then
        mcpClient
                .when()
                .resourcesList()
                .withAssert(page -> {
                    LOG.debug("=== MCP resourcesList response [mcp-list-resource]: {}", page.resources());
                    assertThat(page.resources()).isNotEmpty();
                    assertThat(page.resources()).anyMatch(r -> r.name().equals("mcp-list-resource"));
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Read a text file resource via MCP and verify content matches")
    @Test
    void shouldReadTextFileViaMcp() throws Exception {
        // Given
        Path testFile = createTestFile("read-test.txt", "Hello Wanaku Resources");
        // Use absolute path, not file:// URI — FileResourceDelegate uses new File(location)
        // which doesn't strip the file:// prefix, causing a broken Camel endpoint URI
        String filePath = testFile.toAbsolutePath().toString();

        ResourceConfig config = ResourceConfig.builder()
                .name("readable-text-resource")
                .location(filePath)
                .mimeType("text/plain")
                .build();

        routerClient.exposeResource(config);

        // When / Then
        mcpClient
                .when()
                .resourcesRead(filePath)
                .withAssert(response -> {
                    LOG.debug("=== MCP resourcesRead response [readable-text-resource]: {}", response.contents());
                    assertThat(response.contents()).isNotEmpty();
                    String text = response.contents().get(0).asText().text();
                    assertThat(text).contains("Hello Wanaku Resources");
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Read a resource pointing to a non-existent file and verify error is returned")
    @Test
    void shouldHandleNonExistentFile() throws Exception {
        // Given
        // Use absolute path, not file:// URI — FileResourceDelegate uses new File(location)
        String nonExistentUri = "/tmp/wanaku-does-not-exist-" + System.nanoTime() + ".txt";

        ResourceConfig config = ResourceConfig.builder()
                .name("missing-file-resource")
                .location(nonExistentUri)
                .mimeType("text/plain")
                .build();

        routerClient.exposeResource(config);

        // When / Then — reading a non-existent file should return an error
        mcpClient
                .when()
                .resourcesRead(nonExistentUri)
                .withErrorAssert(error -> {
                    LOG.debug(
                            "=== MCP resourcesRead error [non-existent]: code={}, message={}",
                            error.code(),
                            error.message());
                    assertThat(error.message()).isNotEmpty();
                })
                .send()
                .thenAssertResults();
    }
}
