package ai.wanaku.test.forward;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.McpTestClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.SessionIdProxy;
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
import org.junit.jupiter.api.function.Executable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Tests the confirmed MCP contracts of wanaku-ai/wanaku#2287 against independent upstreams. */
@Timeout(180)
class VirtualNamespaceForwardingITCase {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualNamespaceForwardingITCase.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> OWNERS = List.of("virtual-east", "virtual-west", "virtual-outside");
    private static final String VIRTUAL = "virtual-composite";
    private static final String SECOND_VIEW = "virtual-overlap";
    private static final String TOOL = "capture_tool";
    private static final String PROMPT = "capture_prompt";
    private static final Path CAPTURE_JAR = Path.of(
                    "../fixtures/governance-capture-server/target/quarkus-app/quarkus-run.jar")
            .toAbsolutePath()
            .normalize();

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<MockMcpServerManager> upstreams = new ArrayList<>();
    private final List<SessionIdProxy> proxies = new ArrayList<>();
    private final List<McpTestClient> clients = new ArrayList<>();
    private final Map<String, List<String>> resourceNames = new HashMap<>();
    private WanakuServerManager server;
    private ForwardsClient forwards;

    @BeforeEach
    void startInfrastructure() throws Exception {
        TestConfiguration config = TestConfiguration.fromSystemProperties();
        boolean available = config.getServerBinaryPath() != null
                && Files.exists(config.getServerBinaryPath())
                && Files.exists(CAPTURE_JAR);
        if (!available) {
            LOG.warn(
                    "Virtual namespace forwarding requires a Wanaku binary ({}) and capture fixture ({})",
                    config.getServerBinaryPath(),
                    CAPTURE_JAR);
        }
        assumeThat(available)
                .as("Managed Wanaku binary and capture MCP fixture must be available")
                .isTrue();
        assumeThat(System.getProperty("wanaku.test.external.mgmt.port"))
                .as("This scenario owns its server and registry")
                .isNull();

        // Keep this routing scenario independent of the unresolved combined-policy outcome.
        // Both identities receive an explicit allow, and no credential binding is configured.
        var policy = MAPPER.createObjectNode();
        var rules = policy.putArray("rules");
        var governance = MAPPER.createObjectNode();
        governance
                .putObject("default")
                .put("mode", "enforce")
                .put("no_match", "deny")
                .put("on_failure", "deny");
        var postures = governance.putObject("namespaces");
        for (String namespace : List.of(OWNERS.get(0), OWNERS.get(1), OWNERS.get(2), VIRTUAL, SECOND_VIEW)) {
            var rule = rules.addObject();
            postures.putObject(namespace).put("no_match", "allow");
            rule.put("id", "allow-" + namespace);
            rule.put("effect", "allow");
            rule.putObject("selectors").put("namespace", namespace);
        }
        server = new WanakuServerManager(config.toBuilder()
                .mcpIdFilterEnabled(true)
                .governanceEnabled(true)
                .actionPolicyJson(policy.toString())
                .governanceJson(governance.toString())
                .build());
        server.prepare();
        server.start(getClass().getSimpleName());
        NamespaceClient namespaces = new NamespaceClient(server.getBaseUrl(), null);
        forwards = new ForwardsClient(server.getBaseUrl(), null);
        for (String owner : OWNERS) {
            namespaces.create(owner);
            MockMcpServerManager upstream = new MockMcpServerManager(CAPTURE_JAR, config);
            upstreams.add(upstream);
            upstream.prepare();
            upstream.start(owner);
            forwards.add("forward-" + owner, upstream.getMcpUrl(), owner);
            awaitDiscovery(owner);
        }
        defineVirtual("POST", VIRTUAL, List.of(OWNERS.get(0), OWNERS.get(1)));
        defineVirtual("POST", SECOND_VIEW, List.of(OWNERS.get(0), OWNERS.get(1)));
    }

    @AfterEach
    void stopInfrastructure() {
        List<Executable> cleanup = new ArrayList<>();
        clients.forEach(client -> cleanup.add(client::disconnect));
        proxies.forEach(proxy -> cleanup.add(proxy::close));
        if (server != null) {
            cleanup.add(server::stop);
        }
        upstreams.forEach(upstream -> cleanup.add(upstream::stop));
        cleanup.add(http::close);
        assertAll("Stop every virtual namespace test process and client", cleanup);
    }

