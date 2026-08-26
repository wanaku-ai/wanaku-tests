package ai.wanaku.test.forward;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.SessionIdProxy;
import ai.wanaku.test.managers.MockMcpServerManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Verifies that HTTP headers from the MCP client request are forwarded
 * to downstream MCP servers when calling forwarded tools.
 *
 * <p>Covers <a href="https://github.com/wanaku-ai/wanaku/issues/873">wanaku#873</a>:
 * the mock MCP server exposes an {@code echoAuthHeader} tool annotated with
 * {@code @McpParamHeader("Authorization")}. The test sends an Authorization
 * header via the MCP transport and verifies the forwarded tool receives it.
 */
@QuarkusTest
class McpHeaderForwardingITCase extends McpForwardingTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(McpHeaderForwardingITCase.class);
    private static final String MOCK_SERVER_JAR = "../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar";
    private static final String FORWARD_NAME = "header-fwd-test";
    private static final String TEST_TOKEN = "test-token-873";

    private MockMcpServerManager mockServer;
    private SessionIdProxy headerProxy;
    private McpTestClient headerMcpClient;

    @BeforeEach
    void setupMockServerAndForward() throws Exception {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();

        Path jarPath = Path.of(MOCK_SERVER_JAR).toAbsolutePath();
        assumeThat(jarPath.toFile().exists())
                .as("Mock MCP server JAR must be available at " + jarPath)
                .isTrue();

        mockServer = new MockMcpServerManager(jarPath, config);
        mockServer.prepare();
        mockServer.setLogContext("mock-mcp-server", getClass().getSimpleName(), FORWARD_NAME);
        mockServer.start(FORWARD_NAME);

        forwardsClient.add(FORWARD_NAME, mockServer.getMcpUrl(), "default");
        LOG.info("Registered forward '{}' -> '{}'", FORWARD_NAME, mockServer.getMcpUrl());

        waitForToolDiscovery();
    }

    private void waitForToolDiscovery() throws Exception {
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    SessionIdProxy tempProxy = new SessionIdProxy(getServerMcpBaseUrl() + "/default");
                    try {
                        tempProxy.start();
                        McpTestClient tempClient = new McpTestClient(tempProxy.getBaseUrl(), null);
                        tempClient.connect();
                        try {
                            tempClient
                                    .when()
                                    .toolsList(page ->
                                            assertThat(page.tools()).anyMatch(t -> "echoAuthHeader".equals(t.name())))
                                    .thenAssertResults();
                        } finally {
                            tempClient.disconnect();
                        }
                    } finally {
                        tempProxy.close();
                    }
                });
    }

    private McpTestClient createClientWithHeaders(Map<String, String> headers) throws Exception {
        SessionIdProxy newProxy = new SessionIdProxy(getServerMcpBaseUrl() + "/default");
        newProxy.start();

        if (headerProxy != null) {
            headerProxy.close();
        }
        headerProxy = newProxy;

        McpTestClient client = new McpTestClient(headerProxy.getBaseUrl(), null, headers);
        client.connect();
        return client;
    }

    @AfterEach
    void teardownMockServer() {
        if (headerMcpClient != null) {
            try {
                headerMcpClient.disconnect();
            } catch (Exception e) {
                LOG.debug("MCP disconnect: {}", e.getMessage());
            }
            headerMcpClient = null;
        }
        if (headerProxy != null) {
            try {
                headerProxy.close();
            } catch (Exception e) {
                LOG.debug("Proxy close: {}", e.getMessage());
            }
            headerProxy = null;
        }
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    @DisplayName("Authorization header is forwarded to downstream MCP server tool (issue #873)")
    @Test
    void shouldForwardAuthorizationHeader() throws Exception {
        headerMcpClient = createClientWithHeaders(Map.of("Authorization", "Bearer " + TEST_TOKEN));

        headerMcpClient
                .when()
                .toolsCall("echoAuthHeader", Map.of("marker", "fwd-test"), response -> {
                    LOG.info(
                            "echoAuthHeader response: isError={}, content={}",
                            response.isError(),
                            response.content());

                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    String text = response.content().get(0).asText().text();
                    LOG.info("echoAuthHeader response text: {}", text);
                    assertThat(text)
                            .as("Authorization header should be forwarded to the downstream MCP server")
                            .contains(TEST_TOKEN);
                    assertThat(text).contains("marker=fwd-test");
                })
                .thenAssertResults();
    }

    @DisplayName("Trying to send an authenticated request without mandatory parameters causes error")
    @Test
    void shouldHandleAbsentAuthHeader() throws Exception {
        headerMcpClient = createClientWithHeaders(Map.of());

        headerMcpClient
                .when()
                .toolsCall("echoAuthHeader", Map.of("marker", "no-auth"), response -> {
                    LOG.info(
                            "echoAuthHeader response (no auth): isError={}, content={}",
                            response.isError(),
                            response.content());
                    assertThat(response.isError()).isTrue();
                })
                .thenAssertResults();
    }
}
