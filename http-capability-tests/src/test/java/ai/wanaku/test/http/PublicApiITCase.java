package ai.wanaku.test.http;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.HttpToolConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests invoking public APIs (httpbin.org, jsonplaceholder, meowfacts) via MCP protocol.
 *
 * These tests register HTTP tools pointing to real public APIs and invoke them
 * through the MCP client, verifying the full end-to-end flow.
 *
 * Skip condition: set system property wanaku.test.external-apis=false to disable.
 */
@QuarkusTest
@DisabledIfSystemProperty(
        named = "wanaku.test.external-apis",
        matches = "false",
        disabledReason = "External API tests disabled via system property"
)
class PublicApiITCase extends HttpCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(PublicApiITCase.class);

    @BeforeEach
    void assumeFullStackAndMcpAvailable() {
        assumeThat(isFullStackAvailable())
                .as("Full stack (Router + HTTP Tool Service) required for MCP invocation tests")
                .isTrue();
        assumeThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();
    }

    @DisplayName("Register 2 HTTP tools and verify both appear when listing tools via MCP")
    @Test
    void httpbin_shouldListToolsViaMcp() {
        // Given - Register tools
        routerClient.registerTool(HttpToolConfig.builder()
                .name("mcp-list-tool-1")
                .description("First tool for MCP list test")
                .uri("https://httpbin.org/get?tool=1")
                .build());

        routerClient.registerTool(HttpToolConfig.builder()
                .name("mcp-list-tool-2")
                .description("Second tool for MCP list test")
                .uri("https://httpbin.org/get?tool=2")
                .build());

        // When/Then - List tools via MCP
        mcpClient
                .when()
                .toolsList(page -> {
                    LOG.debug("=== MCP toolsList response [mcp-list-tool]: {}", page.tools());
                    assertThat(page.tools()).hasSize(2);
                    assertThat(page.tools())
                            .extracting(tool -> tool.name())
                            .containsExactlyInAnyOrder("mcp-list-tool-1", "mcp-list-tool-2");
                })
                .thenAssertResults();
    }

    @DisplayName("Call JSONPlaceholder API through Wanaku and receive a list of fake users")
    @Test
    void jsonplaceholder_shouldInvokeUsersEndpoint() {
        // Given - Register JSONPlaceholder users tool
        HttpToolConfig config = HttpToolConfig.builder()
                .name("jsonplaceholder-users-tool")
                .description("Tool using JSONPlaceholder users endpoint")
                .uri("https://jsonplaceholder.typicode.com/users")
                .method("GET")
                .build();

        routerClient.registerTool(config);

        // When/Then - Invoke and verify we get users data
        mcpClient
                .when()
                .toolsCall("jsonplaceholder-users-tool", Map.of(), response -> {
                    LOG.debug("=== MCP toolsCall response [jsonplaceholder-users]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    // JSONPlaceholder returns array of users with name, email, etc.
                    String content = response.content().toString();
                    assertThat(content).contains("email");
                    assertThat(content).contains("name");
                })
                .thenAssertResults();
    }

    @DisplayName("Send POST request with JSON body through Wanaku and verify httpbin receives it")
    @Test
    void httpbin_shouldInvokePostEndpoint() {
        // Given - Register httpbin POST tool with wanaku_body property
        // wanaku_body is a reserved argument name that becomes the HTTP request body
        HttpToolConfig config = HttpToolConfig.builder()
                .name("httpbin-post-tool")
                .description("Tool using httpbin.org POST endpoint")
                .uri("https://httpbin.org/post")
                .method("POST")
                .property("wanaku_body", "string", "The request body")
                .build();

        routerClient.registerTool(config);

        // When/Then - Invoke tool via MCP with wanaku_body argument
        String jsonBody = "{\"message\": \"hello\", \"count\": 42}";
        mcpClient
                .when()
                .toolsCall("httpbin-post-tool", Map.of("wanaku_body", jsonBody), response -> {
                    LOG.debug("=== MCP toolsCall response [httpbin-post-tool]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    // httpbin echoes back the JSON body in "json" field
                    String content = response.content().toString();
                    assertThat(content).contains("message");
                    assertThat(content).contains("hello");
                })
                .thenAssertResults();
    }

    @DisplayName("Request 2 random cat facts from MeowFacts API by passing count parameter")
    @Test
    void meowfacts_shouldInvokeWithRequiredParameter() {
        // Given - Tool with required parameter (like Wanaku docs example)
        // wanaku tools add -n "meow-facts" --uri "https://meowfacts.herokuapp.com?count={parameter.valueOrElse('count',
        // 1)}"
        //                  --property "count:int,The count of facts to retrieve" --required count
        HttpToolConfig config = HttpToolConfig.builder()
                .name("meow-facts")
                .description("Retrieve random facts about cats")
                .uri("https://meowfacts.herokuapp.com?count={parameter.valueOrElse('count', 1)}")
                .property("count", "int", "The count of facts to retrieve")
                .required("count")
                .build();

        routerClient.registerTool(config);

        // When/Then - Invoke with parameter value
        mcpClient
                .when()
                .toolsCall("meow-facts", Map.of("count", 2), response -> {
                    LOG.debug("=== MCP toolsCall response [meow-facts]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    // MeowFacts API returns {"data": ["fact1", "fact2"]} - verify we got 2 facts
                    String content = response.content().toString();
                    assertThat(content).contains("data");
                    // Count facts by counting array elements (simple string matching)
                    String dataArray = content.substring(content.indexOf("["), content.lastIndexOf("]") + 1);
                    // Split by comma outside quotes to count elements
                    int factCount = dataArray.split("\",\"").length;
                    assertThat(factCount).isEqualTo(2);
                })
                .thenAssertResults();
    }

    @DisplayName("Send custom HTTP headers via configurationData and verify httpbin receives them")
    @Test
    void httpbin_shouldInvokeWithCustomHeaders() {
        // Given - Tool with headers configured via configurationData (properties format)
        // Per wanaku-tool-service-http/README.md: headers use "header.Name=value" format
        // Must use /addWithPayload endpoint with configurationData as string
        HttpToolConfig config = HttpToolConfig.builder()
                .name("httpbin-headers-tool")
                .description("Tool with custom headers via configurationData")
                .uri("https://httpbin.org/headers")
                .method("GET")
                .build();

        // Headers in properties format (like --configuration-from-file in CLI)
        String configurationData = "header.X-Custom-Header=test-value-123\nheader.X-Api-Key=my-api-key";

        routerClient.registerToolWithConfig(config, configurationData);

        // When/Then - Invoke and verify custom headers reached httpbin
        mcpClient
                .when()
                .toolsCall("httpbin-headers-tool", Map.of(), response -> {
                    LOG.debug("=== MCP toolsCall response [httpbin-headers-tool]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();

                    // httpbin /headers returns {"headers": {"X-Custom-Header": "...", ...}}
                    String content = response.content().toString();
                    assertThat(content).contains("X-Custom-Header");
                    assertThat(content).contains("test-value-123");
                    assertThat(content).contains("X-Api-Key");
                    assertThat(content).contains("my-api-key");
                })
                .thenAssertResults();
    }

    @DisplayName("Pass dynamic value that gets substituted into URL path segment")
    @Test
    void httpbin_shouldInvokeWithOptionalParameter() {
        // Given - Tool with optional parameter and default value
        // Similar pattern to: wanaku tools add -n "echo-param" --uri
        // "https://httpbin.org/anything/{parameter.valueOrElse('value', 'default')}"
        //                     --property "value:string,Value to echo"
        HttpToolConfig config = HttpToolConfig.builder()
                .name("echo-param")
                .description("Echo a parameter value")
                .uri("https://httpbin.org/anything/{parameter.valueOrElse('value', 'default')}")
                .property("value", "string", "Value to echo in URL path")
                .build();

        routerClient.registerTool(config);

        // When/Then - Invoke with custom value
        mcpClient
                .when()
                .toolsCall("echo-param", Map.of("value", "test-value"), response -> {
                    LOG.debug("=== MCP toolsCall response [echo-param]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    // httpbin /anything returns JSON with "url" field containing the full request URL
                    String content = response.content().toString();
                    assertThat(content).contains("httpbin.org/anything/test-value");
                })
                .thenAssertResults();
    }
}
