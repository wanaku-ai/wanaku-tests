package ai.wanaku.test.governance;

import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import ai.wanaku.test.client.ActionPolicyClient;
import ai.wanaku.test.client.McpTestClient;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real downstream traffic distinguishes selector mismatches from governance denials. */
@Timeout(120)
class ActionPolicySelectorITCase extends GovernanceTestBase {

    private static final String NAMESPACE = "governed";
    private static final String OTHER_NAMESPACE = "open";
    private static final String DENY_MESSAGE = "selector matrix denied this exact request";
    private static final Map<String, String> LABELS = Map.of("tier", "restricted", "team", "security");

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectorCases")
    void selectorsMatchIndividuallyAndAsConjunction(SelectorCase scenario) throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerLabeledCaptureForward(scenario.namespace(), LABELS);
        if (OTHER_NAMESPACE.equals(scenario.namespace())) {
            // The registry keys tools globally by name, so use nonoverlapping upstream catalogs.
            var restrictedUpstream = startAdditionalCaptureServer();
            registerLabeledCaptureForward(NAMESPACE, LABELS, restrictedUpstream);
            assertToolOutcome(connect(OTHER_NAMESPACE), CAPTURE_TOOL, false);
            connect(NAMESPACE)
                    .when()
                    .toolsCall("capture_isolated_tool")
                    .withAssert(response -> assertThat(response.isError()).isFalse())
                    .send()
                    .thenAssertResults();
            assertThat(captureCounts(restrictedUpstream).toolCalls()).isEqualTo(1);
            activateSelectors((ObjectNode) MAPPER.readTree(scenario.selectors()));
            connect(NAMESPACE)
                    .when()
                    .toolsCall("capture_isolated_tool")
                    .withErrorAssert(error -> {
                        assertThat(error.code()).isEqualTo(-32003);
                        assertThat(error.message()).contains(DENY_MESSAGE);
                    })
                    .send()
                    .thenAssertResults();
            assertThat(captureCounts(restrictedUpstream).toolCalls())
                    .as("Scoped denial must leave the successful baseline count unchanged")
                    .isEqualTo(1);
            assertThat(captureCounts().toolCalls()).isEqualTo(1);
        } else {
            activateSelectors((ObjectNode) MAPPER.readTree(scenario.selectors()));
        }
        assertToolOutcome(connect(scenario.namespace()), CAPTURE_TOOL, scenario.denied());
    }

    static Stream<SelectorCase> selectorCases() {
        return Stream.of(
                selector("namespace match", "{\"namespace\":\"governed\"}", true),
                selector("namespace mismatch", "{\"namespace\":\"absent\"}", false),
                new SelectorCase("namespace isolation", "{\"namespace\":\"governed\"}", OTHER_NAMESPACE, false),
                selector("operation match", "{\"operation\":\"tools/call\"}", true),
                selector("operation mismatch", "{\"operation\":\"prompts/get\"}", false),
                selector("target type match", "{\"target_type\":\"tool\"}", true),
                selector("target type mismatch", "{\"target_type\":\"resource\"}", false),
                selector("name match", "{\"target_name\":{\"matcher\":\"exact\",\"value\":\"capture_tool\"}}", true),
                selector("name mismatch", "{\"target_name\":{\"matcher\":\"exact\",\"value\":\"other_tool\"}}", false),
                selector("label match", "{\"labels\":{\"tier\":\"restricted\"}}", true),
                selector("label value mismatch", "{\"labels\":{\"tier\":\"public\"}}", false),
                selector("missing label", "{\"labels\":{\"missing\":\"restricted\"}}", false),
                selector(
                        "label pairs both match", "{\"labels\":{\"tier\":\"restricted\",\"team\":\"security\"}}", true),
                selector(
                        "label pairs require both", "{\"labels\":{\"tier\":\"restricted\",\"team\":\"other\"}}", false),
                selector(
                        "all selectors match",
                        combinedSelectors("governed", "tools/call", "tool", "capture_tool", "restricted"),
                        true),
                selector(
                        "combined namespace mismatch",
                        combinedSelectors("open", "tools/call", "tool", "capture_tool", "restricted"),
                        false),
                selector(
                        "combined operation mismatch",
                        combinedSelectors("governed", "resources/read", "tool", "capture_tool", "restricted"),
                        false),
                selector(
                        "combined type mismatch",
                        combinedSelectors("governed", "tools/call", "prompt", "capture_tool", "restricted"),
                        false),
                selector(
                        "combined name mismatch",
                        combinedSelectors("governed", "tools/call", "tool", "other_tool", "restricted"),
                        false),
                selector(
                        "combined label mismatch",
                        combinedSelectors("governed", "tools/call", "tool", "capture_tool", "public"),
                        false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nameCases")
    void exactAndGlobNamesUseLiteralAndWildcardSemantics(MatcherCase scenario) throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        ObjectNode selectors = MAPPER.createObjectNode().put("operation", "tools/call");
        selectors.putObject("target_name").put("matcher", scenario.matcher()).put("value", scenario.pattern());
        activateSelectors(selectors);
        assertToolOutcome(connect(NAMESPACE), scenario.candidate(), scenario.denied());
    }

    static Stream<MatcherCase> nameCases() {
        return Stream.of(
                new MatcherCase("exact match", "exact", "capture_tool", "capture_tool", true),
                new MatcherCase("exact is case sensitive", "exact", "Capture_tool", "capture_tool", false),
                new MatcherCase("exact star is literal", "exact", "capture_*", "capture_tool", false),
                new MatcherCase("glob star sequence", "glob", "capture_*", "capture_tool", true),
                new MatcherCase("glob star empty sequence", "glob", "capture_*tool", "capture_tool", true),
                new MatcherCase("glob one character", "glob", "capture_?ool", "capture_tool", true),
                new MatcherCase("glob requires one character", "glob", "capture_??ool", "capture_tool", false),
                new MatcherCase("glob anchored", "glob", "capture", "capture_tool", false),
                new MatcherCase(
                        "escaped star matches literal", "glob", "capture_star\\*tool", "capture_star*tool", true),
                new MatcherCase("escaped star is not wildcard", "glob", "capture_\\*", "capture_tool", false),
                new MatcherCase(
                        "escaped question matches literal",
                        "glob",
                        "capture_question\\?tool",
                        "capture_question?tool",
                        true),
                new MatcherCase("escaped question is not wildcard", "glob", "capture_\\?ool", "capture_tool", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uriCases")
    void resourceUrisAreComparedWithoutNormalization(MatcherCase scenario) throws Exception {
        startGovernedServer(GovernancePolicies.policy(), GovernancePolicies.enforce("allow"));
        registerCaptureForward(NAMESPACE);
        ObjectNode selectors = MAPPER.createObjectNode().put("operation", "resources/read");
        selectors.put("target_type", "resource");
        selectors.putObject("uri").put("matcher", scenario.matcher()).put("value", scenario.pattern());
        activateSelectors(selectors);
        var request = connect(NAMESPACE).when().resourcesRead(scenario.candidate());
        if (scenario.denied()) {
            request.withErrorAssert(error -> {
                assertThat(error.code()).isEqualTo(-32003);
                assertThat(error.message()).contains(DENY_MESSAGE);
            });
        } else {
            request.withAssert(response -> assertThat(response.contents()).isNotEmpty());
        }
        request.send().thenAssertResults();
        assertThat(captureCounts().resourceReads()).isEqualTo(scenario.denied() ? 0 : 1);
        assertThat(captureCounts().toolCalls()).isZero();
    }

    static Stream<MatcherCase> uriCases() {
        return Stream.of(
                new MatcherCase("URI exact match", "exact", CAPTURE_RESOURCE_URI, CAPTURE_RESOURCE_URI, true),
                new MatcherCase(
                        "URI exact requires full string",
                        "exact",
                        "capture://resource/dat",
                        CAPTURE_RESOURCE_URI,
                        false),
                new MatcherCase("URI literal prefix", "prefix", "capture://resource/dat", CAPTURE_RESOURCE_URI, true),
                new MatcherCase(
                        "URI prefix has no path boundary",
                        "prefix",
                        CAPTURE_RESOURCE_URI,
                        "capture://resource/database",
                        true),
                new MatcherCase("URI prefix mismatch", "prefix", "capture://other/", CAPTURE_RESOURCE_URI, false),
                new MatcherCase(
                        "URI prefix star is literal", "prefix", "capture://resource/*", CAPTURE_RESOURCE_URI, false),
                new MatcherCase(
                        "URI encoded exact", "exact", "capture://resource/%64ata", "capture://resource/%64ata", true),
                new MatcherCase(
                        "URI encoding not decoded", "exact", CAPTURE_RESOURCE_URI, "capture://resource/%64ata", false),
                new MatcherCase(
                        "URI encoding not introduced",
                        "exact",
                        "capture://resource/%64ata",
                        CAPTURE_RESOURCE_URI,
                        false),
                new MatcherCase(
                        "URI prefix not decoded",
                        "prefix",
                        "capture://resource/da",
                        "capture://resource/%64ata",
                        false),
                new MatcherCase(
                        "URI scheme remains case sensitive",
                        "exact",
                        "CAPTURE://resource/data",
                        CAPTURE_RESOURCE_URI,
                        false));
    }

    private void activateSelectors(ObjectNode selectors) throws Exception {
        ObjectNode rule = GovernancePolicies.denyTool("selector-matrix", CAPTURE_TOOL, DENY_MESSAGE);
        rule.set("selectors", selectors);
        ObjectNode policy = (ObjectNode) MAPPER.readTree(GovernancePolicies.policy(rule));
        try (ActionPolicyClient client = new ActionPolicyClient(server.getBaseUrl())) {
            var response = client.updatePolicy(policy);
            assertThat(response.statusCode())
                    .as("Policy accepted and activated: %s", response.body())
                    .isEqualTo(200);
        }
    }

    private void assertToolOutcome(McpTestClient client, String tool, boolean denied) throws Exception {
        int previousCalls = captureCounts().toolCalls();
        var request = client.when().toolsCall(tool);
        if (CAPTURE_TOOL.equals(tool)) {
            request.withArguments(Map.of("payload", "selector-real-downstream"));
        }
        if (denied) {
            request.withErrorAssert(error -> {
                assertThat(error.code()).isEqualTo(-32003);
                assertThat(error.message()).contains(DENY_MESSAGE);
            });
        } else {
            request.withAssert(response -> assertThat(response.isError()).isFalse());
        }
        request.send().thenAssertResults();
        assertThat(captureCounts().toolCalls())
                .as("Only allowed requests reach the real upstream")
                .isEqualTo(previousCalls + (denied ? 0 : 1));
        assertThat(captureCounts().resourceReads()).isZero();
    }

    private static SelectorCase selector(String description, String selectors, boolean denied) {
        return new SelectorCase(description, selectors, NAMESPACE, denied);
    }

    private static String combinedSelectors(String namespace, String operation, String type, String name, String tier) {
        ObjectNode selectors = (ObjectNode)
                GovernancePolicies.denyTool("unused", name, DENY_MESSAGE).get("selectors");
        selectors.put("namespace", namespace).put("operation", operation).put("target_type", type);
        selectors.putObject("labels").put("tier", tier).put("team", "security");
        return selectors.toString();
    }

    record SelectorCase(String description, String selectors, String namespace, boolean denied) {
        @Override
        public @NonNull String toString() {
            return description;
        }
    }

    record MatcherCase(String description, String matcher, String pattern, String candidate, boolean denied) {
        @Override
        public @NonNull String toString() {
            return description;
        }
    }
}
