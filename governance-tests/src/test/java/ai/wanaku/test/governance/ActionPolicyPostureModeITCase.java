package ai.wanaku.test.governance;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.AuditClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Action-policy behavior in the non-enforcing posture modes. The posture is as strict as possible
 * ({@code no_match: deny}, {@code on_failure: deny}), so any request that reaches the upstream proves
 * that the mode, not a permissive setting, let it through.
 *
 * <p>Each mode runs four decisions against one server: an unavailable policy (the startup policy is
 * invalid and no revision is active), an explicit allow, an explicit deny, and no matching rule.
 * {@code audit} records each decision and lets the request continue. {@code disabled} skips the
 * action-policy evaluation and records no action-policy event.
 */
@Timeout(180)
class ActionPolicyPostureModeITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "posture-modes";
    private static final String INVALID_STARTUP_POLICY = "{\"rules\":\"invalid\"}";
    private static final String DISABLED_REASON = "Posture mode integration test";
    private static final String DENY_MESSAGE = "capture_tool is denied by the posture mode policy";

    @Test
    void auditModeRecordsEveryDecisionAndForwardsEveryRequest() throws Exception {
        startGovernedServer(INVALID_STARTUP_POLICY, GovernancePolicies.posture("audit", "deny", "deny", "basic", null));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        try (ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl());
                AuditClient audit = new AuditClient(server.getBaseUrl(), null);
                HttpClient http = HttpClient.newHttpClient()) {
            for (Decision decision : runDecisions(policies, http)) {
                JsonNode event = AuditRequests.event(audit, decision.correlation(), "wanaku_action_policy");
                assertThat(event.path("namespace").asText()).isEqualTo(NAMESPACE);
                assertThat(event.path("operation").asText()).isEqualTo("tools/call");
                switch (decision.kind()) {
                    case "unavailable" -> {
                        assertThat(event.path("decision").asText()).isEqualTo("error");
                        assertThat(event.path("reason_code").asText()).isEqualTo("action_policy_invalid");
                    }
                    case "allow" -> assertPolicyEvent(event, "allow", "action_policy_allowed", "explicit_allow");
                    case "deny" -> {
                        // The event keeps the would-be denial; the enforcement action says it was not applied.
                        assertPolicyEvent(event, "block", GovernancePolicies.DENY_REASON_CODE, "explicit_deny");
                        assertThat(AuditRequests.textValues(
                                        event.path("attributes").path("deny_rule_ids")))
                                .containsExactly("deny-capture");
                    }
                    case "no-match" -> assertPolicyEvent(event, "allow", "governance_no_match", "no_match");
                    default -> throw new IllegalStateException(decision.kind());
                }

                // The evaluator stage shares the posture: no evaluator matches, and audit keeps the
                // would-be no-match denial while the effective action stays pass.
                JsonNode evaluator = AuditRequests.event(audit, decision.correlation(), "wanaku_evaluator");
                assertThat(evaluator
                                .path("attributes")
                                .path("evaluation_status")
                                .asText())
                        .isEqualTo("no_match");
                assertThat(evaluator.path("attributes").path("enforcement_mode").asText())
                        .isEqualTo("audit");
                assertThat(evaluator.path("attributes").path("would_be_action").asText())
                        .isEqualTo("block");
                assertThat(evaluator.path("attributes").path("effective_action").asText())
                        .isEqualTo("pass");
            }
        }
    }

    @Test
    void disabledModeSkipsEvaluationAndForwardsEveryRequest() throws Exception {
        startGovernedServer(
                INVALID_STARTUP_POLICY,
                GovernancePolicies.posture("disabled", "deny", "deny", "basic", DISABLED_REASON));
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        try (ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl());
                AuditClient audit = new AuditClient(server.getBaseUrl(), null);
                HttpClient http = HttpClient.newHttpClient()) {
            for (Decision decision : runDecisions(policies, http)) {
                JsonNode events = audit.listEvents(Map.of("correlation_id", decision.correlation()))
                        .path("events");
                List<String> filters = new ArrayList<>();
                events.forEach(event -> filters.add(event.path("filter").asText()));
                assertThat(filters)
                        .as("Disabled mode must not evaluate the action policy for %s", decision.kind())
                        .doesNotContain("wanaku_action_policy");

                JsonNode evaluator = AuditRequests.event(audit, decision.correlation(), "wanaku_evaluator");
                assertThat(evaluator.path("reason_code").asText()).isEqualTo("evaluator_disabled");
                assertThat(evaluator.path("decision").asText()).isEqualTo("allow");
                assertThat(evaluator
                                .path("attributes")
                                .path("evaluation_status")
                                .asText())
                        .isEqualTo("not_evaluated");
                assertThat(evaluator.path("attributes").path("enforcement_mode").asText())
                        .isEqualTo("disabled");
                assertThat(evaluator.path("attributes").path("disabled_reason").asText())
                        .isEqualTo(DISABLED_REASON);
            }
        }
    }

    /**
     * Sends one tool call for each decision and checks that each call reaches the upstream exactly
     * once. The unavailable case runs first, while the invalid startup policy is still in effect.
     */
    private List<Decision> runDecisions(ActionPolicyClient policies, HttpClient http) throws Exception {
        List<Decision> decisions = new ArrayList<>();
        assertThat(policies.getActiveRevision().statusCode())
                .as("The invalid startup policy must leave no active revision")
                .isEqualTo(404);
        decisions.add(sendAndExpectForwarded(http, "unavailable", 1));

        activate(policies, GovernancePolicies.allowTool("allow-capture", CAPTURE_TOOL));
        decisions.add(sendAndExpectForwarded(http, "allow", 2));

        activate(policies, GovernancePolicies.denyTool("deny-capture", CAPTURE_TOOL, DENY_MESSAGE));
        decisions.add(sendAndExpectForwarded(http, "deny", 3));

        activate(policies, GovernancePolicies.denyTool("deny-other", "another_tool", DENY_MESSAGE));
        decisions.add(sendAndExpectForwarded(http, "no-match", 4));
        return decisions;
    }

    private Decision sendAndExpectForwarded(HttpClient http, String kind, int expectedUpstreamCalls) throws Exception {
        String correlation = "posture-mode-" + kind;
        JsonNode response = AuditRequests.send(
                http,
                server.getMcpBaseUrl(),
                NAMESPACE,
                AuditRequests.tool(correlation, CAPTURE_TOOL, "posture-mode-payload"),
                correlation);
        assertThat(response.has("result"))
                .as("The %s decision must not block the request: %s", kind, response)
                .isTrue();
        assertThat(captureCounts().toolCalls())
                .as("The %s decision must forward the request to the upstream exactly once", kind)
                .isEqualTo(expectedUpstreamCalls);
        return new Decision(kind, correlation);
    }

    private static void activate(ActionPolicyClient policies, ObjectNode rule) throws Exception {
        var response = policies.updatePolicy(MAPPER.readTree(GovernancePolicies.policy(rule)));
        assertThat(response.statusCode())
                .as("Activate policy: %s", response.body())
                .isEqualTo(200);
    }

    private static void assertPolicyEvent(JsonNode event, String decision, String reason, String policyDecision) {
        assertThat(event.path("decision").asText()).isEqualTo(decision);
        assertThat(event.path("reason_code").asText()).isEqualTo(reason);
        assertThat(event.path("attributes").path("policy_decision").asText()).isEqualTo(policyDecision);
        assertThat(event.path("attributes").path("enforcement_action").asText()).isEqualTo("continue");
        assertThat(event.path("policy_revision").asText()).isNotEmpty();
    }

    private record Decision(String kind, String correlation) {}
}
