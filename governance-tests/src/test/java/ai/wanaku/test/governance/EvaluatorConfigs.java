package ai.wanaku.test.governance;

import java.nio.file.Path;
import ai.wanaku.test.WanakuTestConstants;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds {@code PUT /api/v1/evaluators} bodies with one evaluator for the governance tests. */
final class EvaluatorConfigs {

    static final String SAFETY_PROMPT = "Classify the safety of this MCP request.";

    private EvaluatorConfigs() {}

    /**
     * An LLM evaluator on the deterministic stub connection. A {@code null} namespace matches every
     * namespace; {@code resultSchema} may be {@code null}.
     */
    static ObjectNode llm(
            String name, String method, String namespace, String operation, Path processor, ObjectNode resultSchema) {
        ObjectNode root = GovernanceTestBase.MAPPER.createObjectNode();
        ObjectNode evaluator = root.putArray("evaluators").addObject().put("name", name);
        ObjectNode trigger = evaluator.putObject("trigger").put("method", method);
        if (namespace != null) {
            trigger.put("namespace", namespace);
        }
        ObjectNode engine = evaluator
                .putObject("engine")
                .put("type", "llm")
                .put("operation", operation)
                .put("prompt", SAFETY_PROMPT)
                .put("connection", WanakuTestConstants.TEST_LLM_CONNECTION_NAME);
        if (resultSchema != null) {
            engine.set("result_schema", resultSchema);
        }
        evaluator.putObject("processor").put("path", processor.toString());
        return root;
    }

    /** A local evaluator: the passthrough engine makes no network call. */
    static ObjectNode passthrough(String name, String method, String namespace, Path processor) {
        ObjectNode root = GovernanceTestBase.MAPPER.createObjectNode();
        ObjectNode evaluator = root.putArray("evaluators").addObject().put("name", name);
        evaluator.putObject("trigger").put("method", method).put("namespace", namespace);
        evaluator.putObject("engine").put("type", "passthrough");
        evaluator.putObject("processor").put("path", processor.toString());
        return root;
    }
}
