package ai.wanaku.test.managers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.HealthCheckUtils;
import ai.wanaku.test.utils.LogUtils;
import ai.wanaku.test.utils.PortUtils;

/**
 * Manages the Wanaku Router process lifecycle.
 * Typically suite-scoped (shared across all tests in a test class).
 */
public class RouterManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(RouterManager.class);

    private final TestConfiguration config;
    private int httpPort;
    private int grpcPort;

    /**
     * Creates a new RouterManager.
     *
     * @param config the test configuration
     */
    public RouterManager(TestConfiguration config) {
        this.config = config;
    }

    /**
     * Allocates ports and prepares the router for startup.
     */
    public void prepare() {
        this.httpPort = PortUtils.findAvailablePort();
        this.grpcPort = PortUtils.findAvailablePort();

        LOG.debug("Router prepared with HTTP port {} and gRPC port {}", httpPort, grpcPort);

        // Configure Quarkus properties
        addSystemProperty("quarkus.http.port", String.valueOf(httpPort));
        addSystemProperty("quarkus.grpc.server.port", String.valueOf(grpcPort));

        Path dataDir = config.getTempDataDir();
        if (dataDir != null) {
            addSystemProperty("wanaku.data.dir", dataDir.toAbsolutePath().toString());
        }
    }

    @Override
    protected File createLogFile(String testName) throws IOException {
        // Router log includes test class name for easy identification
        return LogUtils.createRouterLogFile(testName);
    }

    @Override
    protected String getProcessName() {
        return "router";
    }

    @Override
    protected Path getJarPath() {
        return config.getRouterJarPath();
    }

    @Override
    protected List<String> getProcessArguments() {
        // Router typically doesn't need additional command line args
        return new ArrayList<>();
    }

    @Override
    protected boolean performHealthCheck() {
        String healthUrl = "http://localhost:" + httpPort + WanakuTestConstants.ROUTER_HEALTH_PATH;
        return HealthCheckUtils.waitForHealthy(healthUrl, config.getDefaultTimeout());
    }

    /**
     * Gets the HTTP port the router is listening on.
     */
    public int getHttpPort() {
        return httpPort;
    }

    /**
     * Gets the gRPC port the router is listening on.
     */
    public int getGrpcPort() {
        return grpcPort;
    }

    /**
     * Gets the base URL for HTTP access.
     */
    public String getBaseUrl() {
        return "http://localhost:" + httpPort;
    }
}
