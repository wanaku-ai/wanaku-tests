package ai.wanaku.test.http;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.HttpToolConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests for HTTP tool error handling via MCP invocation.
 * Focuses on unreachable endpoints and timeout behavior.
 */
@QuarkusTest
class HttpToolErrorHandlingITCase extends HttpCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(HttpToolErrorHandlingITCase.class);

    @BeforeEach
    void assumeFullStackAndMcpAvailable() {
        assumeThat(isFullStackAvailable())
                .as("Full stack (Router + HTTP Tool Service) required for MCP invocation tests")
                .isTrue();
        assumeThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();
    }

    @DisplayName("Return error when tool targets an unreachable endpoint")
    @Test
    void shouldReturnErrorForUnreachableEndpoint() {
        // Given - Register tool pointing to a closed localhost port
        HttpToolConfig config = HttpToolConfig.builder()
                .name("unreachable-endpoint-tool")
                .description("Tool targeting a closed port to simulate connection failure")
                .uri("http://127.0.0.1:1")
                .method("GET")
                .build();

        routerClient.registerTool(config);

        // When/Then - Invoke and verify error is reported
        mcpClient
                .when()
                .toolsCall("unreachable-endpoint-tool", Map.of(), response -> {
                    LOG.debug("=== MCP toolsCall response [unreachable-endpoint]: {}", response.content());
                    assertThat(response.isError())
                            .as("Unreachable endpoint must return an error response")
                            .isTrue();
                    assertThat(String.valueOf(response.content()))
                            .as("Error response should include diagnostic content")
                            .isNotBlank();
                })
                .thenAssertResults();
    }

    @DisplayName("Return error when tool invocation times out")
    @Test
    void shouldReturnErrorForTimeout() {
        // Given - Register tool pointing to a delayed endpoint to trigger timeout
        HttpToolConfig config = HttpToolConfig.builder()
                .name("timeout-endpoint-tool")
                .description("Tool targeting a delayed endpoint to simulate timeout")
                .uri("https://httpbin.org/delay/10")
                .method("GET")
                .build();

        routerClient.registerTool(config);

        // When/Then - Invoke and verify timeout is reported as an error
        mcpClient
                .when()
                .toolsCall("timeout-endpoint-tool", Map.of(), response -> {
                    LOG.debug("=== MCP toolsCall response [timeout-endpoint]: {}", response.content());
                    assertThat(response.isError())
                            .as("Timeout should return an error response")
                            .isTrue();
                    assertThat(String.valueOf(response.content()))
                            .as("Timeout error should include diagnostic content")
                            .isNotBlank();
                })
                .thenAssertResults();
    }
}
