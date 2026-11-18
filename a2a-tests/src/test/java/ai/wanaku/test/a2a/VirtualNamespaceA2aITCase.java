package ai.wanaku.test.a2a;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.wanaku.test.client.AuditClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

/** Real-process A2A 0.3 checks for qualified routes through virtual namespaces. */
@Timeout(240)
class VirtualNamespaceA2aITCase extends A2aTestBase {
    private String east;
    private String west;
    private String outside;
    private String group;
    private final Map<String, String> addresses = new LinkedHashMap<>();

    @BeforeEach
    void registerVirtualAgents() throws Exception {
        east = agentName + "-east";
        west = agentName + "-west";
        outside = agentName + "-outside";
        group = "virtual-" + agentName;
        for (String owner : List.of(east, west, outside)) {
            registerNamespace(owner, null);
            String address = startAgent(owner);
            addresses.put(owner, address);
            registerAgent(owner, address);
        }
        registerNamespace(group, List.of(east, west));
    }

    @Test
    @DisplayName("Virtual catalog and Agent Cards preserve qualified names and isolate identical task IDs")
    void shouldDiscoverAndRouteSameNameAgentsAndTasks() throws Exception {
        assertCatalog(east, west);
        for (String owner : List.of(east, west)) {
            String url = virtualUrl(owner);
            HttpResponse<String> response = request("GET", url + "/.well-known/agent-card.json", null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode card = json(response);
            assertThat(card.path("name").asText()).isEqualTo(owner);
            assertThat(card.path("url").asText()).isEqualTo(url);
            assertThat(card.path("preferredTransport").asText()).isEqualTo("JSONRPC");
            assertThat(card.path("capabilities").path("streaming").asBoolean()).isFalse();
            assertThat(card.has("additionalInterfaces")).isFalse();
        }

        JsonNode eastTask = virtualRpc(east, "message/send", message("east-message"), "east-send")
                .path("result");
        JsonNode westTask = virtualRpc(west, "message/send", message("west-message"), "west-send")
                .path("result");
        assertThat(eastTask.path("id").asText()).isEqualTo("fixture-task");
        assertThat(eastTask.path("id")).isEqualTo(westTask.path("id"));
        assertTaskOwner(eastTask, east);
        assertTaskOwner(westTask, west);
        for (String owner : List.of(east, west)) {
            JsonNode task = virtualRpc(owner, "tasks/get", Map.of("id", "fixture-task"), owner + "-get")
                    .path("result");
            assertTaskOwner(task, owner);
            JsonNode cancel = virtualRpc(owner, "tasks/cancel", Map.of("id", "fixture-task"), owner + "-cancel");
            assertThat(cancel.at("/error/code").asInt()).isEqualTo(-32002);
            assertThat(cancel.at("/error/message").asText()).isEqualTo("Completed fixture task cannot be canceled");
            assertRequests(owner, 3);
        }
        assertRequests(outside, 0);
    }

    @Test
    @DisplayName("Virtual membership changes immediately change qualified agent routing without nonmember dispatch")
    void shouldRejectNonmembersAndApplyMembershipChanges() throws Exception {
        assertMissingRoute(outside);
        assertRequests(outside, 0);
        HttpResponse<String> changed = request(
                "PUT",
                getServerBaseUrl() + "/api/v1/namespaces/" + group,
                Map.of("name", group, "members", List.of(east, outside)));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertCatalog(east, outside);
        assertMissingRoute(west);
        assertTaskOwner(
                virtualRpc(outside, "message/send", message("new-member"), "new-member")
                        .path("result"),
                outside);
        assertRequests(east, 0);
        assertRequests(west, 0);
        assertRequests(outside, 1);
    }

    @Test
    @DisplayName("Agent removal, re-registration, and address edits update virtual discovery and owner dispatch")
    void shouldApplyAgentLifecycleChangesWithoutRestart() throws Exception {
        HttpResponse<String> removed = request("DELETE", getServerBaseUrl() + agentPath(east), null);
        assertThat(removed.statusCode()).as(removed.body()).isIn(200, 204);
        assertCatalog(west);
        assertMissingRoute(east);
        assertRequests(east, 0);

        registerAgent(east, addresses.get(outside));
        assertCatalog(east, west);
        assertTaskOwner(
                virtualRpc(east, "message/send", message("replacement"), "replacement")
                        .path("result"),
                outside);
        HttpResponse<String> updated =
                request("PUT", getServerBaseUrl() + agentPath(west), entry(west, addresses.get(east)));
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertTaskOwner(
                virtualRpc(west, "message/send", message("updated"), "updated").path("result"), east);
        assertRequests(east, 1);
        assertRequests(west, 0);
        assertRequests(outside, 1);
    }

    @Test
    @DisplayName("Both virtual and concrete policies record namespace identities before allowed and denied dispatch")
    void shouldEvaluateAndAuditBothNamespaceScopes() throws Exception {
        String allowedCorrelation = "allowed-" + agentName;
        JsonNode allowed = virtualRpc(east, "message/send", message("allowed-message"), allowedCorrelation);
        assertThat(allowed.has("error")).as(allowed.toString()).isFalse();
        assertTaskOwner(allowed.path("result"), east);
        assertPolicyEvidence(allowedCorrelation, east, "allow", "allow-a2a-test-agents");
        for (String owner : List.of(east, west)) {
            String correlation = "denied-" + owner;
            JsonNode denied = virtualRpc(owner, "message/send", message("denied-message"), correlation);
            assertThat(denied.at("/error/code").asInt()).isEqualTo(-32003);
            assertThat(denied.at("/error/data/reason_code").asText()).isEqualTo("action_policy_denied");
            assertThat(denied.has("result")).isFalse();
            assertPolicyEvidence(correlation, owner, "block", "deny-a2a-test-message");
        }
        assertRequests(east, 1);
        assertRequests(west, 0);
        assertRequests(outside, 0);
    }

    private String virtualUrl(String owner) {
        return serverManager.getA2aBaseUrl() + "/virtual-ns/" + group + "/a2a/" + owner + "/" + agentName;
    }

    private void assertCatalog(String... owners) throws Exception {
        HttpResponse<String> response =
                request("GET", serverManager.getA2aBaseUrl() + "/virtual-ns/" + group + "/a2a", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode agents = json(response).path("agents");
        assertThat(agents).hasSize(owners.length);
        Set<String> names = new HashSet<>();
        for (JsonNode agent : agents) {
            String owner = agent.path("namespace").asText();
            assertThat(List.of(owners)).contains(owner);
            assertThat(agent.path("name").asText()).isEqualTo(owner + "/" + agentName);
            assertThat(agent.path("url").asText()).isEqualTo(virtualUrl(owner));
            assertThat(agent.path("cardUrl").asText()).isEqualTo(virtualUrl(owner) + "/.well-known/agent-card.json");
            names.add(agent.path("name").asText());
        }
        assertThat(names).hasSize(owners.length);
    }

    private JsonNode virtualRpc(String owner, String method, Object params, String correlation) throws Exception {
        HttpResponse<String> response = sendVirtual(owner, method, params, correlation);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(body.path("id").asText()).isEqualTo(correlation);
        return body;
    }

    private HttpResponse<String> sendVirtual(String owner, String method, Object params, String correlation)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(virtualUrl(owner)))
                .timeout(config.getDefaultTimeout())
                .header("Content-Type", "application/json")
                .header("x-request-id", correlation)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "id", correlation, "method", method, "params", params))))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertMissingRoute(String owner) throws Exception {
        HttpResponse<String> response = sendVirtual(owner, "message/send", message("must-not-arrive"), "missing-route");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        HttpResponse<String> card = request("GET", virtualUrl(owner) + "/.well-known/agent-card.json", null);
        assertThat(card.statusCode()).as(card.body()).isEqualTo(404);
    }

    private void assertTaskOwner(JsonNode task, String marker) {
        assertThat(task.path("id").asText()).isEqualTo("fixture-task");
        assertThat(task.at("/artifacts/0/parts/0/text").asText()).isEqualTo(marker + "--a2a--A2A integration test");
    }

    private void assertRequests(String owner, int expected) throws Exception {
        String url = URI.create(addresses.get(owner)).resolve("/capture/counts").toString();
        HttpResponse<String> response = request("GET", url, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).path("requests").asInt())
                .as("RPC requests received by %s", owner)
                .isEqualTo(expected);
    }

    private void assertPolicyEvidence(String correlation, String owner, String decision, String expectedRule) {
        try (AuditClient audit = new AuditClient(getServerBaseUrl(), null)) {
            JsonNode events =
                    audit.listEvents(Map.of("correlation_id", correlation)).path("events");
            Set<String> scopes = new HashSet<>();
            for (JsonNode event : events) {
                if (!"wanaku_action_policy".equals(event.path("filter").asText())) {
                    continue;
                }
                assertThat(event.path("operation").asText()).isEqualTo("SendMessage");
                assertThat(event.path("protocol").asText()).isEqualTo("a2a");
                assertThat(event.path("target_type").asText()).isEqualTo("agent");
                assertThat(event.path("target").asText()).isEqualTo(agentName);
                assertThat(event.path("decision").asText()).isEqualTo(decision);
                assertThat(event.path("attributes").path("virtual_namespace").asText())
                        .isEqualTo(group);
                assertThat(event.path("attributes").path("concrete_namespace").asText())
                        .isEqualTo(owner);
                assertThat(event.path("attributes").path("matched_rule_ids"))
                        .anySatisfy(rule -> assertThat(rule.asText()).isEqualTo(expectedRule));
                scopes.add(event.path("namespace").asText());
            }
            assertThat(scopes)
                    .as("Audit policy scopes for %s: %s", correlation, events)
                    .containsExactlyInAnyOrder(owner, group);
        }
    }
}
