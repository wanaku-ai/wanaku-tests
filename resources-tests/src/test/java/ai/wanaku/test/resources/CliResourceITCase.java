package ai.wanaku.test.resources;

import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.model.ResourceConfig;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests for resource operations via CLI.
 * CLI availability is asserted (not assumed) — tests fail if CLI is unavailable.
 */
@QuarkusTest
class CliResourceITCase extends ResourceTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(CliResourceITCase.class);
    private static final String NAMESPACE_NAME = "resources-cli-test-ns";

    private CLIExecutor cliExecutor;
    private String authToken;

    @BeforeEach
    void checkInfrastructureAvailable() {
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();
        assertThat(isFileProviderAvailable())
                .as("File provider must be available")
                .isTrue();

        cliExecutor = CLIExecutor.createDefault();
        assertThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();

        if (keycloakManager != null && keycloakManager.isRunning()) {
            authToken = keycloakManager.getMcpToken();
        }

        String nsId = getOrCreateNamespaceId(NAMESPACE_NAME);
        assumeThat(nsId).as("Test namespace must be available").isNotNull();
    }

    private String getOrCreateNamespaceId(String name) {
        NamespaceClient nsClient = new NamespaceClient(routerManager.getBaseUrl(), authToken);
        try {
            List<JsonNode> namespaces = nsClient.list();
            for (JsonNode ns : namespaces) {
                if (ns.has("name") && name.equals(ns.get("name").asText())) {
                    return ns.has("id") ? ns.get("id").asText() : null;
                }
            }
            return nsClient.create(name, name);
        } catch (Exception e) {
            LOG.warn("Failed to get/create namespace '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private CLIResult executeWithAuth(String... args) {
        if (authToken != null) {
            String[] authArgs = new String[args.length + 2];
            System.arraycopy(args, 0, authArgs, 0, args.length);
            authArgs[args.length] = "--token";
            authArgs[args.length + 1] = authToken;
            return cliExecutor.execute(authArgs);
        }
        return cliExecutor.execute(args);
    }

    @DisplayName("Expose a resource via CLI and verify it appears in the list")
    @Test
    void shouldExposeResourceViaCli() throws Exception {
        // Given
        String routerHost = routerManager.getBaseUrl();

        // When
        CLIResult result = executeWithAuth(
                "resources",
                "expose",
                "--host",
                routerHost,
                "-N",
                NAMESPACE_NAME,
                "--name",
                "test-cli-resource",
                "--description",
                "CLI test resource",
                "--location",
                "file:///tmp/test.txt",
                "--type",
                "file");

        // Then
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(routerClient.resourceExists("test-cli-resource")).isTrue();

        // Verify the resource also shows up in CLI list output
        CLIResult listResult = executeWithAuth("resources", "list", "--host", routerHost);
        assertThat(listResult.isSuccess()).as("CLI list should succeed").isTrue();
        assertThat(listResult.getCombinedOutput())
                .as("CLI list output should contain the exposed resource")
                .contains("test-cli-resource");
    }

    @DisplayName("List multiple resources via CLI and verify all are captured in output")
    @Test
    void shouldListResourcesViaCli() throws Exception {
        // Given - expose multiple resources via REST
        String[] names = {"cli-list-alpha", "cli-list-beta", "cli-list-gamma"};
        for (String name : names) {
            Path testFile = createTestFile(name + ".txt", "content for " + name);
            ResourceConfig config = ResourceConfig.builder()
                    .name(name)
                    .location(testFile.toUri().toString())
                    .build();
            routerClient.exposeResource(config);
        }

        // When
        String routerHost = routerManager.getBaseUrl();
        CLIResult result = executeWithAuth("resources", "list", "--host", routerHost);

        // Then - verify output was captured via subprocess
        assertThat(result.isSuccess())
                .as("CLI list command should succeed: %s", result.getCombinedOutput())
                .isTrue();

        String output = result.getCombinedOutput();
        assertThat(output).as("CLI output must not be empty").isNotEmpty();

        // Verify every exposed resource appears in the output
        for (String name : names) {
            assertThat(output)
                    .as("CLI list output should contain resource '%s'", name)
                    .contains(name);
        }
    }

    @DisplayName("Remove a resource via CLI and verify it no longer exists")
    @Test
    void shouldRemoveResourceViaCli() throws Exception {
        // Given - expose a resource first via REST
        Path testFile = createTestFile("test-cli-remove.txt", "CLI remove test");
        ResourceConfig config = ResourceConfig.builder()
                .name("cli-remove-resource")
                .location(testFile.toUri().toString())
                .build();

        routerClient.exposeResource(config);
        assertThat(routerClient.resourceExists("cli-remove-resource")).isTrue();

        // When
        String routerHost = routerManager.getBaseUrl();
        CLIResult result =
                executeWithAuth("resources", "remove", "--host", routerHost, "--name", "cli-remove-resource");

        // Then
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(routerClient.resourceExists("cli-remove-resource")).isFalse();

        // Verify the resource no longer shows up in CLI list output
        CLIResult listResult = executeWithAuth("resources", "list", "--host", routerHost);
        assertThat(listResult.isSuccess()).as("CLI list should succeed").isTrue();
        assertThat(listResult.getCombinedOutput())
                .as("CLI list output should not contain the removed resource")
                .doesNotContain("cli-remove-resource");
    }
}
