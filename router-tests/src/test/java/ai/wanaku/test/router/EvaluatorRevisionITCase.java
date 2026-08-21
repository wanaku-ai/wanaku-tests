package ai.wanaku.test.router;

import ai.wanaku.test.client.EvaluatorClient;
import ai.wanaku.test.client.EvaluatorClient.EvaluatorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Integration tests for the evaluator versioned configuration API
 * introduced in wanaku-ai/wanaku#1879.
 *
 * <p>Covers:
 * <ul>
 *   <li>Revision creation on evaluator update (PUT /api/v1/evaluators)</li>
 *   <li>Listing revisions (GET /api/v1/evaluators/revisions)</li>
 *   <li>Getting the active revision (GET /api/v1/evaluators/revisions/active)</li>
 *   <li>Getting a specific revision by ID (GET /api/v1/evaluators/revisions/{id})</li>
 *   <li>Rollback via revision activation (POST /api/v1/evaluators/revisions/{id}/activate)</li>
 *   <li>Optimistic concurrency control with expected_revision</li>
 *   <li>Legacy update format compatibility</li>
 * </ul>
 */
class EvaluatorRevisionITCase extends RouterTestBase {

    private final ObjectMapper mapper = new ObjectMapper();

    private EvaluatorClient evaluatorClient;

    @BeforeEach
    void setupEvaluatorClient() {
        assumeThat(isServerRunning()).as("Server must be running").isTrue();

        evaluatorClient = new EvaluatorClient(getServerBaseUrl(), null);
    }

