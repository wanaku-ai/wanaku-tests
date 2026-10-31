package ai.wanaku.test.managers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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

public class WanakuServerManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(WanakuServerManager.class);

    private final TestConfiguration config;
    private int mgmtPort;
    private int mcpPort;
    private int a2aPort;
    private Path pipelineConfigFile;
    private Path wanakuConfigFile;
    private Path persistDir;

    private boolean external;

    public WanakuServerManager(TestConfiguration config) {
        this.config = config;
    }

    public static WanakuServerManager external(TestConfiguration config, int mgmtPort, int mcpPort) {
        WanakuServerManager manager = new WanakuServerManager(config);
        manager.mgmtPort = mgmtPort;
        manager.mcpPort = mcpPort;
        String externalA2aPort = System.getProperty("wanaku.test.external.a2a.port");
        if (externalA2aPort != null) {
            manager.a2aPort = Integer.parseInt(externalA2aPort);
        }
        manager.external = true;
        return manager;
    }

    public void prepare() {
        // The server refuses to start when two listeners share a port, so allocate them together.
        int[] ports = PortUtils.findAvailablePorts(3);
        this.mgmtPort = ports[0];
        this.mcpPort = ports[1];
        if (config.isA2aEnabled()) {
            this.a2aPort = ports[2];
            addEnvironmentVariable("WANAKU_A2A_PUBLIC_URL", getA2aBaseUrl() + "/");
        }

        LOG.debug("Wanaku server prepared with management port {} and MCP port {}", mgmtPort, mcpPort);

        addEnvironmentVariable("WANAKU_MGMT_LISTEN", "0.0.0.0:" + mgmtPort);
        addEnvironmentVariable("WANAKU_PERSIST_BACKEND", config.isPersistenceEnabled() ? "file" : "none");

        // Header forwarding is default-deny (wanaku-ai/wanaku#873): the server forwards a request
        // header to downstream MCP servers only when it appears in this allowlist. Left unset, the
        // variable is omitted and the server keeps its default-deny posture, so only modules that
        // explicitly opt in (currently mcp-forwarding-tests) get header forwarding.
        String forwardHeaders = config.getForwardHeaders();
        if (forwardHeaders != null && !forwardHeaders.isBlank()) {
            addEnvironmentVariable("WANAKU_FORWARD_HEADERS", forwardHeaders);
        }

        try {
            persistDir = Files.createTempDirectory("wanaku-server-data-");
            addEnvironmentVariable(
                    "WANAKU_PERSIST_PATH", persistDir.toAbsolutePath().toString());
            pipelineConfigFile = generatePipelineConfig();
            wanakuConfigFile = generateWanakuConfig();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate server config files", e);
        }
    }

    @Override
    protected String getProcessName() {
        return "wanaku-server";
    }

    @Override
    protected Path getExecutablePath() {
        return config.getServerBinaryPath();
    }

    @Override
    protected List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(getExecutablePath().toAbsolutePath().toString());
        command.add("--pipeline-config");
        command.add(pipelineConfigFile.toAbsolutePath().toString());
        command.add("--wanaku-config");
        command.add(wanakuConfigFile.toAbsolutePath().toString());
        return command;
    }

    @Override
    protected Path getWorkingDirectory() {
        return null;
    }

    @Override
    protected void configureDataIsolation() {}

    @Override
    protected List<String> getProcessArguments() {
        return new ArrayList<>();
    }

    @Override
    protected boolean performHealthCheck() {
        String healthUrl = "http://localhost:" + mgmtPort + WanakuTestConstants.SERVER_HEALTH_PATH;
        return HealthCheckUtils.waitForHealthy(healthUrl, config.getDefaultTimeout());
    }

    @Override
    protected File createLogFile(String testName) throws IOException {
        return LogUtils.createLogFile(testName, "wanaku-server");
    }

    @Override
    public boolean isRunning() {
        if (external) {
            return true;
        }
        return super.isRunning();
    }

    @Override
    public void stop() {
        if (external) {
            return;
        }
        super.stop();
        cleanupTempFiles();
    }

    /**
     * Stops a managed server without deleting its generated configuration or persisted state.
     *
     * <p>This is intended for recovery tests that restart the same manager instance. Call
     * {@link #stop()} after the final restart to remove the temporary files and persistence
     * directory.
     */
    public void stopPreservingState() {
        if (external) {
            throw new IllegalStateException("Cannot stop an externally managed Wanaku server");
        }
        super.stop();
    }

    public int getHttpPort() {
        return mgmtPort;
    }

    /** Managed file-persistence directory shared by the independent revision stores. */
    public Path getPersistDirectory() {
        return persistDir;
    }

    public int getMcpPort() {
        return mcpPort;
    }

    public String getBaseUrl() {
        return "http://localhost:" + mgmtPort;
    }

    public String getMcpBaseUrl() {
        return "http://localhost:" + mcpPort;
    }

    public String getA2aBaseUrl() {
        return a2aPort > 0 ? "http://localhost:" + a2aPort : null;
    }

    public TestConfiguration getConfig() {
        return config;
    }

    public Path getPersistDir() {
        return persistDir;
    }

    private Path generatePipelineConfig() throws IOException {
        List<String> lines = new ArrayList<>(List.of(
                "listeners:",
                "  - name: mcp",
                "    address: \"0.0.0.0:" + mcpPort + "\"",
                "    filter_chains: [mcp_router]",
                "",
                "filter_chains:",
                "  - name: mcp_router",
                "    filters:",
                "      - filter: cors",
                "        allow_origins: [\"*\"]",
                "        allow_methods: [\"GET\", \"POST\", \"OPTIONS\"]",
                "        allow_headers: [\"Content-Type\", \"Accept\", \"Mcp-Session-Id\","
                        + " \"Mcp-Protocol-Version\", \"Authorization\"]",
                "      - filter: mcp",
                "        on_invalid: continue"));

        if (config.isA2aEnabled()) {
            lines.add(1, "  - name: a2a");
            lines.add(2, "    address: \"0.0.0.0:" + a2aPort + "\"");
            lines.add(3, "    filter_chains: [a2a_proxy]");
        }

        // wanaku_mcp_id extracts the JSON-RPC id from the request body once and stores it as mcp.id
        // metadata, which downstream filters (mcp_init, tool_call, ...) then read instead of
        // re-parsing the body. Servers that predate wanaku-ai/wanaku#1849 do not register this
        // filter and abort startup with "unknown filter type", so it is only added when the target
        // server is known to support it. It must run right after the mcp filter, before any filter
        // that reads the id. Without it, such servers answer initialize with a null id and MCP
        // clients fail to connect.
        if (config.isMcpIdFilterEnabled()) {
            lines.add("      - filter: wanaku_mcp_id");
        }

        lines.addAll(List.of(
                "      - filter: wanaku_namespace",
                "      - filter: wanaku_well_known",
                "      - filter: wanaku_mcp_init"));

        // Governance filters must sit after request metadata/namespace/init resolution and before the
        // tool/resource/prompt filters that forward to upstream MCP servers, so a denied call never
        // reaches upstream. action_policy (static rules) runs before evaluator (dynamic LLM-backed
        // review) per wanaku-ai/wanaku#1900. Only emitted for the governance-tests module; servers
        // that predate that change abort startup on the unknown filter types.
        if (config.isGovernanceEnabled()) {
            lines.add("      - filter: wanaku_action_policy");
            lines.add("      - filter: wanaku_evaluator");
        }

        lines.addAll(List.of(
                "      - filter: wanaku_tool_list",
                "      - filter: wanaku_tool_call",
                "      - filter: wanaku_resource_list",
                "      - filter: wanaku_resource_read",
                "      - filter: wanaku_prompt_list",
                "      - filter: wanaku_prompt_get",
                "      - filter: static_response",
                "        status: 200",
                "        headers:",
                "          - name: content-type",
                "            value: application/json",
                "        body: '{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,"
                        + "\"message\":\"method not supported\"},\"id\":null}'",
                ""));

        if (config.isA2aEnabled()) {
            lines.add(a2aFilterChain());
        }

        lines.addAll(List.of("insecure_options:", "  skip_pipeline_validation: true", ""));

        if (config.isA2aEnabled()) {
            lines.add("  allow_private_endpoints: true");
            lines.add("  allow_private_upstreams: true");
        }

        Path configFile = Files.createTempFile("pipeline-config-", ".yaml");
        Files.writeString(configFile, String.join("\n", lines));
        LOG.debug("Generated pipeline config at {}", configFile);
        return configFile;
    }

    private String a2aFilterChain() {
        return """
                      - name: a2a_proxy
                        filters:
                          - filter: a2a
                            on_invalid: continue
                            method_aliases:
                              message/send: SendMessage
                              tasks/get: GetTask
                              tasks/cancel: CancelTask
                          - filter: wanaku_a2a
                            managed: true
                            public_url: "%s/"
                          - filter: wanaku_action_policy
                    """
                .formatted(getA2aBaseUrl());
    }

    private Path generateWanakuConfig() throws IOException {
        // Named LLM connections are config-only: they can only be defined here, never through the
        // management API. Evaluator tests reference this connection by name; its credential must
        // never surface through any API response (wanaku-ai/wanaku#1868).
        List<String> lines = new ArrayList<>(List.of(
                "# bootstrap config for testing",
                "llm_connections:",
                "  - name: \"" + WanakuTestConstants.TEST_LLM_CONNECTION_NAME + "\"",
                "    model: \"test-model\"",
                "    url: \"" + config.getLlmConnectionUrl() + "\"",
                "    api_key: \"" + WanakuTestConstants.TEST_LLM_CONNECTION_SECRET + "\""));

        // governance: and action_policy: are emitted as JSON, which is a valid YAML flow mapping, so
        // the whole block is a single line and no hand-indented multi-line YAML is needed. The server
        // reads governance posture and seeds the static action policy from these keys at startup.
        if (config.getGovernanceJson() != null) {
            lines.add("governance: " + config.getGovernanceJson());
        }
        if (config.getActionPolicyJson() != null) {
            lines.add("action_policy: " + config.getActionPolicyJson());
        }
        if (config.getOpaConnectionsJson() != null) {
            lines.add("opa_connections: " + config.getOpaConnectionsJson());
        }
        if (config.getAuditJson() != null) {
            lines.add("audit: " + config.getAuditJson());
        }
        lines.add("");

        String yaml = String.join("\n", lines);

        Path configFile = Files.createTempFile("wanaku-config-", ".yaml");
        Files.writeString(configFile, yaml);
        LOG.debug("Generated wanaku config at {}", configFile);
        return configFile;
    }

    private void cleanupTempFiles() {
        deleteSilently(pipelineConfigFile);
        deleteSilently(wanakuConfigFile);
        if (persistDir != null) {
            try {
                deleteRecursively(persistDir);
            } catch (IOException e) {
                LOG.warn("Failed to cleanup server persist dir: {}", e.getMessage());
            }
        }
    }

    private void deleteSilently(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warn("Failed to delete {}: {}", path, e.getMessage());
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (IOException e) {
                        LOG.warn("Failed to delete: {}", p);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