    @Test
    @DisplayName("Virtual discovery preserves qualified duplicate entries and excludes nonmembers")
    void shouldListQualifiedEntriesWithoutChangingConcreteInventories() throws Exception {
        McpTestClient virtual = connect("virtual-ns/" + VIRTUAL);
        assertInventory(virtual, List.of(OWNERS.get(0), OWNERS.get(1)));
        for (String owner : OWNERS) {
            McpTestClient concrete = connect(owner);
            concrete.when()
                    .toolsList(page -> assertThat(page.tools())
                            .anyMatch(tool -> TOOL.equals(tool.name()))
                            .noneMatch(tool -> tool.name().startsWith(owner + "/")))
                    .thenAssertResults();
            concrete.when()
                    .promptsList(page -> assertThat(page.prompts()).anyMatch(prompt -> PROMPT.equals(prompt.name())))
                    .thenAssertResults();
            concrete.when()
                    .toolsCall(TOOL, Map.of("payload", owner), response -> {
                        assertThat(response.isError()).isFalse();
                        assertThat(response.content().getFirst().asText().text())
                                .contains("payload=" + owner);
                    })
                    .thenAssertResults();
            concrete.when()
                    .promptsGet(PROMPT, Map.of("topic", owner), response -> assertThat(response.messages()
                                    .getFirst()
                                    .content()
                                    .asText()
                                    .text())
                            .contains("topic=" + owner))
                    .thenAssertResults();
        }
        for (int index = 0; index < upstreams.size(); index++) {
            assertCounts(index, 1, 1);
        }
    }

    @Test
    @DisplayName("Qualified tool and prompt calls dispatch to their concrete upstream after member reorder")
    void shouldDispatchEachQualifiedEntryToItsOwner() throws Exception {
        McpTestClient virtual = connect("virtual-ns/" + VIRTUAL);
        invoke(virtual, OWNERS.get(0), "east-before-reorder");
        assertCounts(0, 1, 1);
        assertCounts(1, 0, 0);
        assertCounts(2, 0, 0);
        invoke(virtual, OWNERS.get(1), "west-before-reorder");
        assertCounts(0, 1, 1);
        assertCounts(1, 1, 1);
        defineVirtual("PUT", VIRTUAL, List.of(OWNERS.get(1), OWNERS.get(0)));
        invoke(virtual, OWNERS.get(0), "east-after-reorder");
        invoke(virtual, OWNERS.get(1), "west-after-reorder");
        assertCounts(0, 2, 2);
        assertCounts(1, 2, 2);
        assertCounts(2, 0, 0);
    }

    @Test
    @DisplayName("Unknown and nonmember qualified entries never reach an upstream")
    void shouldRejectTargetsOutsideTheVirtualInventory() throws Exception {
        McpTestClient virtual = connect("virtual-ns/" + VIRTUAL);
        assertRejected(virtual, OWNERS.get(2) + "/" + TOOL, OWNERS.get(2) + "/" + PROMPT);
        assertRejected(virtual, OWNERS.get(0) + "/missing-tool", OWNERS.get(0) + "/missing-prompt");
        for (int index = 0; index < upstreams.size(); index++) {
            assertCounts(index, 0, 0);
        }
    }

    @Test
    @DisplayName("Forward removal removes its entries from every overlapping virtual view")
    void shouldPropagateForwardRemovalToExistingVirtualConnections() throws Exception {
        McpTestClient first = connect("virtual-ns/" + VIRTUAL);
        McpTestClient second = connect("virtual-ns/" + SECOND_VIEW);
        assertInventory(first, List.of(OWNERS.get(0), OWNERS.get(1)));
        assertInventory(second, List.of(OWNERS.get(0), OWNERS.get(1)));
        assertThat(forwards.remove("forward-" + OWNERS.get(0))).isTrue();
        for (McpTestClient view : List.of(first, second)) {
            assertInventory(view, List.of(OWNERS.get(1)));
            assertRejected(view, OWNERS.get(0) + "/" + TOOL, OWNERS.get(0) + "/" + PROMPT);
            invoke(view, OWNERS.get(1), "surviving-member");
        }
        assertCounts(0, 0, 0);
        assertCounts(1, 2, 2);
        assertCounts(2, 0, 0);
    }

