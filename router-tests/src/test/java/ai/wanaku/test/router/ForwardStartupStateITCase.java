package ai.wanaku.test.router;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
import ai.wanaku.test.managers.MockMcpServerManager;
import ai.wanaku.test.managers.WanakuServerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Startup data boundary from wanaku-ai/wanaku#2050: forwards from wanaku.yaml are static and not persisted,
 * discovered entries are discovered again at startup, and only disabled discovered entries are persisted.
 */
@Timeout(180)
class ForwardStartupStateITCase {
    private static final Logger LOG = LoggerFactory.getLogger(ForwardStartupStateITCase.class);
    private static final String FORWARDS = "/api/v1/forwards";
    private static final String STATIC_FORWARD = "static-fwd";
    private static final String STATIC_NAMESPACE = "static-ns";
    private static final String API_FORWARD = "api-fwd";
    private static final String API_NAMESPACE = "api-ns";
    private static final String DISABLED_TOOL = "restartService";
    private static final String ENABLED_TOOL = "scaleDeployment";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private TestConfiguration config;
    private MockMcpServerManager upstream;
    private WanakuServerManager server;
    private String staticForwardConfig;

    @BeforeEach
    void startUpstreamAndServer() throws Exception {
        config = TestConfiguration.fromSystemProperties();
        boolean binaryAvailable = config.getServerBinaryPath() != null && Files.exists(config.getServerBinaryPath());
        if (!binaryAvailable) {
            LOG.warn("Skipping forward startup state: Wanaku binary unavailable at {}", config.getServerBinaryPath());
        }
        assumeThat(binaryAvailable)
                .as("A managed Wanaku server binary is required")
                .isTrue();
        assumeThat(System.getProperty("wanaku.test.external.mgmt.port"))
                .as("Restart tests need a managed server")
                .isNull();
        Path jarPath = Path.of("../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar")
                .toAbsolutePath();
        if (!jarPath.toFile().exists()) {
            LOG.warn("Skipping forward startup state: mock MCP server JAR unavailable at {}", jarPath);
        }
        assumeThat(jarPath.toFile().exists())
                .as("Mock MCP server JAR must be available at %s", jarPath)
                .isTrue();

        upstream = new MockMcpServerManager(jarPath, config);
        upstream.prepare();
        upstream.setLogContext("mock-mcp-server", getClass().getSimpleName(), "forward-startup-state");
        upstream.start("forward-startup-state");

        staticForwardConfig = "forwards: [{name: %s, address: '%s', namespace: %s}]"
                .formatted(STATIC_FORWARD, upstream.getMcpUrl(), STATIC_NAMESPACE);
        server = new WanakuServerManager(config.toBuilder()
                .persistenceEnabled(true)
                .extraWanakuConfig(staticForwardConfig)
                .build());
        server.prepare();
        server.start("forward-startup-state");

        assertSuccess(request("POST", "/api/v1/namespaces", Map.of("name", API_NAMESPACE)));
        assertSuccess(request(
                "POST",
                FORWARDS,
                Map.of(
                        "name",
                        API_FORWARD,
                        "address",
                        upstream.getMcpUrl(),
                        "namespace",
                        API_NAMESPACE,
                        "static",
                        true)));
        assertSuccess(request("PUT", "/api/v1/tools/" + DISABLED_TOOL + "/disable?namespace=" + API_NAMESPACE, null));
    }

    @AfterEach
    void stopProcesses() {
        if (server != null) {
            server.stop();
        }
        if (upstream != null) {
            upstream.stop();
        }
        http.close();
    }

    @Test
    @DisplayName("Forwards from wanaku.yaml are static and the API cannot create static forwards")
    void shouldMarkOnlyConfiguredForwardsStatic() throws Exception {
        assertThat(forward(STATIC_FORWARD).path("static").asBoolean()).isTrue();
        assertThat(forward(API_FORWARD).path("static").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("The snapshot keeps API forwards and disabled entries, and leaves out static and discovered data")
    void shouldPersistOnlyRuntimeData() throws Exception {
        server.stopPreservingState();

        JsonNode snapshot =
                mapper.readTree(Files.readString(server.getPersistDirectory().resolve("registry.json")));
        assertThat(names(snapshot.path("forwards"))).containsExactly(API_FORWARD);
        assertThat(snapshot.path("tools")).singleElement().satisfies(tool -> {
            assertThat(tool.path("name").asText()).isEqualTo(DISABLED_TOOL);
            assertThat(tool.path("forwardId").asText()).isEqualTo(API_FORWARD);
            assertThat(tool.path("enabled").asBoolean()).isFalse();
        });
        assertThat(snapshot.path("resources")).isEmpty();
        assertThat(snapshot.path("prompts")).isEmpty();
    }

    @Test
    @DisplayName("Startup discovers restored forwards again and keeps disabled entries disabled")
    void shouldRediscoverRestoredForwardsAtStartup() throws Exception {
        server.stopPreservingState();
        server.start("forward-startup-state-restart");

        JsonNode restored = forward(API_FORWARD);
        assertThat(restored.path("available").asBoolean())
                .as("startup discovery of the restored forward: %s", restored)
                .isTrue();
        List<JsonNode> tools = toolsOf(API_FORWARD);
        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.path("name").asText()).isEqualTo(ENABLED_TOOL);
            assertThat(tool.path("enabled").asBoolean()).isTrue();
        });
        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.path("name").asText()).isEqualTo(DISABLED_TOOL);
            assertThat(tool.path("enabled").asBoolean()).isFalse();
        });
    }

    @Test
    @DisplayName("A forward removed from wanaku.yaml is gone after restart, and a deleted static forward comes back")
    void shouldFollowWanakuYamlForStaticForwards() throws Exception {
        assertSuccess(request("DELETE", FORWARDS + "/" + STATIC_FORWARD, null));
        assertThat(request("GET", FORWARDS + "/" + STATIC_FORWARD, null).statusCode())
                .isEqualTo(404);

        server.stopPreservingState();
        server.start("forward-startup-state-static-back");
        assertThat(forward(STATIC_FORWARD).path("static").asBoolean()).isTrue();
        assertThat(forward(STATIC_FORWARD).path("available").asBoolean()).isTrue();

        server.stopPreservingState();
        Path wanakuConfig = server.getWanakuConfigFile();
        Files.writeString(wanakuConfig, Files.readString(wanakuConfig).replace(staticForwardConfig, ""));
        server.start("forward-startup-state-static-removed");

        assertThat(request("GET", FORWARDS + "/" + STATIC_FORWARD, null).statusCode())
                .isEqualTo(404);
        assertThat(names(data(request("GET", FORWARDS, null)))).containsExactly(API_FORWARD);
        assertThat(toolsOf(STATIC_FORWARD)).isEmpty();
    }

    private JsonNode forward(String name) throws Exception {
        HttpResponse<String> response = request("GET", FORWARDS + "/" + name, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return data(response);
    }

    private List<JsonNode> toolsOf(String forwardId) throws Exception {
        List<JsonNode> tools = new ArrayList<>();
        data(request("GET", "/api/v1/tools", null)).forEach(tool -> {
            if (forwardId.equals(tool.path("forwardId").asText())) {
                tools.add(tool);
            }
        });
        return tools;
    }

    private List<String> names(JsonNode entries) {
        List<String> names = new ArrayList<>();
        entries.forEach(entry -> names.add(entry.path("name").asText()));
        return names;
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body()).path("data");
    }

    private void assertSuccess(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