    @AfterEach
    void clearEvaluators() {
        if (evaluatorClient != null) {
            try {
                evaluatorClient.updateEvaluators("{\"evaluators\": []}");
            } catch (Exception e) {
                // Ignore cleanup failures
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Revision lifecycle: update creates revision, list, get active
    // ──────────────────────────────────────────────────────────────

    @DisplayName("Updating evaluators creates a revision and returns revision metadata")
    @Test
    void shouldCreateRevisionOnUpdate() {
        EvaluatorResponse response = evaluatorClient.updateEvaluators(newFormatPayload("rev-test-eval"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotNull();
        assertThat(response.body().has("revision")).isTrue();

        JsonNode revision = response.body().get("revision");
        assertThat(revision.has("id")).isTrue();
        assertThat(revision.get("id").isNumber()).isTrue();
        assertThat(revision.has("status")).isTrue();
        assertThat(revision.get("status").asText()).isEqualTo("active");
        assertThat(revision.has("checksum")).isTrue();
        assertThat(revision.get("checksum").asText()).isNotBlank();
        assertThat(revision.has("origin")).isTrue();
        assertThat(revision.get("origin").asText()).isEqualTo("api");
    }

    @DisplayName("List revisions returns all recorded revisions newest first")
    @Test
    void shouldListRevisionsNewestFirst() {
        evaluatorClient.updateEvaluators(newFormatPayload("eval-a"));
        evaluatorClient.updateEvaluators(newFormatPayload("eval-b"));

        JsonNode revisions = evaluatorClient.listRevisions();

        assertThat(revisions).isNotNull();
        assertThat(revisions.isArray()).isTrue();
        // At least the two we just created, plus possibly a startup revision
        assertThat(revisions.size()).isGreaterThanOrEqualTo(2);

        // Verify newest-first ordering: first element ID > second element ID
        long firstId = revisions.get(0).get("id").asLong();
        long secondId = revisions.get(1).get("id").asLong();
        assertThat(firstId).isGreaterThan(secondId);
    }

    @DisplayName("Get active revision returns the most recently activated revision")
    @Test
    void shouldReturnActiveRevision() {
        EvaluatorResponse updateResponse = evaluatorClient.updateEvaluators(newFormatPayload("active-test"));

        long expectedId = updateResponse.body().get("revision").get("id").asLong();

        EvaluatorResponse activeResponse = evaluatorClient.getActiveRevision();

        assertThat(activeResponse.statusCode()).isEqualTo(200);
        assertThat(activeResponse.body()).isNotNull();
        assertThat(activeResponse.body().has("revision")).isTrue();
        assertThat(activeResponse.body().get("revision").get("id").asLong()).isEqualTo(expectedId);
        assertThat(activeResponse.body().has("evaluators")).isTrue();
    }

    @DisplayName("Get a specific revision by its ID")
    @Test
    void shouldGetRevisionById() {
        EvaluatorResponse updateResponse = evaluatorClient.updateEvaluators(newFormatPayload("get-by-id"));
        long revisionId = updateResponse.body().get("revision").get("id").asLong();

        EvaluatorResponse getResponse = evaluatorClient.getRevision(revisionId);

        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(getResponse.body()).isNotNull();
        assertThat(getResponse.body().has("revision")).isTrue();
        assertThat(getResponse.body().get("revision").get("id").asLong()).isEqualTo(revisionId);
        assertThat(getResponse.body().has("evaluators")).isTrue();
    }

    @DisplayName("Get a nonexistent revision returns 404")
    @Test
    void shouldReturn404ForNonexistentRevision() {
        EvaluatorResponse response = evaluatorClient.getRevision(999999);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ──────────────────────────────────────────────────────────────
    // Rollback via revision activation
    // ──────────────────────────────────────────────────────────────

    @DisplayName("Activating a previous revision rolls back the evaluator config")
    @Test
    void shouldRollbackToPreviousRevision() {
        // Create first revision with eval-alpha
        EvaluatorResponse first = evaluatorClient.updateEvaluators(newFormatPayload("eval-alpha"));
        long firstRevisionId = first.body().get("revision").get("id").asLong();

        // Create second revision with eval-beta (this becomes active)
        evaluatorClient.updateEvaluators(newFormatPayload("eval-beta"));

        // Rollback to the first revision
        EvaluatorResponse rollback = evaluatorClient.activateRevision(firstRevisionId, "");

        assertThat(rollback.statusCode()).isEqualTo(200);
        assertThat(rollback.body()).isNotNull();
        assertThat(rollback.body().has("revision")).isTrue();
        // The rollback creates a NEW revision (different ID from the original)
        long rollbackRevisionId = rollback.body().get("revision").get("id").asLong();
        assertThat(rollbackRevisionId).isNotEqualTo(firstRevisionId);
        assertThat(rollback.body().get("revision").get("status").asText()).isEqualTo("active");

        // The evaluator config should match the first revision's config
        assertThat(rollback.body().has("evaluators")).isTrue();
        JsonNode evaluators = rollback.body().get("evaluators");
        assertThat(evaluators.isArray()).isTrue();
        assertThat(evaluators.size()).isEqualTo(1);
        assertThat(evaluators.get(0).get("name").asText()).isEqualTo("eval-alpha");
    }

    @DisplayName("Activating a nonexistent revision returns 404")
    @Test
    void shouldReturn404WhenActivatingNonexistentRevision() {
        EvaluatorResponse response = evaluatorClient.activateRevision(999999, "");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ──────────────────────────────────────────────────────────────
    // Optimistic concurrency control
    // ──────────────────────────────────────────────────────────────

    @DisplayName("Update with correct expected_revision succeeds")
    @Test
    void shouldSucceedWithCorrectExpectedRevision() {
        // Create initial revision
        EvaluatorResponse first = evaluatorClient.updateEvaluators(newFormatPayload("occ-first"));
        long firstRevisionId = first.body().get("revision").get("id").asLong();

        // Update with correct expected_revision
        String payload = newFormatPayloadWithExpectedRevision("occ-second", firstRevisionId);
        EvaluatorResponse second = evaluatorClient.updateEvaluators(payload);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body().get("revision").get("id").asLong()).isGreaterThan(firstRevisionId);
    }

    @DisplayName("Update with stale expected_revision returns 409 Conflict")
    @Test
    void shouldReturn409WithStaleExpectedRevision() {
        // Create initial revision
        EvaluatorResponse first = evaluatorClient.updateEvaluators(newFormatPayload("conflict-first"));
        long firstRevisionId = first.body().get("revision").get("id").asLong();

        // Create a second revision (the first is now stale)
        evaluatorClient.updateEvaluators(newFormatPayload("conflict-second"));

        // Attempt to update with the stale first revision ID
        String payload = newFormatPayloadWithExpectedRevision("conflict-third", firstRevisionId);
        EvaluatorResponse staleUpdate = evaluatorClient.updateEvaluators(payload);

        assertThat(staleUpdate.statusCode()).isEqualTo(409);
    }

    // ──────────────────────────────────────────────────────────────
    // Legacy format compatibility
    // ──────────────────────────────────────────────────────────────

    @DisplayName("Legacy evaluator update format (plain EvaluatorsConfig) still works")
    @Test
    void shouldAcceptLegacyFormat() {
        String legacyPayload = legacyFormatPayload("legacy-eval");
        EvaluatorResponse response = evaluatorClient.updateEvaluators(legacyPayload);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotNull();
        // The response should include revision metadata even for legacy format
        assertThat(response.body().has("revision")).isTrue();
        assertThat(response.body().get("revision").get("status").asText()).isEqualTo("active");
    }

    // ──────────────────────────────────────────────────────────────
    // Superseded revision tracking
    // ──────────────────────────────────────────────────────────────

    @DisplayName("Previous active revision is marked as superseded after a new update")
    @Test
    void shouldMarkPreviousRevisionAsSuperseded() {
        EvaluatorResponse first = evaluatorClient.updateEvaluators(newFormatPayload("sup-first"));
        long firstRevisionId = first.body().get("revision").get("id").asLong();

        evaluatorClient.updateEvaluators(newFormatPayload("sup-second"));

        // Retrieve the first revision - it should be superseded
        EvaluatorResponse getFirst = evaluatorClient.getRevision(firstRevisionId);
        assertThat(getFirst.statusCode()).isEqualTo(200);
        assertThat(getFirst.body().get("revision").get("status").asText()).isEqualTo("superseded");
    }

    // ──────────────────────────────────────────────────────────────
    // Revision metadata completeness
    // ──────────────────────────────────────────────────────────────

    @DisplayName("Revision metadata includes all expected fields")
    @Test
    void shouldIncludeCompleteRevisionMetadata() {
        EvaluatorResponse response = evaluatorClient.updateEvaluators(newFormatPayload("meta-test"));

        JsonNode revision = response.body().get("revision");

        assertThat(revision.has("id")).isTrue();
        assertThat(revision.has("created_at")).isTrue();
        assertThat(revision.get("created_at").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(revision.has("activated_at")).isTrue();
        assertThat(revision.has("status")).isTrue();
        assertThat(revision.has("checksum")).isTrue();
        assertThat(revision.has("origin")).isTrue();
    }

    @DisplayName("Different evaluator configs produce different checksums")
    @Test
    void shouldProduceDifferentChecksumsForDifferentConfigs() {
        EvaluatorResponse first = evaluatorClient.updateEvaluators(newFormatPayload("checksum-a"));
        EvaluatorResponse second = evaluatorClient.updateEvaluators(newFormatPayload("checksum-b"));

        String checksumA = first.body().get("revision").get("checksum").asText();
        String checksumB = second.body().get("revision").get("checksum").asText();

        assertThat(checksumA).isNotEqualTo(checksumB);
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Creates a new-format UpdateEvaluatorsRequest payload with a single evaluator.
     */
    private String newFormatPayload(String evaluatorName) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode evaluators = root.putArray("evaluators");
        evaluators.add(createEvaluatorNode(evaluatorName));
        return root.toString();
    }

    /**
     * Creates a new-format UpdateEvaluatorsRequest payload with expected_revision.
     */
    private String newFormatPayloadWithExpectedRevision(String evaluatorName, long expectedRevision) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode evaluators = root.putArray("evaluators");
        evaluators.add(createEvaluatorNode(evaluatorName));
        root.put("expected_revision", expectedRevision);
        return root.toString();
    }

    /**
     * Creates a legacy EvaluatorsConfig payload (no expected_revision wrapper).
     */
    private String legacyFormatPayload(String evaluatorName) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode evaluators = root.putArray("evaluators");
        evaluators.add(createEvaluatorNode(evaluatorName));
        return root.toString();
    }

    /**
     * Creates a single evaluator definition node with minimal valid fields.
     */
    private ObjectNode createEvaluatorNode(String name) {
        ObjectNode evaluator = mapper.createObjectNode();
        evaluator.put("name", name);

        ObjectNode trigger = mapper.createObjectNode();
        trigger.put("method", "tools/call");
        evaluator.set("trigger", trigger);

        ObjectNode llm = mapper.createObjectNode();
        llm.put("operation", "classify");
        llm.put("prompt", "Test prompt for " + name);
        llm.put("model", "test-model");
        llm.put("url", "http://localhost:11434");
        llm.put("api_key", "");
        evaluator.set("llm", llm);

        ObjectNode processor = mapper.createObjectNode();
        processor.put("path", "/actions/dist/safety_classifier.wasm");
        evaluator.set("processor", processor);

        evaluator.put("on_error", "continue");

        return evaluator;
    }
}
