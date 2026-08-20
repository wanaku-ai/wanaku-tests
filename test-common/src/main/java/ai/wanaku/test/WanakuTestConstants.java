package ai.wanaku.test;

import java.time.Duration;

public final class WanakuTestConstants {

    private WanakuTestConstants() {}

    // System property keys
    public static final String PROP_ARTIFACTS_DIR = "wanaku.test.artifacts.dir";
    public static final String PROP_SERVER_BINARY = "wanaku.test.server.binary";
    public static final String PROP_CLI_PATH = "wanaku.test.cli.path";
    public static final String PROP_CAMEL_CAPABILITY_JAR = "wanaku.test.camel-capability.jar";
    public static final String PROP_TIMEOUT = "wanaku.test.timeout";
    public static final String PROP_SKIP_THRESHOLD = "wanaku.test.skip.threshold";

    // Default values
    public static final String DEFAULT_ARTIFACTS_DIR = "artifacts";
    public static final String DEFAULT_CLI_PATH = "wanaku";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofMillis(200);
    public static final Duration DEFAULT_REGISTRATION_POLL_INTERVAL = Duration.ofMillis(100);
    public static final int DEFAULT_SKIP_THRESHOLD = 30;

    // Health check
    public static final String SERVER_HEALTH_PATH = "/healthz";

    // API paths
    public static final String API_BASE_PATH = "/api/v1";
    public static final String TOOLS_PATH = API_BASE_PATH + "/tools";
    public static final String RESOURCES_PATH = API_BASE_PATH + "/resources";
    public static final String MANAGEMENT_STATISTICS_PATH = API_BASE_PATH + "/management/statistics";
    public static final String NAMESPACES_PATH = API_BASE_PATH + "/namespaces";
    public static final String PROMPTS_PATH = API_BASE_PATH + "/prompts";
    public static final String FORWARDS_PATH = API_BASE_PATH + "/forwards";
    public static final String SERVICES_PATH = API_BASE_PATH + "/services";

    // Port allocation
    public static final int PORT_ALLOCATION_RETRIES = 5;

    // Process management
    public static final Duration GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    // Log directory
    public static final String LOG_DIR = "target/logs";
}
