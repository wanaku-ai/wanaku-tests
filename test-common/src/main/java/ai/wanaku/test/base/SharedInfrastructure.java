package ai.wanaku.test.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.KeycloakManager;
import ai.wanaku.test.managers.RouterManager;

import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Holds shared infrastructure (Keycloak + Router) that is started once per JVM
 * and reused across all test classes within a Maven module.
 * Registered in JUnit 5's global store so {@code close()} runs at JVM shutdown.
 */
public class SharedInfrastructure implements ExtensionContext.Store.CloseableResource {

    private static final Logger LOG = LoggerFactory.getLogger(SharedInfrastructure.class);

    private TestConfiguration config;
    private KeycloakManager keycloakManager;
    private RouterManager routerManager;
    private Path tempDataDir;

    SharedInfrastructure() {}

    void start() throws Exception {
        LOG.info("=== Starting shared infrastructure (once per module) ===");

        tempDataDir = Files.createTempDirectory("wanaku-test-");
        LOG.debug("Created shared temp directory: {}", tempDataDir);

        TestConfiguration baseConfig = TestConfiguration.fromSystemProperties();
        config = TestConfiguration.builder()
                .artifactsDir(baseConfig.getArtifactsDir())
                .routerJarPath(baseConfig.getRouterJarPath())
                .httpToolServiceJarPath(baseConfig.getHttpToolServiceJarPath())
                .fileProviderJarPath(baseConfig.getFileProviderJarPath())
                .camelCapabilityJarPath(baseConfig.getCamelCapabilityJarPath())
                .tempDataDir(tempDataDir)
                .defaultTimeout(baseConfig.getDefaultTimeout())
                .build();

        LOG.debug("Router JAR: {}", config.getRouterJarPath());
        LOG.debug("HTTP Capability JAR: {}", config.getHttpToolServiceJarPath());

        if (shouldSkipInfrastructure()) {
            LOG.info("Skipping infrastructure setup (no JARs available)");
            return;
        }

        keycloakManager = new KeycloakManager();
        try {
            keycloakManager.start();
        } catch (Exception e) {
            LOG.warn("Keycloak startup failed, Router will run without authentication: {}", e.getMessage());
            keycloakManager = null;
        }

        if (config.getRouterJarPath() != null
                && config.getRouterJarPath().toFile().exists()) {
            routerManager = new RouterManager(config);
            routerManager.prepare();

            if (keycloakManager != null && keycloakManager.isRunning()) {
                routerManager.addSystemProperty("wanaku.http.auth", "keycloak");
                routerManager.addSystemProperty(
                        "quarkus.oidc.auth-server-url", "http://localhost:8543/realms/" + KeycloakManager.REALM_NAME);
                routerManager.addSystemProperty("quarkus.oidc.client-id", "wanaku-service");
            } else {
                routerManager.addSystemProperty("wanaku.http.auth", "none");
            }

            routerManager.start("shared");
            LOG.info("Router started on port {}", routerManager.getHttpPort());
        } else {
            LOG.warn("Router JAR not found at {}, skipping Router startup", config.getRouterJarPath());
        }

        LOG.info("=== Shared infrastructure ready ===");
    }

    @Override
    public void close() {
        LOG.info("=== Tearing down shared infrastructure ===");

        if (routerManager != null) {
            routerManager.stop();
        }

        if (keycloakManager != null) {
            keycloakManager.stop();
        }

        if (tempDataDir != null) {
            try {
                deleteRecursively(tempDataDir);
            } catch (IOException e) {
                LOG.warn("Failed to cleanup shared temp directory: {}", e.getMessage());
            }
        }

        LOG.info("=== Shared infrastructure teardown complete ===");
    }

    public TestConfiguration getConfig() {
        return config;
    }

    public KeycloakManager getKeycloakManager() {
        return keycloakManager;
    }

    public RouterManager getRouterManager() {
        return routerManager;
    }

    public Path getTempDataDir() {
        return tempDataDir;
    }

    private boolean shouldSkipInfrastructure() {
        Path artifactsDir = Path.of(System.getProperty("wanaku.test.artifacts.dir", "artifacts"));
        return !Files.exists(artifactsDir) || !hasJars(artifactsDir);
    }

    private boolean hasJars(Path dir) {
        try {
            return Files.list(dir).anyMatch(p -> {
                if (p.toString().endsWith(".jar")) {
                    return true;
                }
                if (Files.isDirectory(p)) {
                    Path quarkusRunJar = p.resolve("quarkus-run.jar");
                    return Files.exists(quarkusRunJar);
                }
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.list(path).forEach(p -> {
                try {
                    deleteRecursively(p);
                } catch (IOException e) {
                    LOG.warn("Failed to delete: {}", p);
                }
            });
        }
        Files.deleteIfExists(path);
    }
}