    @Test
    @DisplayName("Membership changes update an existing virtual connection without changing another view")
    void shouldApplyMembershipChangesToExistingConnections() throws Exception {
        McpTestClient changed = connect("virtual-ns/" + VIRTUAL);
        McpTestClient unchanged = connect("virtual-ns/" + SECOND_VIEW);
        assertInventory(changed, List.of(OWNERS.get(0), OWNERS.get(1)));
        defineVirtual("PUT", VIRTUAL, List.of(OWNERS.get(1), OWNERS.get(2)));
        assertInventory(changed, List.of(OWNERS.get(1), OWNERS.get(2)));
        assertInventory(unchanged, List.of(OWNERS.get(0), OWNERS.get(1)));
        assertRejected(changed, OWNERS.get(0) + "/" + TOOL, OWNERS.get(0) + "/" + PROMPT);
        assertRejected(unchanged, OWNERS.get(2) + "/" + TOOL, OWNERS.get(2) + "/" + PROMPT);
        invoke(changed, OWNERS.get(2), "new-member");
        invoke(unchanged, OWNERS.get(0), "original-member");
        assertCounts(0, 1, 1);
        assertCounts(1, 0, 0);
        assertCounts(2, 1, 1);
    }

    @Test
    @DisplayName("Concrete enable and disable changes are visible in every overlapping virtual connection")
    void shouldReflectEnableAndDisableInEveryVirtualView() throws Exception {
        McpTestClient first = connect("virtual-ns/" + VIRTUAL);
        McpTestClient second = connect("virtual-ns/" + SECOND_VIEW);
        assertInventory(first, List.of(OWNERS.get(0), OWNERS.get(1)));
        assertInventory(second, List.of(OWNERS.get(0), OWNERS.get(1)));
        String owner = OWNERS.get(0);
        Map<String, String> targets = Map.of(
                "tools",
                TOOL,
                "prompts",
                PROMPT,
                "resources",
                resourceNames.get(owner).getFirst());
        for (var target : targets.entrySet()) {
            toggle(target.getKey(), owner, target.getValue(), false);
        }
        for (McpTestClient view : List.of(first, second)) {
            assertToggledVisibility(view, owner, targets.get("resources"), false);
            assertRejected(view, owner + "/" + TOOL, owner + "/" + PROMPT);
        }
        assertCounts(0, 0, 0);
        // Other owners with the same names must remain available.
        invoke(first, OWNERS.get(1), "unaffected-owner");
        for (var target : targets.entrySet()) {
            toggle(target.getKey(), owner, target.getValue(), true);
        }
        for (McpTestClient view : List.of(first, second)) {
            assertInventory(view, List.of(OWNERS.get(0), OWNERS.get(1)));
            invoke(view, owner, "re-enabled-owner");
        }
        assertCounts(0, 2, 2);
        assertCounts(1, 1, 1);
        assertCounts(2, 0, 0);
    }

    private void assertToggledVisibility(McpTestClient view, String owner, String resourceName, boolean enabled) {
        view.when()
                .toolsList(page -> assertThat(
                                page.tools().stream().anyMatch(tool -> (owner + "/" + TOOL).equals(tool.name())))
                        .isEqualTo(enabled))
                .thenAssertResults();
        view.when()
                .promptsList(page -> assertThat(page.prompts().stream()
                                .anyMatch(prompt -> (owner + "/" + PROMPT).equals(prompt.name())))
                        .isEqualTo(enabled))
                .thenAssertResults();
        view.when()
                .resourcesList(page -> assertThat(page.resources().stream()
                                .anyMatch(resource -> (owner + "/" + resourceName).equals(resource.name())))
                        .isEqualTo(enabled))
                .thenAssertResults();
    }

    private void toggle(String kind, String owner, String name, boolean enabled) throws Exception {
        String path = "/api/v1/" + kind + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/"
                + (enabled ? "enable" : "disable") + "?namespace=" + owner;
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                        .timeout(Duration.ofSeconds(20))
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Toggle %s/%s in %s: %s", kind, name, owner, response.body())
                .isEqualTo(200);
        JsonNode entry = MAPPER.readTree(response.body()).path("data");
        assertThat(entry.path("enabled").asBoolean()).isEqualTo(enabled);
        assertThat(entry.path("namespace").asText()).isEqualTo(owner);
        assertThat(entry.path("forwardId").asText()).isEqualTo("forward-" + owner);
    }

