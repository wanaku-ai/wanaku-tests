package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** REST client for evaluator management and revision operations. */
public class EvaluatorClient {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluatorClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public EvaluatorClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    /** Lists all evaluators. */
    public JsonNode listEvaluators() {
        LOG.debug("Listing evaluators");

        HttpRequest request =
                buildRequest(WanakuTestConstants.EVALUATORS_PATH).GET().build();

        return executeAndParse(request, "list evaluators");
    }

    /** Updates evaluators via PUT with a raw JSON body. */
    public EvaluatorResponse updateEvaluators(String jsonBody) {
        LOG.debug("Updating evaluators");

        HttpRequest request = buildRequest(WanakuTestConstants.EVALUATORS_PATH)
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .build();

        return executeRaw(request, "update evaluators");
    }

    /** Lists all evaluator configuration revisions. */
    public JsonNode listRevisions() {
        LOG.debug("Listing evaluator revisions");

        HttpRequest request =
                buildRequest(WanakuTestConstants.EVALUATOR_REVISIONS_PATH).GET().build();

        return executeAndParse(request, "list revisions");
    }

    /** Gets the currently active evaluator revision. */
    public EvaluatorResponse getActiveRevision() {
        LOG.debug("Getting active evaluator revision");

        HttpRequest request = buildRequest(WanakuTestConstants.EVALUATOR_REVISIONS_PATH + "/active")
                .GET()
                .build();

        return executeRaw(request, "get active revision");
    }

    /** Gets a specific revision by its ID. */
    public EvaluatorResponse getRevision(long revisionId) {
        LOG.debug("Getting evaluator revision: {}", revisionId);

        HttpRequest request = buildRequest(WanakuTestConstants.EVALUATOR_REVISIONS_PATH + "/" + revisionId)
                .GET()
                .build();

        return executeRaw(request, "get revision " + revisionId);
    }

    /** Activates (rolls back to) a specific revision. */
    public EvaluatorResponse activateRevision(long revisionId, String jsonBody) {
        LOG.debug("Activating evaluator revision: {}", revisionId);

        HttpRequest request = buildRequest(
                        WanakuTestConstants.EVALUATOR_REVISIONS_PATH + "/" + revisionId + "/activate")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .build();

        return executeRaw(request, "activate revision " + revisionId);
    }

    private JsonNode executeAndParse(HttpRequest request, String operationName) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("{} response: {} - {}", operationName, response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.has("data") ? root.get("data") : root;
            } else {
                throw new EvaluatorClientException(
                        "Failed to " + operationName + ": " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EvaluatorClientException("Failed to " + operationName, e);
        }
    }

    private EvaluatorResponse executeRaw(HttpRequest request, String operationName) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("{} response: {} - {}", operationName, response.statusCode(), response.body());

            JsonNode body = null;
            if (response.body() != null && !response.body().isBlank()) {
                body = objectMapper.readTree(response.body());
            }
            return new EvaluatorResponse(response.statusCode(), body);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EvaluatorClientException("Failed to " + operationName, e);
        }
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (accessToken != null && !accessToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder;
    }

    /**
     * Wraps an HTTP response with status code and parsed body.
     */
    public record EvaluatorResponse(int statusCode, JsonNode body) {}

    public static class EvaluatorClientException extends RuntimeException {
        public EvaluatorClientException(String message) {
            super(message);
        }

        public EvaluatorClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
