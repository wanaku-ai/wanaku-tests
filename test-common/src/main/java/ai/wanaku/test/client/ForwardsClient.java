package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ForwardsClient {

    private static final Logger LOG = LoggerFactory.getLogger(ForwardsClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public ForwardsClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public void add(String name, String targetUrl) {
        LOG.debug("Adding forward: {} -> {}", name, targetUrl);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("target", targetUrl);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_FORWARDS_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Forward added: {}", name);
            } else if (response.statusCode() == 409) {
                throw new ForwardExistsException("Forward '" + name + "' already exists");
            } else {
                throw new ForwardsClientException(
                        "Failed to add forward: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ForwardsClientException("Failed to add forward", e);
        }
    }

    public List<JsonNode> list() {
        LOG.debug("Listing forwards");

        try {
            HttpRequest request =
                    buildRequest(WanakuTestConstants.ROUTER_FORWARDS_PATH).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List forwards response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                List<JsonNode> forwards = new ArrayList<>();
                if (dataNode.isArray()) {
                    for (JsonNode fwd : dataNode) {
                        forwards.add(fwd);
                    }
                }
                return forwards;
            } else {
                throw new ForwardsClientException("Failed to list forwards: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ForwardsClientException("Failed to list forwards", e);
        }
    }

    public boolean remove(String name) {
        LOG.debug("Removing forward: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_FORWARDS_PATH + "/" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Remove forward response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Forward removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Forward not found: {}", name);
                return false;
            } else {
                throw new ForwardsClientException("Failed to remove forward: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ForwardsClientException("Failed to remove forward", e);
        }
    }

    public void refresh() {
        LOG.debug("Refreshing forwards");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_FORWARDS_PATH + "/refresh")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                LOG.debug("Forwards refreshed");
            } else {
                throw new ForwardsClientException(
                        "Failed to refresh forwards: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ForwardsClientException("Failed to refresh forwards", e);
        }
    }

    public void clearAll() {
        LOG.debug("Clearing all forwards");

        List<JsonNode> forwards = list();
        for (JsonNode fwd : forwards) {
            String name = fwd.has("name") ? fwd.get("name").asText() : null;
            if (name != null) {
                try {
                    remove(name);
                } catch (Exception e) {
                    LOG.warn("Failed to remove forward {}: {}", name, e.getMessage());
                }
            }
        }
        LOG.debug("Cleared {} forwards", forwards.size());
    }

    public boolean exists(String name) {
        return list().stream()
                .anyMatch(f -> f.has("name") && f.get("name").asText().equals(name));
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (accessToken != null && !accessToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public static class ForwardsClientException extends RuntimeException {
        public ForwardsClientException(String message) {
            super(message);
        }

        public ForwardsClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ForwardExistsException extends ForwardsClientException {
        public ForwardExistsException(String message) {
            super(message);
        }
    }
}
