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
        manager.external = true;
        return manager;
    }

    public void prepare() {
        this.mgmtPort = PortUtils.findAvailablePort();
        this.mcpPort = PortUtils.findAvailablePort();

        LOG.debug("Wanaku server prepared with management port {} and MCP port {}", mgmtPort, mcpPort);

        addEnvironmentVariable("WANAKU_MGMT_LISTEN", "0.0.0.0:" + mgmtPort);
        addEnvironmentVariable("WANAKU_PERSIST_BACKEND", "file");

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

    public int getHttpPort() {
        return mgmtPort;
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

    public TestConfiguration getConfig() {
        return config;
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
                "      - filter: wanaku_mcp_init",
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
                "",
                "insecure_options:",
                "  skip_pipeline_validation: true",
                ""));

        Path configFile = Files.createTempFile("pipeline-config-", ".yaml");
        Files.writeString(configFile, String.join("\n", lines));
        LOG.debug("Generated pipeline config at {}", configFile);
        return configFile;
    }

    private Path generateWanakuConfig() throws IOException {
        // Named LLM connections are config-only: they can only be defined here, never through the
        // management API. Evaluator tests reference this connection by name; its credential must
        // never surface through any API response (wanaku-ai/wanaku#1868).
        String yaml = String.join(
                "\n",
                "# bootstrap config for testing",
                "llm_connections:",
                "  - name: \"" + WanakuTestConstants.TEST_LLM_CONNECTION_NAME + "\"",
                "    model: \"test-model\"",
                "    url: \"http://localhost:11434/v1/\"",
                "    api_key: \"" + WanakuTestConstants.TEST_LLM_CONNECTION_SECRET + "\"",
                "");

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
