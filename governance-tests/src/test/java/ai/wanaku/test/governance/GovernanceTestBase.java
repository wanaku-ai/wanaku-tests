package ai.wanaku.test.governance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.MockMcpServerManager;
import ai.wanaku.test.managers.WanakuServerManager;
import ai.wanaku.test.stub.DeterministicLlmStub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Standalone lifecycle harness for the governance tests. Unlike the shared-server modules, each
 * scenario needs a Wanaku server whose pipeline includes the governance filters and whose bootstrap
 * config carries a scenario-specific static action policy, so this base manages its own server per
 * test rather than extending {@code BaseIntegrationTest}.
 *
 * <p>Per test it starts: a deterministic in-JVM LLM stub (for evaluator scenarios), the instrumented
 * capture MCP server (the governed upstream), and — once the concrete test supplies its policy via
 * {@link #startGovernedServer} — the Wanaku server itself. Everything is torn down in
 * {@link #stopInfrastructure}. Tests skip (rather than fail) when the server binary or the capture
 * fixture JAR are unavailable.
 */
abstract class GovernanceTestBase {

    protected static final Logger LOG = LoggerFactory.getLogger(GovernanceTestBase.class);
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path CAPTURE_JAR = Path.of(
                    "../fixtures/governance-capture-server/target/quarkus-app/quarkus-run.jar")
            .toAbsolutePath()
            .normalize();

    protected static final String CAPTURE_TOOL = "capture_tool";
    protected static final String CAPTURE_RESOURCE_URI = "capture://resource/data";
    protected static final String CAPTURE_PROMPT = "capture_prompt";

    protected TestConfiguration baseConfig;
    protected WanakuServerManager server;
    protected MockMcpServerManager captureServer;
    protected DeterministicLlmStub llmStub;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<SessionIdProxy> proxies = new ArrayList<>();
    private final List<McpTestClient> clients = new ArrayList<>();
    private final List<MockMcpServerManager> additionalCaptureServers = new ArrayList<>();

    @BeforeEach
    void startInfrastructure() throws Exception {
        baseConfig = TestConfiguration.fromSystemProperties();

        assumeThat(System.getProperty("wanaku.test.external.mgmt.port"))
                .as(
                        "Governance scenarios require a harness-owned server and store; external-server mode is unsupported")
                .isNull();

        assumeThat(baseConfig.getServerBinaryPath() != null && Files.exists(baseConfig.getServerBinaryPath()))
                .as("A managed Wanaku server binary is required (set -D%s)", WanakuTestConstants.PROP_SERVER_BINARY)
                .isTrue();
        assumeThat(Files.exists(CAPTURE_JAR))
                .as("The governance capture fixture JAR must be built at %s", CAPTURE_JAR)
                .isTrue();

        // Let subclasses skip before any process is started, so a missing prerequisite (e.g. the
        // evaluator WASM) does not spin up and tear down the capture server and LLM stub for nothing.
        checkAdditionalRequirements(baseConfig);

        llmStub = new DeterministicLlmStub(null);
        llmStub.start();

        captureServer = new MockMcpServerManager(CAPTURE_JAR, baseConfig);
        captureServer.prepare();
        configureCaptureServer(captureServer);
        captureServer.start(getClass().getSimpleName());
    }

    /**
     * Hook for subclasses to declare extra prerequisites via {@code assumeThat}. Runs after the server
     * binary and capture JAR checks but before any process is started, so a skip here costs nothing.
     */
    protected void checkAdditionalRequirements(TestConfiguration config) {}

    /** Configures the capture fixture before it starts. */
    protected void configureCaptureServer(MockMcpServerManager capture) {}

    @AfterEach
    void stopInfrastructure() {
        for (McpTestClient client : clients) {
            try {
                client.disconnect();
            } catch (Exception e) {
                LOG.debug("MCP client disconnect: {}", e.getMessage());
            }
        }
        clients.clear();
        for (SessionIdProxy proxy : proxies) {
            try {
                proxy.close();
            } catch (Exception e) {
                LOG.debug("Proxy close: {}", e.getMessage());
            }
        }
        proxies.clear();
        if (server != null) {
            server.stop();
            server = null;
        }
        if (captureServer != null) {
            captureServer.stop();
            captureServer = null;
        }
        for (MockMcpServerManager additional : additionalCaptureServers) {
            additional.stop();
        }
        additionalCaptureServers.clear();
        if (llmStub != null) {
            llmStub.stop();
            llmStub = null;
        }
        // JUnit creates a new instance (and thus a new HttpClient) per test method; close it so its
        // selector/executor threads and connection pool do not accumulate over the suite.
        httpClient.close();
    }

    /**
     * Starts the governed Wanaku server with the given static action policy and governance posture.
     * The generated pipeline includes {@code wanaku_action_policy} + {@code wanaku_evaluator}, and the
     * {@code wanaku_mcp_id} filter (required so denial responses carry the request's JSON-RPC id).
     */
    protected void startGovernedServer(String actionPolicyJson, String governanceJson) throws IOException {
        startGovernedServer(actionPolicyJson, governanceJson, null);
    }

    protected void startGovernedServer(String actionPolicyJson, String governanceJson, String auditJson)
            throws IOException {
        startGovernedServer(actionPolicyJson, governanceJson, auditJson, true);
    }

    protected void startGovernedServer(
            String actionPolicyJson, String governanceJson, String auditJson, boolean persistenceEnabled)
            throws IOException {
        TestConfiguration.Builder builder = baseConfig.toBuilder()
                .governanceEnabled(true)
                .mcpIdFilterEnabled(true)
                .actionPolicyJson(actionPolicyJson)
                .governanceJson(governanceJson)
                .auditJson(auditJson)
                .persistenceEnabled(persistenceEnabled)
                .llmConnectionUrl(llmStub.getConnectionUrl());
        configureGovernedServer(builder);
        TestConfiguration config = builder.build();
        server = new WanakuServerManager(config);
        server.prepare();
        server.setLogContext("wanaku-server", getClass().getSimpleName(), "governed-server");
        server.start(getClass().getSimpleName());
    }

    /** Allows a scenario to supply additional bootstrap configuration before starting Wanaku. */
    protected void configureGovernedServer(TestConfiguration.Builder builder) {}

    /** Creates the namespace (if needed), forwards it to the capture server, and waits for discovery. */
    protected void registerCaptureForward(String namespace) {
        NamespaceClient namespaceClient = new NamespaceClient(server.getBaseUrl(), null);
        if (!namespaceClient.exists(namespace)) {
            namespaceClient.create(namespace);
        }
        ForwardsClient forwardsClient = new ForwardsClient(server.getBaseUrl(), null);
        forwardsClient.add("capture-fwd-" + namespace, captureServer.getMcpUrl(), namespace);
        waitForToolDiscovery(namespace, CAPTURE_TOOL);
    }

    /**
     * Forwards the namespace to the capture server with the given labels attached, then waits for the
     * capture tool to be discovered. Discovered tools inherit the forward's labels, so a label-selector
     * policy can match on them. The Rust server exposes no API to register a native tool with labels,
     * so forwarding a labeled upstream is the supported way to exercise label selectors.
     */
    protected void registerLabeledCaptureForward(String namespace, Map<String, String> labels) throws Exception {
        registerLabeledCaptureForward(namespace, labels, captureServer);
    }

    protected MockMcpServerManager startAdditionalCaptureServer() throws Exception {
        MockMcpServerManager additional = new MockMcpServerManager(CAPTURE_JAR, baseConfig);
        additionalCaptureServers.add(additional);
        additional.prepare();
        additional.addSystemProperty("capture.isolated-catalog", "true");
        additional.start(getClass().getSimpleName());
        return additional;
    }

    protected void registerLabeledCaptureForward(
            String namespace, Map<String, String> labels, MockMcpServerManager upstream) throws Exception {
        NamespaceClient namespaceClient = new NamespaceClient(server.getBaseUrl(), null);
        if (!namespaceClient.exists(namespace)) {
            namespaceClient.create(namespace);
        }

        var body = MAPPER.createObjectNode();
        body.put("name", "capture-fwd-" + namespace);
        body.put("address", upstream.getMcpUrl());
        body.put("namespace", namespace);
        var labelsNode = body.putObject("labels");
        labels.forEach(labelsNode::put);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + WanakuTestConstants.FORWARDS_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Registering labeled forward for namespace '%s' should succeed: %s", namespace, response.body())
                .isIn(200, 201);
        waitForToolDiscovery(namespace, upstream == captureServer ? CAPTURE_TOOL : "capture_isolated_tool");
    }

    protected McpTestClient connect(String namespace) throws Exception {
        return connect(namespace, Map.of());
    }

    protected McpTestClient connect(String namespace, Map<String, String> headers) throws Exception {
        SessionIdProxy proxy = new SessionIdProxy(server.getMcpBaseUrl() + "/" + namespace);
        proxy.start();
        proxies.add(proxy);
        McpTestClient client = new McpTestClient(proxy.getBaseUrl(), null, headers);
        client.connect();
        clients.add(client);
        return client;
    }

    protected CaptureCounts captureCounts() throws Exception {
        return captureCounts(captureServer);
    }

    protected CaptureCounts captureCounts(MockMcpServerManager upstream) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(upstream.getBaseUrl() + "/capture/counts"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode node = MAPPER.readTree(response.body());
        return new CaptureCounts(
                node.get("toolCalls").asInt(),
                node.get("resourceReads").asInt(),
                node.get("promptGets").asInt());
    }

    protected void waitForToolDiscovery(String namespace, String toolName) {
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .ignoreExceptions()
                .untilAsserted(() -> {
                    try (SessionIdProxy proxy = new SessionIdProxy(server.getMcpBaseUrl() + "/" + namespace)) {
                        proxy.start();
                        McpTestClient client = new McpTestClient(proxy.getBaseUrl(), null);
                        client.connect();
                        try {
                            client.when()
                                    .toolsList(
                                            page -> assertThat(page.tools()).anyMatch(t -> toolName.equals(t.name())))
                                    .thenAssertResults();
                        } finally {
                            client.disconnect();
                        }
                    }
                });
    }

    protected record CaptureCounts(int toolCalls, int resourceReads, int promptGets) {}
}
