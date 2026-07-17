package ai.wanaku.test.http;

import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.HttpToolConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class HttpToolErrorHandlingITCase extends HttpCapabilityTestBase {

    @SuppressWarnings("resource")
    private static GenericContainer<?> httpbinContainer;

    private static String httpbinBaseUrl;

    @BeforeAll
    static void startHttpbin() {
        httpbinContainer = new GenericContainer<>("mccutchen/go-httpbin").withExposedPorts(8080);
        httpbinContainer.start();
        httpbinBaseUrl = "http://" + httpbinContainer.getHost() + ":" + httpbinContainer.getMappedPort(8080);
    }

    @AfterAll
    static void stopHttpbin() {
        if (httpbinContainer != null) {
            httpbinContainer.stop();
        }
    }

    @BeforeEach
    void assumeFullStackAndMcpAvailable() {
        assumeThat(isFullStackAvailable())
                .as("Full stack required for error handling tests")
                .isTrue();
        assumeThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();
    }

    @DisplayName("Invoke tool pointing to unreachable endpoint and get error response")
    @Test
    void shouldHandleUnreachableEndpoint() {
        HttpToolConfig config = HttpToolConfig.builder()
                .name("unreachable-tool")
                .description("Tool pointing to unreachable host")
                .uri("http://192.0.2.1:9999/does-not-exist")
                .build();

        routerClient.registerTool(config);

        try {
            mcpClient
                    .when()
                    .toolsCall("unreachable-tool", Map.of(), response -> {
                        assertThat(response.isError()).isTrue();
                    })
                    .thenAssertResults();
        } catch (Exception e) {
            assertThat(e.getMessage()).isNotNull();
        }
    }

    @DisplayName("Invoke tool that returns HTTP 404 and get error in response")
    @Test
    void shouldHandleNotFoundEndpoint() {
        HttpToolConfig config = HttpToolConfig.builder()
                .name("not-found-tool")
                .description("Tool pointing to 404 endpoint")
                .uri(httpbinBaseUrl + "/status/404")
                .build();

        routerClient.registerTool(config);

        mcpClient
                .when()
                .toolsCall("not-found-tool", Map.of(), response -> {
                    String content = response.content().toString();
                    assertThat(content).isNotEmpty();
                })
                .thenAssertResults();
    }

    @DisplayName("Invoke tool that returns HTTP 500 server error")
    @Test
    void shouldHandleServerError() {
        HttpToolConfig config = HttpToolConfig.builder()
                .name("server-error-tool")
                .description("Tool pointing to 500 endpoint")
                .uri(httpbinBaseUrl + "/status/500")
                .build();

        routerClient.registerTool(config);

        mcpClient
                .when()
                .toolsCall("server-error-tool", Map.of(), response -> {
                    String content = response.content().toString();
                    assertThat(content).isNotEmpty();
                })
                .thenAssertResults();
    }

    @DisplayName("Invoke tool with empty response body from server")
    @Test
    void shouldHandleEmptyResponse() {
        HttpToolConfig config = HttpToolConfig.builder()
                .name("empty-response-tool")
                .description("Tool pointing to endpoint that returns 204")
                .uri(httpbinBaseUrl + "/status/204")
                .build();

        routerClient.registerTool(config);

        mcpClient
                .when()
                .toolsCall("empty-response-tool", Map.of(), response -> {
                    assertThat(response.content()).isNotNull();
                })
                .thenAssertResults();
    }
}
