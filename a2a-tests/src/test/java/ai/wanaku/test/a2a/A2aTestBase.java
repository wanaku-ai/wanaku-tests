package ai.wanaku.test.a2a;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.fixtures.TestFixtures;
import ai.wanaku.test.managers.CamelCapabilityManager;
import ai.wanaku.test.utils.HealthCheckUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

public abstract class A2aTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(A2aTestBase.class);
    protected final ObjectMapper mapper = new ObjectMapper();
    protected HttpClient httpClient;
    protected String agentName;

    @TempDir
    Path fixtureDirectory;

    private final List<CamelCapabilityManager> capabilities = new ArrayList<>();
    private final List<String> registeredAgentPaths = new ArrayList<>();
    private final List<String> registeredNamespaces = new ArrayList<>();

    @BeforeEach
    void setupA2aInfrastructure() {
        boolean available = isServerRunning()
                && serverManager.getA2aBaseUrl() != null
                && (config.isA2aEnabled() || System.getProperty("wanaku.test.external.a2a.port") != null);
        if (!available) {
            LOG.warn(
                    "A2A tests require a running A2A-enabled Wanaku server; enable wanaku.test.server.a2a-enabled or provide wanaku.test.external.a2a.port");
        }
        assumeThat(available).as("A2A-enabled server is available").isTrue();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getDefaultTimeout())
                .build();
        agentName = "a2a-" + UUID.randomUUID();
    }

    @AfterEach
    void teardownA2aInfrastructure() throws Exception {
        // Always stop every child process, including when a management cleanup fails.
        try {
            for (String path : registeredAgentPaths) {
                HttpResponse<String> response = request("DELETE", getServerBaseUrl() + path, null);
                assertThat(response.statusCode())
                        .as("Remove agent %s: %s", path, response.body())
                        .isIn(200, 204, 404);
            }
            for (int index = registeredNamespaces.size() - 1; index >= 0; index--) {
                String namespace = registeredNamespaces.get(index);
                HttpResponse<String> response =
                        request("DELETE", getServerBaseUrl() + "/api/v1/namespaces/" + namespace, null);
                assertThat(response.statusCode())
                        .as("Remove namespace %s: %s", namespace, response.body())
                        .isIn(200, 204, 404);
            }
        } finally {
            try {
                for (CamelCapabilityManager manager : capabilities) {
                    manager.stop();
                }
            } finally {
                if (httpClient != null) {
                    httpClient.close();
                }
            }
        }
    }

    protected String startAgent(String marker) throws Exception {
        boolean available = config.getCamelCapabilityJarPath() != null
                && config.getCamelCapabilityJarPath().toFile().isFile();
        if (!available) {
            LOG.warn("A2A upstream fixture requires CIC JAR at {}", config.getCamelCapabilityJarPath());
        }
        assumeThat(available)
                .as("Camel Integration Capability JAR is available")
                .isTrue();
        CamelCapabilityManager manager = new CamelCapabilityManager(config);
        Path fixture = TestFixtures.load("a2a-agent", fixtureDirectory.resolve(marker));
        manager.prepare(
                marker,
                fixture.resolve("routes.camel.yaml").toUri().toString(),
                fixture.resolve("dependencies.txt").toUri().toString());
        String origin = "http://127.0.0.1:" + manager.getHttpPort();
        TestFixtures.load(
                "a2a-agent", fixtureDirectory.resolve(marker), Map.of("AGENT_MARKER", marker, "UPSTREAM_URL", origin));
        manager.setLogContext("a2a", getClass().getSimpleName(), marker);
        capabilities.add(manager);
        manager.start(marker);
        assertThat(HealthCheckUtils.waitForHealthy(origin + "/.well-known/agent-card.json", config.getDefaultTimeout()))
                .as("Camel A2A card is ready at %s", origin)
                .isTrue();
        assertThat(json(request("GET", origin + "/.well-known/agent-card.json", null))
                        .path("protocolVersion")
                        .asText())
                .isEqualTo("0.3.0");
        return origin + "/agent";
    }

    protected JsonNode registerAgent(String namespace, String address) throws Exception {
        String path = agentPath(namespace);
        registeredAgentPaths.add(path);
        HttpResponse<String> response =
                request("POST", getServerBaseUrl() + "/api/v1/agents", entry(namespace, address));
        assertThat(response.statusCode())
                .as("Register agent: %s", response.body())
                .isIn(200, 201);
        return json(response).path("data");
    }

    protected void registerNamespace(String namespace, List<String> members) throws Exception {
        registeredNamespaces.add(namespace);
        Map<String, Object> definition =
                members == null ? Map.of("name", namespace) : Map.of("name", namespace, "members", members);
        HttpResponse<String> response = request("POST", getServerBaseUrl() + "/api/v1/namespaces", definition);
        assertThat(response.statusCode())
                .as("Register namespace %s: %s", namespace, response.body())
                .isIn(200, 201);
    }

    protected Map<String, Object> entry(String namespace, String address) {
        return Map.of(
                "name",
                agentName,
                "namespace",
                namespace,
                "description",
                "Camel A2A integration fixture",
                "address",
                address,
                "cardAddress",
                URI.create(address).resolve("/.well-known/agent-card.json").toString());
    }

    protected String agentPath(String namespace) {
        return "/api/v1/agents/" + namespace + "/" + agentName;
    }

    protected String proxyUrl(String namespace) {
        return serverManager.getA2aBaseUrl() + "/" + namespace + "/a2a/" + agentName;
    }

    protected Map<String, Object> message(String messageId) {
        return Map.of(
                "message",
                Map.of(
                        "kind",
                        "message",
                        "role",
                        "user",
                        "messageId",
                        messageId,
                        "parts",
                        List.of(Map.of("kind", "text", "text", "A2A integration test"))));
    }

    protected JsonNode rpc(String namespace, String method, Object params) throws Exception {
        HttpResponse<String> response = request(
                "POST",
                proxyUrl(namespace),
                Map.of("jsonrpc", "2.0", "id", "request-" + method, "method", method, "params", params));
        assertThat(response.statusCode())
                .as("A2A %s: %s", method, response.body())
                .isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(body.path("id").asText()).isEqualTo("request-" + method);
        return body;
    }

    protected HttpResponse<String> request(String method, String url, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(config.getDefaultTimeout());
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
}
