package ai.wanaku.test.camel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.ResourceReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CIC with file-reading Camel routes exposed as MCP resources.
 * One fixture ({@code file-resource/}) with a file route and resource definition.
 * Uses {@code ${FILE_DIR}} and {@code ${FILE_NAME}} placeholders substituted at runtime.
 *
 * <p>CIC registers resources with URI format: {@code {serviceName}://{resourceName}}.
 *
 * <p>Covers: list, read, non-existent file, DataStore loading.
 */
@QuarkusTest
class CamelFileResourceITCase extends CamelCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(CamelFileResourceITCase.class);

    private static final String SERVICE_NAME = "file-resource-svc";
    private static final String RESOURCE_NAME = "test-file-resource";
    private static final String RESOURCE_URI = SERVICE_NAME + "://" + RESOURCE_NAME;

    @BeforeEach
    void assertInfrastructureAvailable() {
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();
    }

    @DisplayName("Register a file resource via CIC and verify it appears via MCP and REST API")
    @Test
    void shouldRegisterFileResourceViaMcp() throws Exception {
        Path file1 = createTestFile("res-a.txt", "Content A");
        Path file2 = createTestFile("res-b.txt", "Content B");
        Path file3 = createTestFile("res-empty.txt", "");

        startFileResourceCapability("svc-a", "resource-a", file1);
        startFileResourceCapability("svc-b", "resource-b", file2);
        startFileResourceCapability("svc-c", "resource-c", file3);

        mcpClient
                .when()
                .resourcesList()
                .withAssert(page -> {
                    LOG.debug("=== MCP resourcesList response [file-resource]: {}", page.resources());
                    assertThat(page.resources()).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(page.resources()).anyMatch(r -> "svc-a://resource-a".equals(r.uri()));
                    assertThat(page.resources()).anyMatch(r -> "svc-b://resource-b".equals(r.uri()));
                    assertThat(page.resources()).anyMatch(r -> "svc-c://resource-c".equals(r.uri()));
                })
                .send()
                .thenAssertResults();

        List<ResourceReference> resources = routerClient.listResources();
        assertThat(resources).hasSizeGreaterThanOrEqualTo(3);
        assertThat(resources).anyMatch(r -> "resource-a".equals(r.getName()));
        assertThat(resources).anyMatch(r -> "resource-b".equals(r.getName()));
        assertThat(resources).anyMatch(r -> "resource-c".equals(r.getName()));
    }

    @DisplayName("Read a file resource via MCP and verify content matches source file")
    @Test
    void shouldReadFileResourceViaMcp() throws Exception {
        Path testFile = createTestFile("test-read.txt", "Hello Wanaku from CIC resource");
        startFileResourceCapability(SERVICE_NAME, RESOURCE_NAME, testFile);

        mcpClient
                .when()
                .resourcesRead(RESOURCE_URI)
                .withAssert(response -> {
                    LOG.debug("=== MCP resourcesRead response [test-file-resource]: {}", response.contents());
                    assertThat(response.contents()).isNotEmpty();
                    assertThat(response.contents().get(0).asText().text()).contains("Hello Wanaku from CIC resource");
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Read resource pointing to non-existent file and verify error response")
    @Test
    void shouldHandleNonExistentFileResource() throws Exception {
        Path fakeFile = tempDataDir.resolve("nonexistent-" + System.nanoTime() + ".txt");
        startCapability(
                SERVICE_NAME,
                "file-resource",
                Map.of(
                        "FILE_DIR", fakeFile.getParent().toString(),
                        "FILE_NAME", fakeFile.getFileName().toString(),
                        "RESOURCE_NAME", RESOURCE_NAME));

        mcpClient
                .when()
                .resourcesRead(RESOURCE_URI)
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

    @DisplayName("Read two resources alternately and verify content does not become stale")
    @Test
    void shouldSwitchBetweenResourcesWithoutStaleData() throws Exception {
        Path fileA = createTestFile("switch-a.txt", "Alpha content");
        Path fileB = createTestFile("switch-b.txt", "Beta content");

        startFileResourceCapability("switch-svc-a", "switch-res-a", fileA);
        startFileResourceCapability("switch-svc-b", "switch-res-b", fileB);

        // First read
        mcpClient
                .when()
                .resourcesRead("switch-svc-a://switch-res-a")
                .withAssert(r -> {
                    LOG.debug("=== MCP resourcesRead response [1st switch-res-a]: {}", r.contents());
                    assertThat(r.contents()).isNotEmpty();
                    assertThat(r.contents().get(0).asText().text()).contains("Alpha content");
                })
                .send()
                .thenAssertResults();

        mcpClient
                .when()
                .resourcesRead("switch-svc-b://switch-res-b")
                .withAssert(r -> {
                    LOG.debug("=== MCP resourcesRead response [1st switch-res-b]: {}", r.contents());
                    assertThat(r.contents()).isNotEmpty();
                    assertThat(r.contents().get(0).asText().text()).contains("Beta content");
                })
                .send()
                .thenAssertResults();

        // Re-read — must return original content, not stale or empty
        mcpClient
                .when()
                .resourcesRead("switch-svc-a://switch-res-a")
                .withAssert(r -> {
                    LOG.debug("=== MCP resourcesRead response [2nd switch-res-a]: {}", r.contents());
                    assertThat(r.contents())
                            .as("Re-reading resource A should not be empty")
                            .isNotEmpty();
                    assertThat(r.contents().get(0).asText().text())
                            .as("Resource A should still return Alpha, not stale data")
                            .contains("Alpha content");
                })
                .send()
                .thenAssertResults();

        mcpClient
                .when()
                .resourcesRead("switch-svc-b://switch-res-b")
                .withAssert(r -> {
                    LOG.debug("=== MCP resourcesRead response [2nd switch-res-b]: {}", r.contents());
                    assertThat(r.contents())
                            .as("Re-reading resource B should not be empty")
                            .isNotEmpty();
                    assertThat(r.contents().get(0).asText().text())
                            .as("Resource B should still return Beta, not stale data")
                            .contains("Beta content");
                })
                .send()
                .thenAssertResults();
    }

    @DisplayName("Load file resource config from Data Store and verify it works")
    @Test
    void shouldLoadFileResourceFromDataStore() throws Exception {
        assertThat(isDataStoreAvailable())
                .as("Data Store API must be available")
                .isTrue();

        Path testFile = createTestFile("test-ds.txt", "DataStore resource content");

        String routesContent = readFixtureFromClasspath("file-resource/routes.camel.yaml")
                .replace("${FILE_DIR}", testFile.getParent().toString())
                .replace("${FILE_NAME}", testFile.getFileName().toString())
                .replace("${RESOURCE_NAME}", RESOURCE_NAME);
        String rulesContent =
                readFixtureFromClasspath("file-resource/rules.yaml").replace("${RESOURCE_NAME}", RESOURCE_NAME);

        dataStoreClient.upload("test-res-routes.camel.yaml", routesContent);
        dataStoreClient.upload("test-res-rules.yaml", rulesContent);

        String dsServiceName = "ds-resource-svc";
        String dsResourceUri = dsServiceName + "://" + RESOURCE_NAME;

        startCapabilityFromDataStore(
                dsServiceName, "datastore://test-res-routes.camel.yaml", "datastore://test-res-rules.yaml", null);

        mcpClient
                .when()
                .resourcesRead(dsResourceUri)
                .withAssert(response -> {
                    LOG.debug("=== MCP resourcesRead response [DataStore resource]: {}", response.contents());
                    assertThat(response.contents()).isNotEmpty();
                    assertThat(response.contents().get(0).asText().text()).contains("DataStore resource content");
                })
                .send()
                .thenAssertResults();
    }

    // -- Helpers --

    private void startFileResourceCapability(String serviceName, String resourceName, Path testFile) throws Exception {
        startCapability(
                serviceName,
                "file-resource",
                Map.of(
                        "FILE_DIR", testFile.getParent().toString(),
                        "FILE_NAME", testFile.getFileName().toString(),
                        "RESOURCE_NAME", resourceName));
    }

    private Path createTestFile(String filename, String content) throws Exception {
        Path file = tempDataDir.resolve(filename);
        Files.writeString(file, content);
        LOG.debug("Created test file: {}", file);
        return file;
    }
}
