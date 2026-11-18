package ai.wanaku.test.governance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.awaitility.Awaitility;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.McpTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

/** Both namespace scopes must be evaluated without selecting a pending mixed-outcome policy. */
@Timeout(120)
class VirtualNamespacePolicyITCase extends GovernanceTestBase {
    private static final String OWNER = "virtual-policy-owner";
    private static final String VIRTUAL = "virtual-policy-view";

    @Test
    @DisplayName("Virtual tool and prompt actions evaluate policies in both request and owner namespaces")
    void shouldEvaluateVirtualAndConcretePolicyScopes() throws Exception {
        ObjectNode governance = (ObjectNode) MAPPER.readTree(GovernancePolicies.enforce("deny"));
        ObjectNode namespaces = governance.putObject("namespaces");
        namespaces.putObject(OWNER).put("no_match", "allow");
        namespaces.putObject(VIRTUAL).put("no_match", "allow");
        startGovernedServer(
                """
                {"rules":[
                  {"id":"allow-virtual","effect":"allow","selectors":{"namespace":"%s"}},
                  {"id":"allow-owner","effect":"allow","selectors":{"namespace":"%s"}}
                ]}
                """
                        .formatted(VIRTUAL, OWNER),
                governance.toString());
        registerCaptureForward(OWNER);

        try (HttpClient http = HttpClient.newHttpClient();
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            HttpRequest definition = HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/api/v1/namespaces"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(Map.of("name", VIRTUAL, "members", List.of(OWNER)))))
                    .build();
            HttpResponse<String> created = http.send(definition, HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).as(created.body()).isBetween(200, 299);

            McpTestClient virtualClient = connect("virtual-ns/" + VIRTUAL);
            // Forward catalog discovery is asynchronous; wait for the prompt before invocation.
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> virtualClient
                    .when()
                    .promptsList(page -> assertThat(page.prompts())
                            .anyMatch(prompt -> (OWNER + "/" + CAPTURE_PROMPT).equals(prompt.name())))
                    .thenAssertResults());

            CaptureCounts before = captureCounts();
            JsonNode tool = AuditRequests.send(
                    http,
                    server.getMcpBaseUrl(),
                    "virtual-ns/" + VIRTUAL,
                    AuditRequests.tool("virtual-policy-tool", OWNER + "/" + CAPTURE_TOOL, "scope-evidence"),
                    "virtual-policy-tool-request");
            assertThat(tool.has("error")).as(tool.toString()).isFalse();
            assertThat(tool.has("result")).isTrue();
            assertThat(tool.path("result").path("isError").asBoolean()).isFalse();
            assertBothRulesRecorded(audit, "virtual-policy-tool-request", "tools/call");

            ObjectNode prompt = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", "virtual-policy-prompt")
                    .put("method", "prompts/get");
            prompt.putObject("params")
                    .put("name", OWNER + "/" + CAPTURE_PROMPT)
                    .putObject("arguments")
                    .put("topic", "scope-evidence");
            JsonNode rendered = AuditRequests.send(
                    http, server.getMcpBaseUrl(), "virtual-ns/" + VIRTUAL, prompt, "virtual-policy-prompt-request");
            assertThat(rendered.has("error")).as(rendered.toString()).isFalse();
            assertThat(rendered.has("result")).isTrue();
            assertBothRulesRecorded(audit, "virtual-policy-prompt-request", "prompts/get");

            CaptureCounts after = captureCounts();
            assertThat(after.toolCalls()).isEqualTo(before.toolCalls() + 1);
            assertThat(after.promptGets()).isEqualTo(before.promptGets() + 1);
            assertThat(after.resourceReads()).isEqualTo(before.resourceReads());
        }
    }

    private void assertBothRulesRecorded(AuditClient audit, String correlation, String operation) {
        Set<String> matchedRuleIds = new HashSet<>();
        JsonNode events =
                audit.listEvents(Map.of("correlation_id", correlation)).path("events");
        for (JsonNode event : events) {
            if ("wanaku_action_policy".equals(event.path("filter").asText())
                    && operation.equals(event.path("operation").asText())) {
                AuditRequests.assertMetadata(event, correlation, "decision", operation);
                assertThat(event.path("attributes").path("virtual_namespace").asText())
                        .as("Virtual request namespace in audit event %s", event)
                        .isEqualTo(VIRTUAL);
                assertThat(event.path("attributes").path("concrete_namespace").asText())
                        .as("Concrete owner namespace in audit event %s", event)
                        .isEqualTo(OWNER);
                matchedRuleIds.addAll(
                        AuditRequests.textValues(event.path("attributes").path("matched_rule_ids")));
            }
        }
        // Support one combined audit event or separate events for each evaluated scope.
        assertThat(matchedRuleIds)
                .as("Both namespace rules must be evaluated for %s: %s", correlation, events)
                .contains("allow-virtual", "allow-owner");
    }
}
