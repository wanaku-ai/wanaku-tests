package ai.wanaku.test.managers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.HealthCheckUtils;
import ai.wanaku.test.utils.PortUtils;

/**
 * Manages the File Resource Provider process lifecycle.
 * Follows the same pattern as {@link HttpCapabilityManager}.
 */
public class ResourceProviderManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceProviderManager.class);

    private final TestConfiguration config;
    private int grpcPort;

    /**
     * Creates a new ResourceProviderManager.
     *
     * @param config the test configuration
     */
    public ResourceProviderManager(TestConfiguration config) {
        this.config = config;
    }

    /**
     * Prepares the File Resource Provider with the router connection info.
     *
     * @param target the target/router connection configuration
     */
    public void prepare(TargetConfiguration target) {
        this.grpcPort = PortUtils.findAvailablePort();

        LOG.debug(
                "File Provider prepared with gRPC port {}, connecting to Router HTTP:{} gRPC:{}",
                grpcPort,
                target.routerHttpPort(),
                target.routerGrpcPort());

        addSystemProperty("quarkus.http.port", "0");
        addSystemProperty("quarkus.grpc.server.port", String.valueOf(grpcPort));

        String registrationUri = target.registrationUri();
        addSystemProperty("wanaku.service.registration.uri", registrationUri);
        LOG.debug("File Provider will register at {}", registrationUri);

        addSystemProperty("wanaku.router.host", target.routerHost());
        addSystemProperty("wanaku.router.port", String.valueOf(target.routerGrpcPort()));

        if (target.oidcCredentials() != null) {
            addSystemProperty(
                    "quarkus.oidc-client.auth-server-url",
                    target.oidcCredentials().getAuthServerUrl());
            addSystemProperty(
                    "quarkus.oidc-client.client-id", target.oidcCredentials().clientId());
            addSystemProperty(
                    "quarkus.oidc-client.credentials.secret",
                    target.oidcCredentials().clientSecret());
            LOG.debug("File Provider configured with OIDC credentials");
        }

        addSystemProperty("wanaku.service.registration.delay-seconds", "0");
    }

    @Override
    protected String getProcessName() {
        return "file-provider";
    }

    @Override
    protected Path getJarPath() {
        return config.getFileProviderJarPath();
    }

    @Override
    protected List<String> getProcessArguments() {
        return new ArrayList<>();
    }

    @Override
    protected boolean performHealthCheck() {
        return HealthCheckUtils.waitForPort("localhost", grpcPort, config.getDefaultTimeout());
    }
}
