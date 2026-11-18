package ai.wanaku.test.router;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.config.TestConfiguration;
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

/** Membership contracts from wanaku-ai/wanaku#2287; no feature-detection skips. */
@Timeout(120)
class VirtualNamespaceMembershipITCase {
    private static final Logger LOG = LoggerFactory.getLogger(VirtualNamespaceMembershipITCase.class);
    private static final String NAMESPACES = "/api/v1/namespaces";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private TestConfiguration config;
    private WanakuServerManager server;

    @BeforeEach
    void requireManagedServer() {
        config = TestConfiguration.fromSystemProperties();
        boolean binaryAvailable = config.getServerBinaryPath() != null && Files.exists(config.getServerBinaryPath());
        if (!binaryAvailable) {
            LOG.warn(
                    "Skipping virtual namespace membership: Wanaku binary unavailable at {}",
                    config.getServerBinaryPath());
        }
        assumeThat(binaryAvailable)
                .as("A managed Wanaku server binary is required")
                .isTrue();
        if (System.getProperty("wanaku.test.external.mgmt.port") != null) {
            LOG.warn("Skipping virtual namespace membership: external server cannot provide an isolated registry");
        }
        assumeThat(System.getProperty("wanaku.test.external.mgmt.port"))
                .as("Membership tests need an isolated managed registry")
                .isNull();
    }

    @AfterEach
    void stopManagedServer() {
        if (server != null) {
            server.stop();
        }
        http.close();
    }

