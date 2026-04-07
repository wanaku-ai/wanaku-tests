package ai.wanaku.test.managers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.OidcCredentials;
import ai.wanaku.test.config.TargetConfiguration;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.HealthCheckUtils;
import ai.wanaku.test.utils.PortUtils;

/**
 * Manages the Camel Integration Capability (CIC) process lifecycle.
 * <p>
 * Unlike Quarkus-based capabilities, CIC is packaged as a fat JAR and
 * accepts configuration via CLI arguments rather than system properties.
 */
public class CamelCapabilityManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(CamelCapabilityManager.class);

    private final TestConfiguration config;
    private int grpcPort;

    // CLI argument values set during prepare()
    private String name;
    private String registrationUrl;
    private String registrationAnnounceAddress;
    private String routesRef;
    private String rulesRef;
    private String dependenciesRef;
    private String tokenEndpoint;
    private String clientId;
    private String clientSecret;

    /**
     * Creates a new CamelCapabilityManager.
     *
     * @param config the test configuration
     */
    public CamelCapabilityManager(TestConfiguration config) {
        this.config = config;
    }

    /**
     * Prepares the Camel Integration Capability with the router connection info.
     *
     * @param serviceName the service name for Router registration (used as --name)
     * @param target the target/router connection configuration
     * @param routesRef routes reference (e.g., "file:///path/to/routes.yaml")
     * @param rulesRef rules reference (can be null)
     * @param dependenciesRef dependencies reference (can be null)
     */
    public void prepare(
            String serviceName, TargetConfiguration target, String routesRef, String rulesRef, String dependenciesRef) {
        this.grpcPort = PortUtils.findAvailablePort();
        this.name = serviceName;
        this.registrationUrl = target.registrationUri();
        this.registrationAnnounceAddress = target.routerHost();
        this.routesRef = routesRef;
        this.rulesRef = rulesRef;
        this.dependenciesRef = dependenciesRef;

        LOG.debug(
                "Camel Capability prepared with gRPC port {}, connecting to Router HTTP:{} gRPC:{}",
                grpcPort,
                target.routerHttpPort(),
                target.routerGrpcPort());
        LOG.debug("Camel Capability will register at {}", registrationUrl);

        OidcCredentials oidcCredentials = target.oidcCredentials();
        if (oidcCredentials != null) {
            this.tokenEndpoint = oidcCredentials.tokenEndpoint();
            this.clientId = oidcCredentials.clientId();
            this.clientSecret = oidcCredentials.clientSecret();
            LOG.debug("Camel Capability configured with OIDC credentials");
        }
    }

    /**
     * Starts the process.
     * <p>
     * Overrides the base class to handle fat JAR packaging: uses the absolute
     * JAR path directly without setting a working directory, since CIC does not
     * use the Quarkus fast-jar layout.
     *
     * @param testName the name of the test for log file naming
     * @throws IOException if the process cannot be started
     */
    @Override
    public void start(String testName) throws IOException {
        if (state != ProcessState.STOPPED) {
            throw new IllegalStateException("Process is already running: " + getProcessName());
        }

        Path jarPath = getJarPath();
        if (jarPath == null || !jarPath.toFile().exists()) {
            throw new IllegalStateException("JAR not found: " + jarPath);
        }

        state = ProcessState.STARTING;

        LOG.debug("Starting {}", getProcessName());

        // Create log file
        logFile = createLogFile(testName);

        // Build command — fat JAR uses absolute path, no working directory change
        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(jarPath.toAbsolutePath().toString());
        command.addAll(getProcessArguments());

        LOG.debug("Command: {}", String.join(" ", command));

        // Start process — no pb.directory() for fat JAR
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(environment);
        pb.redirectOutput(logFile);
        pb.redirectErrorStream(true);

        process = pb.start();
        LOG.debug("{} started with PID: {}", getProcessName(), process.pid());

        // Wait for health check
        if (performHealthCheck()) {
            state = ProcessState.RUNNING;
            LOG.debug("{} is healthy", getProcessName());
        } else {
            stop();
            throw new IllegalStateException(
                    getProcessName() + " failed health check. Check logs: " + logFile.getAbsolutePath());
        }
    }

    @Override
    protected String getProcessName() {
        return "camel-capability";
    }

    @Override
    protected Path getJarPath() {
        return config.getCamelCapabilityJarPath();
    }

    @Override
    protected List<String> getProcessArguments() {
        List<String> args = new ArrayList<>();

        args.add("--name");
        args.add(name);

        args.add("--grpc-port");
        args.add(String.valueOf(grpcPort));

        args.add("--routes-ref");
        args.add(routesRef);

        if (rulesRef != null) {
            args.add("--rules-ref");
            args.add(rulesRef);
        }

        if (dependenciesRef != null) {
            args.add("--dependencies");
            args.add(dependenciesRef);
        }

        args.add("--registration-url");
        args.add(registrationUrl);

        args.add("--registration-announce-address");
        args.add(registrationAnnounceAddress);

        if (tokenEndpoint != null) {
            args.add("--token-endpoint");
            args.add(tokenEndpoint);
        }

        if (clientId != null) {
            args.add("--client-id");
            args.add(clientId);
        }

        if (clientSecret != null) {
            args.add("--client-secret");
            args.add(clientSecret);
        }

        return args;
    }

    @Override
    protected boolean performHealthCheck() {
        // Wait for the gRPC port to be listening
        return HealthCheckUtils.waitForPort("localhost", grpcPort, config.getDefaultTimeout());
    }

    /**
     * Gets the service name used for Router registration.
     *
     * @return the service name
     */
    public String getName() {
        return name;
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
