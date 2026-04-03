package ai.wanaku.test.resources;

import java.nio.file.Path;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;
import ai.wanaku.test.model.ResourceConfig;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for resource operations via CLI.
 * CLI availability is asserted (not assumed) — tests fail if CLI is unavailable.
 * File provider availability is assumed — tests skip if it is not present.
 */
@QuarkusTest
class CliResourceITCase extends ResourceTestBase {

    @BeforeEach
    void checkInfrastructureAvailable() {
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();
        Assumptions.assumeTrue(isFileProviderAvailable(), "File provider must be available");
    }

    @DisplayName("Expose a resource via CLI and verify it appears in the list")
    @Test
    void shouldExposeResourceViaCli() throws Exception {
        CLIExecutor cliExecutor = CLIExecutor.createDefault();
        assertThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();

        // Given
        String routerHost = routerManager.getBaseUrl();

        // When
        CLIResult result = cliExecutor.execute(
                "resources",
                "expose",
                "--host",
                routerHost,
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
        CLIResult listResult = cliExecutor.execute("resources", "list", "--host", routerHost);
        assertThat(listResult.isSuccess()).as("CLI list should succeed").isTrue();
        assertThat(listResult.getCombinedOutput())
                .as("CLI list output should contain the exposed resource")
                .contains("test-cli-resource");
    }

    @DisplayName("List multiple resources via CLI and verify all are captured in output")
    @Test
    void shouldListResourcesViaCli() throws Exception {
        CLIExecutor cliExecutor = CLIExecutor.createDefault();
        assertThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();

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
        CLIResult result = cliExecutor.execute("resources", "list", "--host", routerHost);

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
        CLIExecutor cliExecutor = CLIExecutor.createDefault();
        assertThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();

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
                cliExecutor.execute("resources", "remove", "--host", routerHost, "--name", "cli-remove-resource");

        // Then
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(routerClient.resourceExists("cli-remove-resource")).isFalse();

        // Verify the resource no longer shows up in CLI list output
        CLIResult listResult = cliExecutor.execute("resources", "list", "--host", routerHost);
        assertThat(listResult.isSuccess()).as("CLI list should succeed").isTrue();
        assertThat(listResult.getCombinedOutput())
                .as("CLI list output should not contain the removed resource")
                .doesNotContain("cli-remove-resource");
    }
}
