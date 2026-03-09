package ai.wanaku.test.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Manages a PostgreSQL container lifecycle using Testcontainers.
 * Provides JDBC access and SQL execution for schema/data seeding.
 */
public class PostgresServiceManager {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresServiceManager.class);

    private static final String DEFAULT_IMAGE = "postgres:16";
    private static final String DEFAULT_USERNAME = "test";
    private static final String DEFAULT_PASSWORD = "test";
    private static final String DEFAULT_DATABASE = "testdb";

    private PostgreSQLContainer<?> container;
    private ManagerState state = ManagerState.STOPPED;

    public enum ManagerState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    /**
     * Starts the PostgreSQL container.
     */
    public void start() {
        if (state != ManagerState.STOPPED) {
            throw new IllegalStateException("PostgreSQL is already running");
        }

        state = ManagerState.STARTING;
        LOG.info("Starting PostgreSQL container with image {}", DEFAULT_IMAGE);

        container = new PostgreSQLContainer<>(DEFAULT_IMAGE)
                .withDatabaseName(DEFAULT_DATABASE)
                .withUsername(DEFAULT_USERNAME)
                .withPassword(DEFAULT_PASSWORD);
        container.start();

        state = ManagerState.RUNNING;
        LOG.debug("PostgreSQL started at {}:{}", getHost(), getPort());
    }

    /**
     * Stops the PostgreSQL container.
     */
    public void stop() {
        if (container == null) {
            state = ManagerState.STOPPED;
            return;
        }

        state = ManagerState.STOPPING;
        LOG.debug("Stopping PostgreSQL container");

        try {
            container.stop();
        } finally {
            container = null;
            state = ManagerState.STOPPED;
            LOG.debug("PostgreSQL stopped");
        }
    }

    /**
     * Returns the JDBC URL for the running container.
     */
    public String getJdbcUrl() {
        ensureRunning();
        return container.getJdbcUrl();
    }

    /**
     * Returns the database username.
     */
    public String getUsername() {
        return DEFAULT_USERNAME;
    }

    /**
     * Returns the database password.
     */
    public String getPassword() {
        return DEFAULT_PASSWORD;
    }

    /**
     * Returns the mapped host for the running container.
     */
    public String getHost() {
        ensureRunning();
        return container.getHost();
    }

    /**
     * Returns the mapped port for the running container.
     */
    public int getPort() {
        ensureRunning();
        return container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    }

    /**
     * Checks if the container is running.
     */
    public boolean isRunning() {
        return container != null && container.isRunning() && state == ManagerState.RUNNING;
    }

    /**
     * Executes a SQL statement for schema or data seeding.
     *
     * @param sql the SQL to execute
     */
    public void executeSql(String sql) {
        ensureRunning();
        LOG.debug("Executing SQL: {}", sql);

        try (Connection conn = DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            LOG.debug("SQL executed successfully");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute SQL: " + sql, e);
        }
    }

    /**
     * Reads a SQL file and executes its contents.
     *
     * @param sqlFile the path to the SQL file
     */
    public void executeSqlFromFile(Path sqlFile) {
        LOG.debug("Executing SQL from file: {}", sqlFile);

        try {
            String sql = Files.readString(sqlFile);
            executeSql(sql);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SQL file: " + sqlFile, e);
        }
    }

    private void ensureRunning() {
        if (!isRunning()) {
            throw new IllegalStateException("PostgreSQL is not running");
        }
    }
}
