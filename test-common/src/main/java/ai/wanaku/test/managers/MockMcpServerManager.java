package ai.wanaku.test.managers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.utils.HealthCheckUtils;
import ai.wanaku.test.utils.PortUtils;

public class MockMcpServerManager extends ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(MockMcpServerManager.class);

    private final Path jarPath;
    private final TestConfiguration config;
    private int httpPort;

    public MockMcpServerManager(Path jarPath, TestConfiguration config) {
        this.jarPath = jarPath;
        this.config = config;
    }

    public void prepare() {
        this.httpPort = PortUtils.findAvailablePort();
        addSystemProperty("quarkus.http.port", String.valueOf(httpPort));
        LOG.debug("Mock MCP server prepared on port {}", httpPort);
    }

    @Override
    protected List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(jarPath.toAbsolutePath().toString());
        return command;
    }

    @Override
    protected Path getWorkingDirectory() {
        return jarPath.getParent();
    }

    @Override
    protected void configureDataIsolation() {}

    @Override
    protected String getProcessName() {
        return "mock-mcp-server";
    }

    @Override
    protected Path getExecutablePath() {
        return jarPath;
    }

    @Override
    protected List<String> getProcessArguments() {
        return new ArrayList<>();
    }

    @Override
    protected boolean performHealthCheck() {
        return HealthCheckUtils.waitForPort("127.0.0.1", httpPort, config.getDefaultTimeout());
    }

    public int getHttpPort() {
        return httpPort;
    }

    public String getMcpUrl() {
        return "http://127.0.0.1:" + httpPort + "/mcp";
    }
}
