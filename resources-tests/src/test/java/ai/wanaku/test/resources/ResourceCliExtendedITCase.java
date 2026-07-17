package ai.wanaku.test.resources;

import java.nio.file.Path;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;
import ai.wanaku.test.model.ResourceConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ResourceCliExtendedITCase extends ResourceTestBase {

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
    }

    @DisplayName("Show details of a registered resource via CLI")
    @Test
    void shouldShowResourceViaCli() throws Exception {
        Path testFile = createTestFile("cli-show-test.txt", "Show test content");
        ResourceConfig config = ResourceConfig.builder()
                .name("cli-show-resource")
                .location(testFile.toUri().toString())
                .description("Resource for CLI show test")
                .build();

        routerClient.exposeResource(config);

        CLIResult result = executeWithAuth("resources", "show", "--host", getRouterHost(), "cli-show-resource");

        assertThat(result.isSuccess())
                .as("CLI show command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(result.getCombinedOutput()).contains("cli-show-resource");
    }

    @DisplayName("CLI resources list returns output when no resources exposed")
    @Test
    void shouldListEmptyResources() {
        CLIResult result = executeWithAuth("resources", "list", "--host", getRouterHost());

        assertThat(result.isSuccess())
                .as("CLI list should succeed even with no resources: %s", result.getCombinedOutput())
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
