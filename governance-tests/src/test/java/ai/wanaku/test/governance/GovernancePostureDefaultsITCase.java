package ai.wanaku.test.governance;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.AuditClient;
import ai.wanaku.test.client.EvaluatorClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-safe defaults and namespace inheritance of the governance posture.
 *
 * <p>When {@code wanaku.yaml} has no {@code governance} section, the server must use
 * {@code enforce}, {@code no_match: deny}, {@code on_failure: deny}, and {@code audit_level: basic}.
 * A namespace override changes only the fields it sets; resolution uses the namespace name, so the
 * declaration order of the overrides must not change the result.
 */
@Timeout(180)
class GovernancePostureDefaultsITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "posture-defaults";
    private static final List<String> OPERATIONS = List.of("tools/call", "resources/read", "prompts/get");
    private static final String DISABLED_REASON = "Paused for the inheritance test";

    @Test
    void omittedPostureDeniesUnmatchedActionsAndExplicitAllowOnlyClearsTheActionPolicy() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), null);
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        try (HttpClient http = HttpClient.newHttpClient();
                ActionPolicyClient policies = new ActionPolicyClient(server.getBaseUrl());
                AuditClient audit = new AuditClient(server.getBaseUrl(), null)) {
            assertPosture(
                    new EvaluatorClient(server.getBaseUrl(), null).status(NAMESPACE),
                    "enforce",
                    "deny",
                    "deny",
                    "basic");

            for (String operation : OPERATIONS) {
                JsonNode response = send(http, NAMESPACE, operation, "omitted-" + operation.replace('/', '-'));
                assertActionPolicyDenied(response, "governance_no_match");
            }
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(0, 0, 0));

            // An explicit allow clears the action policy. The evaluator stage applies the same
            // fail-safe no_match: deny on its own, so the request still does not reach the upstream.
            var activated = policies.updatePolicy(MAPPER.readTree(
                    GovernancePolicies.policy(GovernancePolicies.allowTool("allow-capture", CAPTURE_TOOL))));
            assertThat(activated.statusCode())
                    .as("Activate policy: %s", activated.body())
                    .isEqualTo(200);
            JsonNode allowed = send(http, NAMESPACE, "tools/call", "omitted-explicit-allow");
            assertThat(allowed.path("error").path("code").asInt()).isEqualTo(-32001);
            assertThat(allowed.path("error").path("message").asText()).isEqualTo("evaluator: no_match");
            JsonNode policyEvent = AuditRequests.event(audit, "omitted-explicit-allow", "wanaku_action_policy");
            assertThat(policyEvent.path("decision").asText()).isEqualTo("allow");
            assertThat(policyEvent.path("attributes").path("policy_decision").asText())
                    .isEqualTo("explicit_allow");
            JsonNode evaluatorEvent = AuditRequests.event(audit, "omitted-explicit-allow", "wanaku_evaluator");
            assertThat(evaluatorEvent.path("decision").asText()).isEqualTo("block");
            assertThat(evaluatorEvent
                            .path("attributes")
                            .path("evaluation_status")
                            .asText())
                    .isEqualTo("no_match");
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(0, 0, 0));
        }
    }

    @Test
    void omittedPostureFailsClosedWhenThePolicyIsUnavailable() throws Exception {
        startGovernedServer("{\"rules\":\"invalid\"}", null);
        registerCaptureForward(NAMESPACE);
        connect(NAMESPACE);
        try (HttpClient http = HttpClient.newHttpClient()) {
            for (String operation : OPERATIONS) {
                JsonNode response = send(http, NAMESPACE, operation, "unavailable-" + operation.replace('/', '-'));
                assertActionPolicyDenied(response, "action_policy_invalid");
            }
            assertThat(captureCounts()).isEqualTo(new CaptureCounts(0, 0, 0));
        }
    }

    @ParameterizedTest(name = "overrides declared in {0} order")
    @ValueSource(strings = {"forward", "reverse"})
    void namespaceOverridesInheritUnsetFieldsIndependentOfDeclarationOrder(String order) throws Exception {
        ObjectNode governance = MAPPER.createObjectNode();
        governance.set("default", GovernancePolicies.postureNode("enforce", "deny", "deny", "basic", null));
        ObjectNode namespaces = governance.putObject("namespaces");
        List<String> declared = List.of("open", "observed", "paused");
        for (String namespace : "forward".equals(order) ? declared : declared.reversed()) {
            switch (namespace) {
                case "open" -> namespaces.putObject("open").put("no_match", "allow");
                case "observed" -> namespaces.putObject("observed").put("mode", "audit");
                case "paused" ->
                    namespaces.putObject("paused").put("mode", "disabled").put("disabled_reason", DISABLED_REASON);
                default -> throw new IllegalStateException(namespace);
            }
        }
        startGovernedServer(GovernancePolicies.policy(), governance.toString());
        for (String namespace : List.of("strict", "open", "observed", "paused")) {
            registerCaptureForward(namespace);
            connect(namespace);
        }

        EvaluatorClient evaluators = new EvaluatorClient(server.getBaseUrl(), null);
        assertPosture(evaluators.status("strict"), "enforce", "deny", "deny", "basic");
        assertPosture(evaluators.status("open"), "enforce", "allow", "deny", "basic");
        assertPosture(evaluators.status("observed"), "audit", "deny", "deny", "basic");
        JsonNode paused = evaluators.status("paused");
        assertPosture(paused, "disabled", "deny", "deny", "basic");
        assertThat(paused.path("posture").path("disabled_reason").asText()).isEqualTo(DISABLED_REASON);
        assertThat(paused.path("state").asText()).isEqualTo("disabled");

        try (HttpClient http = HttpClient.newHttpClient()) {
            assertActionPolicyDenied(send(http, "strict", "tools/call", order + "-strict"), "governance_no_match");
            assertThat(captureCounts().toolCalls()).isZero();
            for (String namespace : List.of("open", "observed", "paused")) {
                assertThat(send(http, namespace, "tools/call", order + "-" + namespace)
                                .has("result"))
                        .as("Namespace %s must forward the unmatched call", namespace)
                        .isTrue();
            }
            assertThat(captureCounts().toolCalls()).isEqualTo(3);
        }
    }

    private JsonNode send(HttpClient http, String namespace, String operation, String correlation) throws Exception {
        ObjectNode body = MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", correlation)
                .put("method", operation);
        ObjectNode params = body.putObject("params");
        switch (operation) {
            case "tools/call" ->
                params.put("name", CAPTURE_TOOL).putObject("arguments").put("payload", "posture-defaults");
            case "resources/read" -> params.put("uri", CAPTURE_RESOURCE_URI);
            case "prompts/get" ->
                params.put("name", CAPTURE_PROMPT).putObject("arguments").put("topic", "posture-defaults");
            default -> throw new IllegalArgumentException(operation);
        }
        return AuditRequests.send(http, server.getMcpBaseUrl(), namespace, body, correlation);
    }

    private static void assertActionPolicyDenied(JsonNode response, String reasonCode) {
        assertThat(response.path("error").path("code").asInt())
                .as("Action-policy denial: %s", response)
                .isEqualTo(-32003);
        assertThat(response.path("error").path("data"))
                .isEqualTo(MAPPER.createObjectNode().put("reason_code", reasonCode));
    }

    private static void assertPosture(
            JsonNode status, String mode, String noMatch, String onFailure, String auditLevel) {
        JsonNode posture = status.path("posture");
        assertThat(Map.of(
                        "mode", posture.path("mode").asText(),
                        "no_match", posture.path("no_match").asText(),
                        "on_failure", posture.path("on_failure").asText(),
                        "audit_level", posture.path("audit_level").asText()))
                .as(
                        "Effective posture of namespace %s",
                        status.path("namespace").asText())
                .isEqualTo(
                        Map.of("mode", mode, "no_match", noMatch, "on_failure", onFailure, "audit_level", auditLevel));
    }
}
