package ai.wanaku.test.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the {@code action_policy:} and {@code governance:} JSON blocks written into the Wanaku
 * bootstrap config. JSON is a valid YAML flow mapping, so these strings can be embedded directly
 * under the corresponding YAML keys (see {@code WanakuServerManager.generateWanakuConfig}).
 *
 * <p>Every deny rule carries a distinctive {@code message} and {@code reason_code} so tests can
 * assert on the JSON-RPC error the router returns (code -32003 for a static deny).
 */
final class GovernancePolicies {

    static final String DENY_REASON_CODE = "policy_error";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GovernancePolicies() {}

    /** Enforcing posture; {@code noMatch} decides what happens to traffic no rule matches. */
    static String enforce(String noMatch) {
        ObjectNode posture = MAPPER.createObjectNode();
        posture.put("mode", "enforce");
        posture.put("no_match", noMatch);
        posture.put("on_failure", "deny");
        posture.put("audit_level", "basic");
        ObjectNode root = MAPPER.createObjectNode();
        root.set("default", posture);
        return root.toString();
    }

    /** Global posture with every setting explicit; {@code disabledReason} may be {@code null}. */
    static String posture(String mode, String noMatch, String onFailure, String auditLevel, String disabledReason) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("default", postureNode(mode, noMatch, onFailure, auditLevel, disabledReason));
        return root.toString();
    }

    static ObjectNode postureNode(
            String mode, String noMatch, String onFailure, String auditLevel, String disabledReason) {
        ObjectNode posture = MAPPER.createObjectNode();
        posture.put("mode", mode);
        posture.put("no_match", noMatch);
        posture.put("on_failure", onFailure);
        posture.put("audit_level", auditLevel);
        if (disabledReason != null) {
            posture.put("disabled_reason", disabledReason);
        }
        return posture;
    }

    static String policy(ObjectNode... rules) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("rules");
        for (ObjectNode rule : rules) {
            array.add(rule);
        }
        return root.toString();
    }

    static ObjectNode denyTool(String id, String toolName, String message) {
        ObjectNode rule = baseRule(id, message);
        ObjectNode selectors = rule.putObject("selectors");
        selectors.put("operation", "tools/call");
        selectors.put("target_type", "tool");
        selectors.putObject("target_name").put("matcher", "exact").put("value", toolName);
        return rule;
    }

    static ObjectNode allowTool(String id, String toolName) {
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("id", id);
        rule.put("effect", "allow");
        ObjectNode selectors = rule.putObject("selectors");
        selectors.put("operation", "tools/call");
        selectors.put("target_type", "tool");
        selectors.putObject("target_name").put("matcher", "exact").put("value", toolName);
        return rule;
    }

    static ObjectNode denyToolInNamespace(String id, String toolName, String namespace, String message) {
        ObjectNode rule = denyTool(id, toolName, message);
        ((ObjectNode) rule.get("selectors")).put("namespace", namespace);
        return rule;
    }

    static ObjectNode denyToolWithLabel(String id, String labelKey, String labelValue, String message) {
        ObjectNode rule = baseRule(id, message);
        ObjectNode selectors = rule.putObject("selectors");
        selectors.put("operation", "tools/call");
        selectors.put("target_type", "tool");
        selectors.putObject("labels").put(labelKey, labelValue);
        return rule;
    }

    static ObjectNode denyResourceByUri(String id, String uri, String message) {
        ObjectNode rule = baseRule(id, message);
        ObjectNode selectors = rule.putObject("selectors");
        selectors.put("operation", "resources/read");
        selectors.put("target_type", "resource");
        selectors.putObject("uri").put("matcher", "exact").put("value", uri);
        return rule;
    }

    static ObjectNode denyPrompt(String id, String promptName, String message) {
        ObjectNode rule = baseRule(id, message);
        ObjectNode selectors = rule.putObject("selectors");
        selectors.put("operation", "prompts/get");
        selectors.put("target_type", "prompt");
        selectors.putObject("target_name").put("matcher", "exact").put("value", promptName);
        return rule;
    }

    private static ObjectNode baseRule(String id, String message) {
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("id", id);
        rule.put("effect", "deny");
        rule.put("reason_code", DENY_REASON_CODE);
        rule.put("message", message);
        return rule;
    }
}
