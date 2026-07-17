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

public class PromptsClient {

    private static final Logger LOG = LoggerFactory.getLogger(PromptsClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public PromptsClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public void add(String name, String description, String template) {
        LOG.debug("Adding prompt: {}", name);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", description);
        body.put("template", template);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_PROMPTS_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Prompt added: {}", name);
            } else if (response.statusCode() == 409) {
                throw new PromptExistsException("Prompt '" + name + "' already exists");
            } else {
                throw new PromptsClientException(
                        "Failed to add prompt: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PromptsClientException("Failed to add prompt", e);
        }
    }

    public List<JsonNode> list() {
        LOG.debug("Listing prompts");

        try {
            HttpRequest request =
                    buildRequest(WanakuTestConstants.ROUTER_PROMPTS_PATH).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List prompts response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                List<JsonNode> prompts = new ArrayList<>();
                if (dataNode.isArray()) {
                    for (JsonNode prompt : dataNode) {
                        prompts.add(prompt);
                    }
                }
                return prompts;
            } else {
                throw new PromptsClientException("Failed to list prompts: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PromptsClientException("Failed to list prompts", e);
        }
    }

    public boolean remove(String name) {
        LOG.debug("Removing prompt: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_PROMPTS_PATH + "/" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Remove prompt response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Prompt removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Prompt not found: {}", name);
                return false;
            } else {
                throw new PromptsClientException("Failed to remove prompt: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PromptsClientException("Failed to remove prompt", e);
        }
    }

    public void edit(String name, Map<String, Object> updates) {
        LOG.debug("Editing prompt: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            String json = objectMapper.writeValueAsString(updates);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_PROMPTS_PATH + "/" + encodedName)
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                LOG.debug("Prompt edited: {}", name);
            } else if (response.statusCode() == 404) {
                throw new PromptNotFoundException("Prompt '" + name + "' not found");
            } else {
                throw new PromptsClientException(
                        "Failed to edit prompt: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PromptsClientException("Failed to edit prompt", e);
        }
    }

    public void clearAll() {
        LOG.debug("Clearing all prompts");

        List<JsonNode> prompts = list();
        for (JsonNode prompt : prompts) {
            String name = prompt.has("name") ? prompt.get("name").asText() : null;
            if (name != null) {
                try {
                    remove(name);
                } catch (Exception e) {
                    LOG.warn("Failed to remove prompt {}: {}", name, e.getMessage());
                }
            }
        }
        LOG.debug("Cleared {} prompts", prompts.size());
    }

    public boolean exists(String name) {
        return list().stream()
                .anyMatch(p -> p.has("name") && p.get("name").asText().equals(name));
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

    public static class PromptsClientException extends RuntimeException {
        public PromptsClientException(String message) {
            super(message);
        }

        public PromptsClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PromptExistsException extends PromptsClientException {
        public PromptExistsException(String message) {
            super(message);
        }
    }

    public static class PromptNotFoundException extends PromptsClientException {
        public PromptNotFoundException(String message) {
            super(message);
        }
    }
}
