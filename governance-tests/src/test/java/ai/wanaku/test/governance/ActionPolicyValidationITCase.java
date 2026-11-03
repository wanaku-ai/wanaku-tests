package ai.wanaku.test.governance;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.McpTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Invalid management updates must leave both the active document and compiled behavior intact. */
class ActionPolicyValidationITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "validation";
    private static final String MESSAGE = "baseline policy remains active";

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPolicies")
    void rejectsInvalidPolicyWithoutReplacingActiveState(InvalidPolicy scenario) throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        McpTestClient mcp = connect(NAMESPACE);

        try (ActionPolicyClient client = new ActionPolicyClient(server.getBaseUrl())) {
            var accepted = client.updatePolicy(baselinePolicy());
            assertThat(accepted.statusCode())
                    .as("Baseline activation: %s", accepted.body())
                    .isEqualTo(200);
            JsonNode active = client.getActiveRevision().body();
            assertBaselineBehavior(mcp);

            ObjectNode invalid = baselinePolicy();
            scenario.change().accept(invalid);
            var rejected = client.updatePolicy(invalid);
            assertThat(rejected.statusCode())
                    .as("Rejected %s: %s", scenario.name(), rejected.body())
                    .isEqualTo(scenario.status());
            assertThat(rejected.body().toString()).contains(scenario.error());

            var retained = client.getActiveRevision();
            assertThat(retained.statusCode()).isEqualTo(200);
            assertThat(retained.body().path("revision").path("id"))
                    .isEqualTo(active.path("revision").path("id"));
            assertThat(retained.body().path("policy")).isEqualTo(active.path("policy"));
            var effective = client.getPolicy();
            assertThat(effective.statusCode()).isEqualTo(200);
            assertThat(effective.body().path("policy")).isEqualTo(active.path("policy"));
            assertBaselineBehavior(mcp);
        }
    }

    @Test
    void acceptedPolicyPreservesStableIdsAndMetadataOnRetrieval() throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        ObjectNode policy = baselinePolicy();
        ObjectNode rule = rule(policy);
        rule.put("description", "typed metadata round trip");
        rule.putObject("metadata")
                .put("owner", "test-suite")
                .put("enabled", false)
                .put("count", 0)
                .putNull("optional")
                .putObject("nested")
                .putArray("values")
                .add("zero")
                .add(0);
        try (ActionPolicyClient client = new ActionPolicyClient(server.getBaseUrl())) {
            var accepted = client.updatePolicy(policy);
            assertThat(accepted.statusCode())
                    .as("Activation: %s", accepted.body())
                    .isEqualTo(200);
            for (var response : new ActionPolicyClient.Response[] {client.getPolicy(), client.getActiveRevision()}) {
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode retrieved =
                        response.body().path("policy").path("rules").get(0);
                assertThat(retrieved.path("id")).isEqualTo(rule.path("id"));
                assertThat(retrieved.path("description")).isEqualTo(rule.path("description"));
                assertThat(retrieved.path("metadata")).isEqualTo(rule.path("metadata"));
                rule.path("selectors").properties().forEach(entry -> assertThat(
                                retrieved.path("selectors").path(entry.getKey()))
                        .isEqualTo(entry.getValue()));
                assertThat(retrieved.path("effect")).isEqualTo(rule.path("effect"));
                assertThat(retrieved.path("predicates")).isEqualTo(rule.path("predicates"));
                assertThat(retrieved.path("reason_code")).isEqualTo(rule.path("reason_code"));
                assertThat(retrieved.path("message")).isEqualTo(rule.path("message"));
            }
            assertBaselineBehavior(connect(NAMESPACE));
        }
    }

    private void assertBaselineBehavior(McpTestClient client) throws Exception {
        int before = captureCounts().toolCalls();
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "allowed"))
                .withAssert(result -> assertThat(result.isError()).isFalse())
                .send()
                .thenAssertResults();
        assertThat(captureCounts().toolCalls()).isEqualTo(before + 1);
        client.when()
                .toolsCall(CAPTURE_TOOL)
                .withArguments(Map.of("payload", "blocked"))
                .withErrorAssert(error -> {
                    assertThat(error.code()).isEqualTo(-32003);
                    assertThat(error.message()).contains(MESSAGE);
                })
                .send()
                .thenAssertResults();
        assertThat(captureCounts().toolCalls()).isEqualTo(before + 1);
    }

    private static ObjectNode baselinePolicy() throws Exception {
        ObjectNode rule = GovernancePolicies.denyTool("stable-rule", CAPTURE_TOOL, MESSAGE);
        rule.putArray("predicates")
                .addObject()
                .put("operator", "equals")
                .put("pointer", "/arguments/payload")
                .put("value", "blocked");
        return (ObjectNode) MAPPER.readTree(GovernancePolicies.policy(rule));
    }

    private static Stream<InvalidPolicy> invalidPolicies() {
        return Stream.of(
                semantic("duplicate IDs", "stable-rule", p -> p.withArray("rules")
                        .add(rule(p).deepCopy())),
                semantic("invalid ID", "bad id", p -> rule(p).put("id", "bad id")),
                semantic("empty ID", "not a valid identifier", p -> rule(p).put("id", "")),
                semantic("missing selectors", "stable-rule", p -> rule(p).remove("selectors")),
                semantic("empty selectors", "stable-rule", p -> rule(p).putObject("selectors")),
                semantic("invalid namespace", "stable-rule", p -> selectors(p).put("namespace", "bad space")),
                semantic("invalid operation", "stable-rule", p -> selectors(p).put("operation", "bad space")),
                semantic("invalid label key", "stable-rule", p -> selectors(p)
                        .putObject("labels")
                        .put("bad key", "v")),
                semantic("invalid metadata key", "stable-rule", p -> rule(p).putObject("metadata")
                        .put("bad key", 1)),
                semantic("invalid reason code", "stable-rule", p -> rule(p).put("reason_code", "bad code")),
                malformed("unknown policy field", "unknown field", p -> p.put("unknown", true)),
                malformed("unknown rule field", "unknown field", p -> rule(p).put("unknown", true)),
                malformed("unknown selector field", "unknown field", p -> selectors(p)
                        .put("unknown", true)),
                malformed("unknown matcher field", "unknown field", p -> matcher(p)
                        .put("unknown", true)),
                malformed("unknown predicate field", "unknown field", p -> predicate(p)
                        .put("unknown", true)),
                malformed(
                        "unknown operator", "unknown variant", p -> predicate(p).put("operator", "regex")),
                malformed("unknown matcher", "unknown variant", p -> matcher(p).put("matcher", "regex")),
                malformed("unknown target type", "unknown variant", p -> selectors(p)
                        .put("target_type", "actor")),
                malformed("unknown effect", "unknown variant", p -> rule(p).put("effect", "audit")),
                malformed("exists nonboolean operand", "boolean", p -> predicate(p)
                        .put("operator", "exists")
                        .put("value", 0)),
                malformed("membership scalar operand", "sequence", p -> {
                    predicate(p).remove("value");
                    predicate(p).put("operator", "one_of").put("values", "blocked");
                }),
                malformed("missing equals operand", "value", p -> predicate(p).remove("value")),
                semantic("empty one_of", "stable-rule", p -> {
                    predicate(p).remove("value");
                    predicate(p).put("operator", "one_of").putArray("values");
                }),
                semantic("empty not_one_of", "stable-rule", p -> {
                    predicate(p).remove("value");
                    predicate(p).put("operator", "not_one_of").putArray("values");
                }),
                semantic("URI glob", "stable-rule", p -> selectors(p)
                        .putObject("uri")
                        .put("matcher", "glob")
                        .put("value", "capture:/*")),
                semantic("target-name prefix", "stable-rule", p -> matcher(p).put("matcher", "prefix")),
                semantic("trailing glob escape", "stable-rule", p -> matcher(p)
                        .put("matcher", "glob")
                        .put("value", "capture\\")),
                semantic("empty target matcher", "stable-rule", p -> matcher(p).put("value", "")),
                semantic("empty URI matcher", "stable-rule", p -> selectors(p)
                        .putObject("uri")
                        .put("matcher", "exact")
                        .put("value", "")),
                semantic("pointer without slash", "stable-rule", p -> predicate(p)
                        .put("pointer", "arguments/payload")),
                semantic("invalid pointer escape", "stable-rule", p -> predicate(p)
                        .put("pointer", "/arguments/~2")),
                semantic("trailing pointer escape", "stable-rule", p -> predicate(p)
                        .put("pointer", "/arguments/~")));
    }

    private static InvalidPolicy semantic(String name, String error, Consumer<ObjectNode> change) {
        return new InvalidPolicy(name, 422, error, change);
    }

    private static InvalidPolicy malformed(String name, String error, Consumer<ObjectNode> change) {
        return new InvalidPolicy(name, 400, error, change);
    }

    private static ObjectNode rule(ObjectNode policy) {
        return (ObjectNode) policy.path("rules").get(0);
    }

    private static ObjectNode selectors(ObjectNode policy) {
        return (ObjectNode) rule(policy).get("selectors");
    }

    private static ObjectNode matcher(ObjectNode policy) {
        return (ObjectNode) selectors(policy).get("target_name");
    }

    private static ObjectNode predicate(ObjectNode policy) {
        return (ObjectNode) rule(policy).path("predicates").get(0);
    }

    private record InvalidPolicy(String name, int status, String error, Consumer<ObjectNode> change) {
        @Override
        public @NonNull String toString() {
            return name;
        }
    }
}
