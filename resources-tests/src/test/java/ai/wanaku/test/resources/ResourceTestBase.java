package ai.wanaku.test.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.managers.ResourceProviderManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;

/**
 * Base class for resource provider integration tests.
 * Adds file provider lifecycle management on top of {@link BaseIntegrationTest}.
 * File provider is suite-scoped (started once, shared across all tests).
 */
public abstract class ResourceTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceTestBase.class);

    protected static ResourceProviderManager resourceProviderManager;

    @BeforeAll
    static void startFileProvider(TestInfo testInfo) throws Exception {
        if (config == null
                || config.getFileProviderJarPath() == null
                || !config.getFileProviderJarPath().toFile().exists()) {
            LOG.warn("File provider JAR not available, skipping provider startup");
            return;
        }

        if (routerManager == null || !routerManager.isRunning()) {
            LOG.warn("Router not running, skipping file provider startup");
            return;
        }

        OidcCredentials oidcCredentials = null;
        if (keycloakManager != null && keycloakManager.isRunning()) {
            oidcCredentials = keycloakManager.getServiceCredentials();
        }

        String testClassName = testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");

        resourceProviderManager = new ResourceProviderManager(config);
        resourceProviderManager.prepare(
                "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials);
        resourceProviderManager.setLogContext("file-provider", testClassName, "file-provider");
        resourceProviderManager.start(testClassName);

        LOG.debug("Waiting for file provider registration...");
        RouterClient client = new RouterClient(routerManager.getBaseUrl());
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> client.isCapabilityRegistered("file"));
        LOG.info("File provider is registered");
    }

    @AfterAll
    static void stopFileProvider() {
        if (resourceProviderManager != null) {
            resourceProviderManager.stop();
            resourceProviderManager = null;
        }
    }

    @AfterEach
    void clearResources() {
        if (routerClient != null) {
            try {
                routerClient.clearAllResources();
            } catch (Exception e) {
                LOG.warn("Failed to clear resources: {}", e.getMessage());
            }
        }
    }

    @Override
    protected String getLogProfile() {
        return "file-provider";
    }

    /**
     * Creates a test file in the temp data directory.
     *
     * @param filename the file name
     * @param content the file content
     * @return the absolute path to the created file
     */
    protected Path createTestFile(String filename, String content) throws IOException {
        Path file = tempDataDir.resolve(filename);
        Files.writeString(file, content);
        LOG.debug("Created test file: {}", file);
        return file;
    }

    /**
     * Checks if the file provider JAR is available.
     */
    protected boolean isFileProviderAvailable() {
        return config != null
                && config.getFileProviderJarPath() != null
                && config.getFileProviderJarPath().toFile().exists();
    }
}
