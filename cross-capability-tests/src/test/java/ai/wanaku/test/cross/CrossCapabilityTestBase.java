package ai.wanaku.test.cross;

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
import ai.wanaku.test.fixtures.TestFixtures;
import ai.wanaku.test.managers.CamelCapabilityManager;
import ai.wanaku.test.managers.ResourceProviderManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for tests that exercise more than one capability at a time.
 * Manages the extra capability lifecycles and shared cleanup on top of {@link BaseIntegrationTest}.
 */
public abstract class CrossCapabilityTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(CrossCapabilityTestBase.class);
    private static final Path FIXTURES_TARGET_DIR = Path.of("target", "test-fixtures");

    protected ResourceProviderManager resourceProviderManager;
    protected CamelCapabilityManager camelCapabilityManager;

    @BeforeEach
    void setupCrossCapabilityInfrastructure() throws IOException {
        Files.createDirectories(FIXTURES_TARGET_DIR);
    }

    @AfterEach
    void teardownCrossCapabilityInfrastructure() {
        stopCamelCapability();
        stopResourceProvider();
        clearRouterState();
    }

    protected ResourceProviderManager startResourceProvider() throws Exception {
        OidcCredentials oidcCredentials = getOidcCredentials();

        resourceProviderManager = new ResourceProviderManager(config);
        resourceProviderManager.prepare(
                "localhost", routerManager.getHttpPort(), routerManager.getGrpcPort(), oidcCredentials);
        resourceProviderManager.setLogContext("file-provider", getClass().getSimpleName(), "file-provider");
        resourceProviderManager.start(getClass().getSimpleName());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> routerClient.isCapabilityRegistered("file"));
        return resourceProviderManager;
    }

    protected CamelCapabilityManager startCamelCapability(String serviceName, String fixtureName) throws Exception {
        Path fixtureDir = TestFixtures.load(fixtureName, FIXTURES_TARGET_DIR);
        Path routesRef = fixtureDir.resolve("routes.camel.yaml");
        Path rulesRef = fixtureDir.resolve("rules.yaml");

        OidcCredentials oidcCredentials = getOidcCredentials();

        camelCapabilityManager = new CamelCapabilityManager(config);
        camelCapabilityManager.prepare(
                serviceName,
                "localhost",
                routerManager.getHttpPort(),
                routerManager.getGrpcPort(),
                oidcCredentials,
                "file://" + routesRef.toAbsolutePath(),
                rulesRef.toFile().exists() ? "file://" + rulesRef.toAbsolutePath() : null,
                null);
        camelCapabilityManager.setLogContext("camel-capability", getClass().getSimpleName(), serviceName);
        camelCapabilityManager.start(serviceName);

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> routerClient.isCapabilityRegistered(serviceName));
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> routerClient.listTools().stream().anyMatch(t -> serviceName.equals(t.getType())));
        return camelCapabilityManager;
    }

    protected void restartRouterWithSamePorts(String testName) throws Exception {
        int httpPort = routerManager.getHttpPort();
        int grpcPort = routerManager.getGrpcPort();

        routerManager.stop();
        routerManager.start(testName);

        routerClient = new RouterClient(routerManager.getBaseUrl());
        if (httpPort != routerManager.getHttpPort() || grpcPort != routerManager.getGrpcPort()) {
            throw new IllegalStateException("Router restarted on different ports, reconnection would be invalid");
        }
    }

    protected void clearRouterState() {
        if (routerClient == null) {
            return;
        }

        try {
            routerClient.clearAllTools();
        } catch (Exception e) {
            LOG.warn("Failed to clear tools: {}", e.getMessage());
        }

        try {
            routerClient.clearAllResources();
        } catch (Exception e) {
            LOG.warn("Failed to clear resources: {}", e.getMessage());
        }
    }

    protected void stopResourceProvider() {
        if (resourceProviderManager == null) {
            return;
        }

        try {
            resourceProviderManager.stop();
        } catch (Exception e) {
            LOG.warn("Failed to stop resource provider: {}", e.getMessage());
        } finally {
            resourceProviderManager = null;
        }
    }

    protected void stopCamelCapability() {
        if (camelCapabilityManager == null) {
            return;
        }

        try {
            String deregToken = getMcpDeregistrationToken();
            if (routerClient != null && camelCapabilityManager.getName() != null) {
                routerClient.deregisterCapability(camelCapabilityManager.getName(), deregToken);
            }
        } catch (Exception e) {
            LOG.warn("Failed to deregister Camel capability: {}", e.getMessage());
        }

        try {
            camelCapabilityManager.stop();
        } catch (Exception e) {
            LOG.warn("Failed to stop Camel capability: {}", e.getMessage());
        } finally {
            camelCapabilityManager = null;
        }
    }

    protected Path createTestFile(String filename, String content) throws IOException {
        Path file = tempDataDir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    protected boolean isFileProviderAvailable() {
        return config != null
                && config.getFileProviderJarPath() != null
                && config.getFileProviderJarPath().toFile().exists();
    }

    protected boolean isCamelCapabilityAvailable() {
        return config != null
                && config.getCamelCapabilityJarPath() != null
                && config.getCamelCapabilityJarPath().toFile().exists();
    }

    protected OidcCredentials getOidcCredentials() {
        if (keycloakManager != null && keycloakManager.isRunning()) {
            return keycloakManager.getServiceCredentials();
        }
        return null;
    }

    private String getMcpDeregistrationToken() {
        if (keycloakManager != null && keycloakManager.isRunning()) {
            return keycloakManager.getMcpToken();
        }
        return null;
    }

    @Override
    protected String getLogProfile() {
        return "cross-capability";
    }
}