    private void assertInventory(McpTestClient client, List<String> owners) {
        client.when()
                .toolsList(
                        page -> assertThat(page.tools())
                                .extracting(tool -> tool.name())
                                .containsExactlyInAnyOrderElementsOf(owners.stream()
                                        .flatMap(owner -> List.of(
                                                        TOOL,
                                                        "capture_typed_tool",
                                                        "capture_star*tool",
                                                        "capture_question?tool")
                                                .stream()
                                                .map(name -> owner + "/" + name))
                                        .toList()))
                .thenAssertResults();
        client.when()
                .promptsList(page -> assertThat(page.prompts())
                        .extracting(prompt -> prompt.name())
                        .containsExactlyInAnyOrderElementsOf(owners.stream()
                                .flatMap(owner -> List.of(PROMPT, "capture_optional_prompt").stream()
                                        .map(name -> owner + "/" + name))
                                .toList()))
                .thenAssertResults();
        // URI qualification and resource reads await the resource-addressing decision in #2287.
        // Names, however, must already preserve each concrete namespace qualifier.
        client.when()
                .resourcesList(page -> assertThat(page.resources())
                        .extracting(resource -> resource.name())
                        .containsExactlyInAnyOrderElementsOf(owners.stream()
                                .flatMap(owner ->
                                        resourceNames.get(owner).stream().map(name -> owner + "/" + name))
                                .toList()))
                .thenAssertResults();
    }

    private void invoke(McpTestClient client, String owner, String marker) {
        client.when()
                .toolsCall(owner + "/" + TOOL, Map.of("payload", marker), response -> {
                    assertThat(response.isError()).isFalse();
                    assertThat(response.content().getFirst().asText().text()).contains("payload=" + marker);
                })
                .thenAssertResults();
        client.when()
                .promptsGet(owner + "/" + PROMPT, Map.of("topic", marker), response -> assertThat(response.messages()
                                .getFirst()
                                .content()
                                .asText()
                                .text())
                        .contains("topic=" + marker))
                .thenAssertResults();
    }

    private void assertRejected(McpTestClient client, String tool, String prompt) {
        client.when()
                .toolsCall(tool)
                .withArguments(Map.of("payload", "must-not-arrive"))
                .withErrorAssert(error -> assertThat(error.message()).isNotBlank())
                .send()
                .thenAssertResults();
        client.when()
                .promptsGet(prompt)
                .withArguments(Map.of("topic", "must-not-arrive"))
                .withErrorAssert(error -> assertThat(error.message()).isNotBlank())
                .send()
                .thenAssertResults();
    }

    private void defineVirtual(String method, String name, List<String> members) throws Exception {
        String path = "/api/v1/namespaces" + ("PUT".equals(method) ? "/" + name : "");
        String body = MAPPER.writeValueAsString(Map.of("name", name, "members", members));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s virtual namespace %s: %s", method, name, response.body())
                .isIn(200, 201);
    }

    private McpTestClient connect(String path) throws Exception {
        SessionIdProxy proxy = new SessionIdProxy(server.getMcpBaseUrl() + "/" + path);
        proxies.add(proxy);
        proxy.start();
        McpTestClient client = new McpTestClient(proxy.getBaseUrl(), null);
        clients.add(client);
        client.connect();
        return client;
    }

    private void awaitDiscovery(String owner) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    for (var catalog :
                            Map.of("tools", 4, "prompts", 2, "resources", 3).entrySet()) {
                        HttpResponse<String> response = http.send(
                                HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/api/v1/" + catalog.getKey()))
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode()).isEqualTo(200);
                        JsonNode entries = MAPPER.readTree(response.body()).path("data");
                        assertThat(entries)
                                .filteredOn(entry ->
                                        owner.equals(entry.path("namespace").asText()))
                                .as("Discovered %s in concrete namespace %s", catalog.getKey(), owner)
                                .hasSize(catalog.getValue());
                        if ("resources".equals(catalog.getKey())) {
                            List<String> names = new ArrayList<>();
                            entries.forEach(entry -> {
                                if (owner.equals(entry.path("namespace").asText())) {
                                    names.add(entry.path("name").asText());
                                }
                            });
                            assertThat(names).doesNotHaveDuplicates().allSatisfy(name -> assertThat(name)
                                    .isNotBlank());
                            resourceNames.put(owner, names);
                        }
                    }
                });
    }

    private void assertCounts(int upstream, int tools, int prompts) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + upstreams.get(upstream).getHttpPort() + "/capture/counts"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode counts = MAPPER.readTree(response.body());
        assertThat(counts.path("toolCalls").asInt())
                .as("tool calls at upstream %s", upstream)
                .isEqualTo(tools);
        assertThat(counts.path("promptGets").asInt())
                .as("prompt gets at upstream %s", upstream)
                .isEqualTo(prompts);
        assertThat(counts.path("resourceReads").asInt()).isZero();
    }
}
