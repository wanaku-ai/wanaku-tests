package ai.wanaku.test.camel;

import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.services.PostgresServiceManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CIC with JDBC Camel routes and PostgreSQL via Testcontainers.
 * One fixture ({@code postgres-tool/}) with a parameterized query route, DataSource bean,
 * and dependencies.txt for the PostgreSQL driver.
 *
 * <p>Uses {@code ${JDBC_URL}}, {@code ${DB_USER}}, {@code ${DB_PASSWORD}} placeholders
 * substituted at runtime from PostgresServiceManager.
 *
 * <p>Covers: database query, error handling, DataStore loading.
 */
@QuarkusTest
class CamelPostgresToolITCase extends CamelCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(CamelPostgresToolITCase.class);

    private static PostgresServiceManager postgresManager;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgresManager = new PostgresServiceManager();
        postgresManager.start();

        // Seed database from fixture SQL
        try (InputStream is = CamelPostgresToolITCase.class.getResourceAsStream("/fixtures/postgres-tool/seed.sql")) {
            if (is != null) {
                String sql = new String(is.readAllBytes());
                postgresManager.executeSql(sql);
                LOG.info("Database seeded from seed.sql");
            }
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgresManager != null) {
            postgresManager.stop();
            postgresManager = null;
        }
    }

    @BeforeEach
    void assertInfrastructureAvailable() {
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();
        assertThat(postgresManager != null && postgresManager.isRunning())
                .as("PostgreSQL must be running")
                .isTrue();
    }

    @DisplayName("Query users from PostgreSQL via CIC JDBC tool")
    @Test
    void shouldQueryDatabaseViaTool() throws Exception {
        startPostgresCapability();

        mcpClient
                .when()
                .toolsCall("query-db", Map.of("query", "SELECT id, name, email FROM users ORDER BY id"), response -> {
                    LOG.debug("=== MCP toolsCall response [query-db]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    String text = response.content().get(0).asText().text();
                    assertThat(text).contains("Alice");
                    assertThat(text).contains("Bob");
                })
                .thenAssertResults();
    }

    @DisplayName("Query non-existent table and verify error response")
    @Test
    void shouldHandleDatabaseError() throws Exception {
        startPostgresCapability();

        mcpClient
                .when()
                .toolsCall("query-db", Map.of("query", "SELECT * FROM nonexistent_table"), response -> {
                    LOG.debug(
                            "=== MCP toolsCall response [query-db-error]: isError={}, content={}",
                            response.isError(),
                            response.content());
                    assertThat(response.isError())
                            .as("Invalid SQL should return error")
                            .isTrue();
                })
                .thenAssertResults();
    }

    @DisplayName("Load PostgreSQL tool config from Data Store and verify it works")
    @Test
    void shouldLoadPostgresToolFromDataStore() throws Exception {
        assertThat(isDataStoreAvailable())
                .as("Data Store API must be available")
                .isTrue();

        Map<String, String> dbVars = getDbVars();

        String routesContent = readFixtureFromClasspath("postgres-tool/routes.camel.yaml")
                .replace("${JDBC_URL}", dbVars.get("JDBC_URL"))
                .replace("${DB_USER}", dbVars.get("DB_USER"))
                .replace("${DB_PASSWORD}", dbVars.get("DB_PASSWORD"));
        String rulesContent = readFixtureFromClasspath("postgres-tool/rules.yaml");
        String depsContent = readFixtureFromClasspath("postgres-tool/dependencies.txt");

        dataStoreClient.upload("test-pg-routes.camel.yaml", routesContent);
        dataStoreClient.upload("test-pg-rules.yaml", rulesContent);
        dataStoreClient.upload("test-pg-dependencies.txt", depsContent);

        startCapabilityFromDataStore(
                "ds-pg-svc",
                "datastore://test-pg-routes.camel.yaml",
                "datastore://test-pg-rules.yaml",
                "datastore://test-pg-dependencies.txt");

        mcpClient
                .when()
                .toolsCall("query-db", Map.of("query", "SELECT name FROM users ORDER BY id"), response -> {
                    LOG.debug("=== MCP toolsCall response [query-db-datastore]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    assertThat(response.content().get(0).asText().text()).contains("Alice");
                })
                .thenAssertResults();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void startPostgresCapability() throws Exception {
        startCapability("postgres-tool-svc", "postgres-tool", getDbVars());
    }

    private Map<String, String> getDbVars() {
        return Map.of(
                "JDBC_URL", postgresManager.getJdbcUrl(),
                "DB_USER", postgresManager.getUsername(),
                "DB_PASSWORD", postgresManager.getPassword());
    }
}
