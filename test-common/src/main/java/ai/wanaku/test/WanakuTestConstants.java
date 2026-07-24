package ai.wanaku.test;

import java.time.Duration;

/**
 * Configuration constants for Wanaku integration tests.
 */
public final class WanakuTestConstants {

    private WanakuTestConstants() {
        // Utility class
    }

    // System property keys
    public static final String PROP_ARTIFACTS_DIR = "wanaku.test.artifacts.dir";
    public static final String PROP_ROUTER_JAR = "wanaku.test.router.jar";
    public static final String PROP_HTTP_SERVICE_JAR = "wanaku.test.http-service.jar";
    public static final String PROP_CLI_PATH = "wanaku.test.cli.path";
    public static final String PROP_FILE_PROVIDER_JAR = "wanaku.test.file-provider.jar";
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

    // Health check endpoints
    public static final String ROUTER_HEALTH_PATH = "/q/health/ready";

    // API paths
    public static final String ROUTER_API_BASE_PATH = "/api/v1";
    public static final String ROUTER_TOOLS_PATH = ROUTER_API_BASE_PATH + "/tools";
    public static final String ROUTER_RESOURCES_PATH = ROUTER_API_BASE_PATH + "/resources";
    public static final String ROUTER_CAPABILITIES_PATH = ROUTER_API_BASE_PATH + "/capabilities";
    public static final String ROUTER_MANAGEMENT_DISCOVERY_PATH = ROUTER_API_BASE_PATH + "/management/discovery";
    public static final String ROUTER_MANAGEMENT_INFO_PATH = ROUTER_API_BASE_PATH + "/management/info/version";
    public static final String ROUTER_MANAGEMENT_STATISTICS_PATH = ROUTER_API_BASE_PATH + "/management/statistics";
    public static final String ROUTER_DATA_STORE_PATH = ROUTER_API_BASE_PATH + "/data-store";
    public static final String ROUTER_NAMESPACES_PATH = ROUTER_API_BASE_PATH + "/namespaces";
    public static final String ROUTER_PROMPTS_PATH = ROUTER_API_BASE_PATH + "/prompts";
    public static final String ROUTER_FORWARDS_PATH = ROUTER_API_BASE_PATH + "/forwards";
    public static final String ROUTER_SERVICE_CATALOG_PATH = ROUTER_API_BASE_PATH + "/service-catalog";

    // Port allocation
    public static final int PORT_ALLOCATION_RETRIES = 5;

    // Process management
    public static final Duration GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    // Log directory
    public static final String LOG_DIR = "target/logs";
}
