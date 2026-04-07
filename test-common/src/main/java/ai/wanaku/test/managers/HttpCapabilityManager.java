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
 * Manages the HTTP Capability Service process lifecycle.
 * Default: test-scoped (started/stopped per test).
 * Optional: suite-scoped (shared across tests).
 */
public class HttpCapabilityManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCapabilityManager.class);

    private final TestConfiguration config;
    private int grpcPort;

    /**
     * Creates a new HttpCapabilityManager.
     *
     * @param config the test configuration
     */
    public HttpCapabilityManager(TestConfiguration config) {
        this.config = config;
    }

    /**
     * Prepares the HTTP Capability Service with the router connection info.
     *
     * @param target the target/router connection configuration
     */
    public void prepare(TargetConfiguration target) {
        this.grpcPort = PortUtils.findAvailablePort();

        LOG.debug(
                "HTTP Capability prepared with gRPC port {}, connecting to Router HTTP:{} gRPC:{}",
                grpcPort,
                target.routerHttpPort(),
                target.routerGrpcPort());

        // Configure Quarkus properties
        addSystemProperty("quarkus.http.port", "0"); // Disable HTTP, only gRPC
        addSystemProperty("quarkus.grpc.server.port", String.valueOf(grpcPort));

        // Configure Router connection for capability registration (HTTP REST API)
        String registrationUri = target.registrationUri();
        addSystemProperty("wanaku.service.registration.uri", registrationUri);
        LOG.debug("HTTP Capability will register at {}", registrationUri);

        // Configure Router gRPC connection (for tool invocation)
        addSystemProperty("wanaku.router.host", target.routerHost());
        addSystemProperty("wanaku.router.port", String.valueOf(target.routerGrpcPort()));

        // Configure OIDC client for capability registration
        // Capabilities use quarkus.oidc-client.* (not quarkus.oidc.*) to obtain tokens
        if (target.oidcCredentials() != null) {
            addSystemProperty(
                    "quarkus.oidc-client.auth-server-url",
                    target.oidcCredentials().getAuthServerUrl());
            addSystemProperty(
                    "quarkus.oidc-client.client-id", target.oidcCredentials().clientId());
            addSystemProperty(
                    "quarkus.oidc-client.credentials.secret",
                    target.oidcCredentials().clientSecret());
            LOG.debug("HTTP Capability configured with OIDC credentials");
        }

        // Reduce registration delay for faster startup in tests
        addSystemProperty("wanaku.service.registration.delay-seconds", "0");
    }

    @Override
    protected String getProcessName() {
        return "http-capability";
    }

    @Override
    protected Path getJarPath() {
        return config.getHttpToolServiceJarPath();
    }

    @Override
    protected List<String> getProcessArguments() {
        return new ArrayList<>();
    }

    @Override
    protected boolean performHealthCheck() {
        // Wait for the gRPC port to be listening
        return HealthCheckUtils.waitForPort("localhost", grpcPort, config.getDefaultTimeout());
    }

    /**
     * Gets the gRPC port allocated for this capability.
     *
     * @return the gRPC port
     */
    public int getGrpcPort() {
        return grpcPort;
    }
}
