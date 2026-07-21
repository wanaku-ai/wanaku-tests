package ai.wanaku.test.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for HTTP tool registration via CLI.
 * Validates that CLI commands work correctly for tool management
 * and that CLI output is properly captured from subprocess.
 */
@QuarkusTest
class HttpToolCliITCase extends HttpCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(HttpToolCliITCase.class);
    private CLIExecutor cliExecutor;
    private String authToken;

    @BeforeEach
    void setupCli() {
        cliExecutor = CLIExecutor.createDefault();
        assertThat(cliExecutor.isAvailable()).as("CLI must be available").isTrue();
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();

        if (keycloakManager != null && keycloakManager.isRunning()) {
            authToken = keycloakManager.getMcpToken();
        }
    }

    @DisplayName("Register a tool via CLI and verify it appears in CLI list output")
    @Test
    void shouldRegisterHttpToolViaCli() {
        // Given
        String toolName = "cli-weather-api";
        String toolUri = "https://httpbin.org/get";

        // When
        CLIResult result = executeWithAuth(
                "tools",
                "add",
                "--host",
                getRouterHost(),
                "-N",
                "default",
                "--name",
                toolName,
                "--type",
                "http",
                "--uri",
                toolUri,
                "--description",
                "Weather API registered via CLI");

        // Then
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(routerClient.toolExists(toolName)).isTrue();

        // Verify the tool shows up in CLI list output
        CLIResult listResult = executeWithAuth("tools", "list", "--host", getRouterHost());
        assertThat(listResult.isSuccess()).as("CLI list should succeed").isTrue();
        assertThat(listResult.getCombinedOutput())
                .as("CLI list output should contain the registered tool")
                .contains(toolName);
    }

    @DisplayName("Register a tool via CLI with a named namespace")
    @Test
    void shouldRegisterToolWithNamespace() {
        String toolName = "cli-ns-tool";
        String toolUri = "https://httpbin.org/get";

        CLIResult result = executeWithAuth(
                "tools",
                "add",
                "--host",
                getRouterHost(),
                "-N",
                "public",
                "--name",
                toolName,
                "--type",
                "http",
                "--uri",
                toolUri,
                "--description",
                "Tool registered in public namespace");

        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(routerClient.toolExists(toolName)).isTrue();
    }

    @DisplayName("List multiple tools via CLI and verify all are captured in output")
    @Test
    void shouldListToolsViaCli() {
        // Given - register multiple tools via REST
        String[] toolNames = {"cli-list-tool-alpha", "cli-list-tool-beta", "cli-list-tool-gamma"};
        for (String name : toolNames) {
            routerClient.registerTool(ai.wanaku.test.model.HttpToolConfig.builder()
                    .name(name)
                    .uri("https://httpbin.org/get")
                    .build());
        }

        // When
        CLIResult result = executeWithAuth("tools", "list", "--host", getRouterHost());

        // Then
        assertThat(result.isSuccess())
                .as("CLI list command should succeed: %s", result.getCombinedOutput())
                .isTrue();

        String output = result.getCombinedOutput();
        assertThat(output).as("CLI output must not be empty").isNotEmpty();

        for (String name : toolNames) {
            assertThat(output)
                    .as("CLI list output should contain tool '%s'", name)
                    .contains(name);
        }
    }

    @DisplayName("Remove a tool via CLI and verify it no longer appears in CLI list output")
    @Test
    void shouldRemoveToolViaCli() {
        // Given - register a tool first via REST
        String toolName = "cli-remove-test";
        routerClient.registerTool(ai.wanaku.test.model.HttpToolConfig.builder()
                .name(toolName)
                .uri("https://httpbin.org/delete")
                .build());
        assertThat(routerClient.toolExists(toolName)).isTrue();

        // When
        CLIResult result = executeWithAuth("tools", "remove", "--host", getRouterHost(), "--name", toolName);

        // Then
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(routerClient.toolExists(toolName)).isFalse();

        // Verify the tool no longer shows up in CLI list output
        CLIResult listResult = executeWithAuth("tools", "list", "--host", getRouterHost());
        assertThat(listResult.isSuccess()).as("CLI list should succeed").isTrue();
        assertThat(listResult.getCombinedOutput())
                .as("CLI list output should not contain the removed tool")
                .doesNotContain(toolName);
    }

    private String getRouterHost() {
        return routerManager != null ? routerManager.getBaseUrl() : "http://localhost:8080";
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
}
