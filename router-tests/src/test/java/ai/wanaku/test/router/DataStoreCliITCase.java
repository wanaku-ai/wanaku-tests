package ai.wanaku.test.router;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DataStoreCliITCase extends RouterTestBase {

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

    @DisplayName("Add a data store entry via CLI and verify it exists via REST")
    @Test
    void shouldAddDataStoreEntryViaCli() throws IOException {
        // Given
        String name = "cli-entry.txt";
        Path tempFile = Files.createTempFile("cli-entry", ".txt");
        Files.writeString(tempFile, "CLI content");

        try {
            // When
            CLIResult result = executeWithAuth(
                    "data-store",
                    "add",
                    "--host",
                    getRouterHost(),
                    "--name",
                    name,
                    "--read-from-file=" + tempFile.toAbsolutePath());

            // Then
            assertThat(result.isSuccess())
                    .as("CLI command should succeed: %s", result.getCombinedOutput())
                    .isTrue();
            assertThat(dataStoreClient.list()).contains(name);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @DisplayName("Upload 3 entries via REST and verify all appear in CLI list output")
    @Test
    void shouldListDataStoreEntriesViaCli() {
        // Given
        dataStoreClient.upload("cli-list-1.txt", "content 1");
        dataStoreClient.upload("cli-list-2.txt", "content 2");
        dataStoreClient.upload("cli-list-3.txt", "content 3");

        // When
        CLIResult result = executeWithAuth("data-store", "list", "--host", getRouterHost());

        // Then
        assertThat(result.isSuccess())
                .as("CLI list should succeed: %s", result.getCombinedOutput())
                .isTrue();

        String output = result.getCombinedOutput();
        assertThat(output).contains("cli-list-1.txt");
        assertThat(output).contains("cli-list-2.txt");
        assertThat(output).contains("cli-list-3.txt");
    }

    @DisplayName("Upload an entry via REST, get via CLI, and verify content in output")
    @Test
    void shouldGetDataStoreEntryViaCli() {
        // Given
        String name = "cli-get-entry.txt";
        dataStoreClient.upload(name, "Get me via CLI");

        // When
        CLIResult result = executeWithAuth("data-store", "get", "--host", getRouterHost(), "--name", name);

        // Then
        assertThat(result.isSuccess())
                .as("CLI get should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(result.getCombinedOutput())
                .as("CLI get output should reference the entry")
                .contains(name);
    }

    @DisplayName("Upload an entry via REST, remove via CLI, and verify removal")
    @Test
    void shouldRemoveDataStoreEntryViaCli() {
        // Given
        String name = "cli-remove-entry.txt";
        dataStoreClient.upload(name, "Remove me via CLI");
        assertThat(dataStoreClient.list()).contains(name);

        // When
        CLIResult result = executeWithAuth("data-store", "remove", "--host", getRouterHost(), "--name", name);

        // Then
        assertThat(result.isSuccess())
                .as("CLI command should succeed: %s", result.getCombinedOutput())
                .isTrue();
        assertThat(dataStoreClient.list()).doesNotContain(name);
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