    @Test
    @DisplayName("Virtual namespace membership survives create, get, list, and update")
    void shouldRoundTripMembership() throws Exception {
        startServer(null);
        createConcrete("member-a");
        createConcrete("member-b");
        createVirtual("aggregate", List.of("member-a", "member-b"));

        assertMembers("aggregate", "member-a", "member-b");
        HttpResponse<String> listed = request("GET", NAMESPACES, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(data(listed)).anySatisfy(entry -> {
            assertThat(entry.path("name").asText()).isEqualTo("aggregate");
            assertThat(entry.path("members")).isEqualTo(mapper.valueToTree(List.of("member-a", "member-b")));
        });

        assertSuccess(
                request("PUT", NAMESPACES + "/aggregate", Map.of("name", "aggregate", "members", List.of("member-b"))));
        assertMembers("aggregate", "member-b");
        assertThat(request("GET", NAMESPACES + "/member-a", null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Creation rejects missing, virtual, and self members without registering the definition")
    void shouldRejectInvalidMembersOnCreate() throws Exception {
        startServer(null);
        createConcrete("member-a");
        createVirtual("existing-virtual", List.of("member-a"));

        for (String invalidMember : List.of("unknown-member", "existing-virtual", "rejected")) {
            HttpResponse<String> rejected = request(
                    "POST", NAMESPACES, Map.of("name", "rejected", "members", List.of("member-a", invalidMember)));
            assertRejected(rejected);
            assertThat(mapper.readTree(rejected.body()).path("error").asText())
                    .isEqualTo(expectedMembershipError("rejected", invalidMember));
            assertThat(request("GET", NAMESPACES + "/rejected", null).statusCode())
                    .isEqualTo(404);
        }
        assertMembers("existing-virtual", "member-a");
    }

    @Test
    @DisplayName("Rejected membership updates preserve the previous concrete members")
    void shouldPreserveMembershipAfterRejectedUpdates() throws Exception {
        startServer(null);
        createConcrete("member-a");
        createConcrete("member-b");
        createVirtual("other-virtual", List.of("member-b"));
        createVirtual("aggregate", List.of("member-a"));

        for (String invalidMember : List.of("unknown-member", "other-virtual", "aggregate")) {
            HttpResponse<String> rejected = request(
                    "PUT",
                    NAMESPACES + "/aggregate",
                    Map.of("name", "aggregate", "members", List.of("member-b", invalidMember)));
            assertRejected(rejected);
            assertThat(mapper.readTree(rejected.body()).path("error").asText())
                    .isEqualTo(expectedMembershipError("aggregate", invalidMember));
            assertMembers("aggregate", "member-a");
        }
    }

    @Test
    @DisplayName("Virtual namespace names obey DNS label boundaries")
    void shouldValidateVirtualNamespaceNames() throws Exception {
        startServer(null);
        createConcrete("member-a");
        for (String invalidName :
                List.of("", "Uppercase", "with_under", "with.dot", "with space", "-first", "last-", "a".repeat(64))) {
            assertRejected(request("POST", NAMESPACES, Map.of("name", invalidName, "members", List.of("member-a"))));
        }
        createVirtual("v", List.of("member-a"));
        assertMembers("v", "member-a");
        createVirtual("v".repeat(63), List.of("member-a"));
        assertMembers("v".repeat(63), "member-a");
    }

    @Test
    @DisplayName("The targets input alias stores the same concrete membership")
    void shouldAcceptTargetsAlias() throws Exception {
        startServer(null);
        createConcrete("member-a");
        assertSuccess(request("POST", NAMESPACES, Map.of("name", "aggregate", "targets", List.of("member-a"))));
        assertMembers("aggregate", "member-a");
    }

    @Test
    @DisplayName("Startup configuration loads concrete and virtual namespace definitions")
    void shouldLoadNamespaceConfiguration() throws Exception {
        startServer(
                """
                namespaces:
                  - name: member-a
                  - name: member-b
                  - name: aggregate
                    members: [member-a, member-b]
                """);
        assertMembers("aggregate", "member-a", "member-b");
    }

    @Test
    @DisplayName("Membership and concrete definitions survive a persisted restart")
    void shouldRestoreMembershipAfterRestart() throws Exception {
        startServer(null);
        createConcrete("member-a");
        createConcrete("member-b");
        createVirtual("aggregate", List.of("member-a"));
        assertSuccess(request(
                "PUT",
                NAMESPACES + "/aggregate",
                Map.of("name", "aggregate", "members", List.of("member-a", "member-b"))));
        server.stopPreservingState();
        server.start("virtual-namespace-membership-restart");

        assertMembers("aggregate", "member-a", "member-b");
        assertThat(request("GET", NAMESPACES + "/member-a", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", NAMESPACES + "/member-b", null).statusCode()).isEqualTo(200);
    }

    @Test
    @Timeout(240)
    @DisplayName("Configuration rejects unknown, virtual, and self members")
    void shouldRejectInvalidConfiguredMembership() throws Exception {
        for (String invalidMember : List.of("unknown-member", "other-virtual", "rejected-aggregate")) {
            prepareServer(
                    """
                    namespaces:
                      - name: member-a
                      - name: other-virtual
                        members: [member-a]
                      - name: rejected-aggregate
                        members: [%s]
                    """
                            .formatted(invalidMember));
            assertInvalidDefinitionNotExposed(expectedMembershipError("rejected-aggregate", invalidMember), false);
            server.stop();
        }
    }

    @Test
    @Timeout(240)
    @DisplayName("Snapshot restoration rejects unknown, virtual, and self members")
    void shouldRejectInvalidRestoredMembership() throws Exception {
        for (String invalidMember : List.of("unknown-member", "other-virtual", "rejected-aggregate")) {
            prepareServer(null);
            Files.writeString(
                    server.getPersistDirectory().resolve("registry.json"),
                    mapper.writeValueAsString(Map.of(
                            "namespaces",
                            List.of(
                                    Map.of("name", "member-a"),
                                    Map.of("name", "other-virtual", "members", List.of("member-a")),
                                    Map.of("name", "rejected-aggregate", "members", List.of(invalidMember))))));
            assertInvalidDefinitionNotExposed(expectedMembershipError("rejected-aggregate", invalidMember), true);
            server.stop();
        }
    }

    @Test
    @DisplayName("Legacy snapshots without membership fields still restore concrete namespaces")
    void shouldRestoreLegacyNamespaceSnapshot() throws Exception {
        prepareServer(null);
        Files.writeString(
                server.getPersistDirectory().resolve("registry.json"),
                "{\"namespaces\":[{\"name\":\"legacy-member\"}]}");
        server.start("virtual-namespace-legacy-snapshot");
        HttpResponse<String> response = request("GET", NAMESPACES + "/legacy-member", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(data(response).path("name").asText()).isEqualTo("legacy-member");
        createVirtual("aggregate", List.of("legacy-member"));
        assertMembers("aggregate", "legacy-member");
    }

    private String expectedMembershipError(String namespace, String invalidMember) {
        return namespace.equals(invalidMember)
                ? "a virtual namespace cannot include itself"
                : "member must be an existing concrete namespace: " + invalidMember;
    }

    private void assertInvalidDefinitionNotExposed(String expectedError, boolean restoredSnapshot) throws Exception {
        try {
            server.start("virtual-namespace-invalid-definition");
        } catch (IllegalStateException rejectedStartup) {
            // Rejecting the whole load or excluding the invalid definition are both valid.
            // Require a relevant diagnostic so an unrelated startup failure cannot pass.
            String diagnostic = Files.readString(server.getLogFile().toPath());
            assertThat(diagnostic)
                    .as("Startup must report the membership validation failure: %s", rejectedStartup.getMessage())
                    .contains(expectedError);
            return;
        }
        assertThat(request("GET", NAMESPACES + "/rejected-aggregate", null).statusCode())
                .isEqualTo(404);
        String diagnostic = Files.readString(server.getLogFile().toPath());
        if (restoredSnapshot && diagnostic.contains("invalid persisted namespace graph; snapshot was not loaded")) {
            // Rejecting the whole snapshot is intentional: no seeded row may leak into the live registry.
            assertThat(diagnostic).contains(expectedError);
            HttpResponse<String> listed = request("GET", NAMESPACES, null);
            assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
            assertThat(data(listed))
                    .extracting(entry -> entry.path("name").asText())
                    .containsExactly("default");
            HttpResponse<String> baseline = request("GET", NAMESPACES + "/default", null);
            assertThat(baseline.statusCode()).as(baseline.body()).isEqualTo(200);
            assertThat(data(baseline).hasNonNull("members")).isFalse();
        } else {
            // A loader that excludes only the invalid definition must retain the valid input rows.
            HttpResponse<String> concrete = request("GET", NAMESPACES + "/member-a", null);
            assertThat(concrete.statusCode()).as(concrete.body()).isEqualTo(200);
            assertThat(data(concrete).hasNonNull("members")).isFalse();
            assertMembers("other-virtual", "member-a");
        }
    }

    private void startServer(String namespaceConfig) throws Exception {
        prepareServer(namespaceConfig);
        server.start("virtual-namespace-membership");
    }

    private void prepareServer(String namespaceConfig) throws Exception {
        server = new WanakuServerManager(config.toBuilder()
                .persistenceEnabled(true)
                .extraWanakuConfig(namespaceConfig)
                .build());
        server.prepare();
    }

    private void createConcrete(String name) throws Exception {
        assertSuccess(request("POST", NAMESPACES, Map.of("name", name)));
    }

    private void createVirtual(String name, List<String> members) throws Exception {
        assertSuccess(request("POST", NAMESPACES, Map.of("name", name, "members", members)));
    }

    private void assertMembers(String name, String... expected) throws Exception {
        HttpResponse<String> response = request("GET", NAMESPACES + "/" + name, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(data(response).path("members")).isEqualTo(mapper.valueToTree(List.of(expected)));
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body()).path("data");
    }

    private void assertSuccess(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    private void assertRejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(400, 499);
    }

    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(15))
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
