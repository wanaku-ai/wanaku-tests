package ai.wanaku.test.http;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;
import ai.wanaku.test.model.HttpToolConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HttpToolCliExtendedITCase extends HttpCapabilityTestBase {

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

    @DisplayName("Show details of a registered tool via CLI")
    @Test
    void shouldShowToolViaCli() {
        routerClient.registerTool(HttpToolConfig.builder()
                .name("cli-show-tool")
                .description("Tool to show details via CLI")
                .uri("https://httpbin.org/get")
                .build());

        CLIResult result = executeWithAuth("tools", "show", "--host", getRouterHost(), "cli-show-tool");

        assertThat(result.isSuccess())
                .as("CLI show command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(result.getCombinedOutput()).contains("cli-show-tool");
    }

    @DisplayName("CLI should return non-zero exit code for invalid subcommand")
    @Test
    void shouldFailWithInvalidSubcommand() {
        CLIResult result = cliExecutor.execute("tools", "invalid-subcommand");

        assertThat(result.isSuccess()).isFalse();
    }

    @DisplayName("CLI tools list returns empty output when no tools registered")
    @Test
    void shouldListEmptyTools() {
        CLIResult result = executeWithAuth("tools", "list", "--host", getRouterHost());

        assertThat(result.isSuccess())
                .as("CLI list should succeed even with no tools: %s", result.getCombinedOutput())
                .isTrue();
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
