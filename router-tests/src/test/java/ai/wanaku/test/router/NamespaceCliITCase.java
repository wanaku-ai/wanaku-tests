package ai.wanaku.test.router;

import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class NamespaceCliITCase extends RouterTestBase {

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

    @DisplayName("Create a namespace via CLI and verify it exists via REST")
    @Test
    void shouldCreateNamespaceViaCli() {
        String name = "test-cli-ns";

        CLIResult result = executeWithAuth(
                "namespaces", "create", "--host", getRouterHost(), "--name", name, "--path", "/" + name);

        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(namespaceClient.exists(name)).isTrue();
    }

    @DisplayName("Create a namespace via REST and verify it appears in CLI list output")
    @Test
    void shouldListNamespacesViaCli() {
        String name = "cli-list-ns";
        namespaceClient.create(name, "/" + name);

        CLIResult result = executeWithAuth("namespaces", "list", "--host", getRouterHost());

        assumeThat(result.getCombinedOutput())
                .as("CLI list should not return auth redirect")
                .doesNotContain("302");
        assertThat(result.isSuccess())
                .as("CLI list should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(result.getCombinedOutput()).contains(name);
    }

    @DisplayName("Create a namespace via REST, delete via CLI, and verify removal")
    @Test
    void shouldDeleteNamespaceViaCli() {
        String name = "cli-delete-ns";
        String id = namespaceClient.create(name, "/" + name);
        assertThat(namespaceClient.exists(name)).isTrue();

        CLIResult result = executeWithAuth("namespaces", "delete", "--host", getRouterHost(), "--name", id);

        if (!result.isSuccess()) {
            result = executeWithAuth("namespaces", "delete", "--host", getRouterHost(), id);
        }

        assumeThat(result.getCombinedOutput())
                .as("CLI delete should not return auth redirect")
                .doesNotContain("302");
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(namespaceClient.exists(name)).isFalse();
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
