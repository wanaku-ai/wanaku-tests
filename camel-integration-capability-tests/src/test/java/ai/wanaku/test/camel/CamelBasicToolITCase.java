package ai.wanaku.test.camel;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.model.ToolInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CIC with simple Camel routes (direct: endpoints, no external dependencies).
 * One fixture ({@code simple-tool/}) with 3 routes and 3 tools:
 * simple-greeting (no params), weather-lookup (auto param), explicit-param-tool (explicit mapping).
 *
 * <p>Covers: registration, invocation, parameters, explicit mapping, DataStore.
 */
@QuarkusTest
class CamelBasicToolITCase extends CamelCapabilityTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(CamelBasicToolITCase.class);

    @BeforeEach
    void assertInfrastructureAvailable() {
        assertThat(isRouterAvailable()).as("Router must be available").isTrue();
        assertThat(isCamelCapabilityAvailable()).as("CIC JAR must be available").isTrue();
        assertThat(isMcpClientAvailable()).as("MCP client must be connected").isTrue();
    }

    @DisplayName("Register CIC tools and verify they appear via MCP and REST API")
    @Test
    void shouldRegisterToolsViaMcp() throws Exception {
        startCapability("simple-tool-svc", "simple-tool");

        mcpClient
                .when()
                .toolsList(page -> {
                    LOG.debug("=== MCP toolsList response [simple-tool]: {}", page.tools());
                    assertThat(page.tools()).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(page.tools()).anyMatch(tool -> "simple-greeting".equals(tool.name()));
                    assertThat(page.tools()).anyMatch(tool -> "weather-lookup".equals(tool.name()));
                    assertThat(page.tools()).anyMatch(tool -> "explicit-param-tool".equals(tool.name()));
                })
                .thenAssertResults();

        List<ToolInfo> tools = routerClient.listTools();
        assertThat(tools).anyMatch(tool -> "simple-greeting".equals(tool.getName()));
    }

    @DisplayName("Invoke simple-greeting tool via MCP and verify response")
    @Test
    void shouldInvokeSimpleToolViaMcp() throws Exception {
        startCapability("simple-tool-svc", "simple-tool");

        mcpClient
                .when()
                .toolsCall("simple-greeting", Map.of(), response -> {
                    LOG.debug("=== MCP toolsCall response [simple-greeting]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    assertThat(response.content().get(0).asText().text()).contains("Hello from CIC!");
                })
                .thenAssertResults();
    }

    @DisplayName("Invoke weather-lookup tool with city parameter and verify substitution")
    @Test
    void shouldInvokeToolWithParameters() throws Exception {
        startCapability("simple-tool-svc", "simple-tool");

        mcpClient
                .when()
                .toolsCall("weather-lookup", Map.of("city", "London"), response -> {
                    LOG.debug("=== MCP toolsCall response [weather-lookup]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    assertThat(response.content().get(0).asText().text()).contains("London");
                })
                .thenAssertResults();
    }

    @DisplayName("Invoke explicit-param-tool with mapped header and verify substitution")
    @Test
    void shouldInvokeToolWithExplicitParameterMapping() throws Exception {
        startCapability("simple-tool-svc", "simple-tool");

        mcpClient
                .when()
                .toolsCall("explicit-param-tool", Map.of("myValue", "test-value-123"), response -> {
                    LOG.debug("=== MCP toolsCall response [explicit-param-tool]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content()).isNotEmpty();
                    assertThat(response.content().get(0).asText().text()).contains("test-value-123");
                })
                .thenAssertResults();
    }

    @DisplayName("Invoke weather-lookup without required city parameter")
    @Test
    void shouldHandleMissingRequiredParameter() throws Exception {
        startCapability("simple-tool-svc", "simple-tool");

        mcpClient
                .when()
                .toolsCall("weather-lookup", Map.of(), response -> {
                    LOG.debug(
                            "=== MCP toolsCall response [weather-lookup-no-param]: isError={}, content={}",
                            response.isError(),
                            response.content());
                    assertThat(response.isError())
                            .as("CIC does not validate required params")
                            .isFalse();
                    String text = response.content().get(0).asText().text();
                    assertThat(text).contains("Weather report for");
                    assertThat(text).doesNotContain("London");
                })
                .thenAssertResults();
    }

    @DisplayName("Load tool config from Data Store and verify tool works identically")
    @Test
    void shouldLoadToolFromDataStore() throws Exception {
        assertThat(isDataStoreAvailable())
                .as("Data Store API must be available")
                .isTrue();

        String routesContent = readFixtureFromClasspath("simple-tool/routes.camel.yaml");
        String rulesContent = readFixtureFromClasspath("simple-tool/rules.yaml");

        dataStoreClient.upload("test-routes.camel.yaml", routesContent);
        dataStoreClient.upload("test-rules.yaml", rulesContent);

        startCapabilityFromDataStore(
                "ds-tool-svc", "datastore://test-routes.camel.yaml", "datastore://test-rules.yaml", null);

        mcpClient
                .when()
                .toolsCall("simple-greeting", Map.of(), response -> {
                    LOG.debug("=== MCP toolsCall response [simple-greeting-datastore]: {}", response.content());
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content().get(0).asText().text()).contains("Hello from CIC!");
                })
                .thenAssertResults();
    }
}
