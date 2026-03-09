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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST API client for the Wanaku Router Data Store API.
 *
 * API Endpoints (base path: /api/v1/data-store):
 * - POST /api/v1/data-store/add - Add an entry
 * - GET /api/v1/data-store/list - List all entries
 * - GET /api/v1/data-store/get?name={name} - Get entry by name
 * - DELETE /api/v1/data-store/remove?name={name} - Remove entry by name
 */
public class DataStoreClient {

    private static final Logger LOG = LoggerFactory.getLogger(DataStoreClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    /**
     * Creates a new DataStoreClient with bearer token authentication.
     *
     * @param baseUrl     the Router base URL (e.g., "http://localhost:8080")
     * @param accessToken the bearer token for authorization (Data Store API requires authentication)
     */
    public DataStoreClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Uploads a string content entry to the data store.
     * The content is base64-encoded before sending.
     *
     * @param name the entry name (e.g., "routes.yaml")
     * @param content the string content to upload
     */
    public void upload(String name, String content) {
        upload(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Uploads a byte array content entry to the data store.
     * The content is base64-encoded before sending.
     *
     * @param name the entry name (e.g., "routes.yaml")
     * @param content the byte array content to upload
     */
    public void upload(String name, byte[] content) {
        LOG.debug("Uploading data store entry: {}", name);

        String encoded = Base64.getEncoder().encodeToString(content);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("data", encoded);
        body.put("labels", Map.of());

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_DATA_STORE_PATH + "/add")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Data store entry uploaded: {}", name);
            } else {
                throw new DataStoreClientException(
                        "Failed to upload data store entry: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DataStoreClientException("Failed to upload data store entry", e);
        }
    }

    /**
     * Downloads an entry from the data store by name.
     * The returned data is base64-decoded back to a string.
     *
     * @param name the entry name to download
     * @return the decoded content as a string
     * @throws DataStoreEntryNotFoundException if no entry with the given name is found
     */
    public String download(String name) {
        LOG.debug("Downloading data store entry: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_DATA_STORE_PATH + "/get?name=" + encodedName)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Download response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());

                // Check for error in response
                if (root.has("error") && !root.get("error").isNull()) {
                    String errorMsg = root.get("error").has("message")
                            ? root.get("error").get("message").asText()
                            : root.get("error").asText();
                    throw new DataStoreEntryNotFoundException("Entry '" + name + "' not found: " + errorMsg);
                }

                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    throw new DataStoreEntryNotFoundException("Entry '" + name + "' not found");
                }

                String encodedData = dataNode.has("data") ? dataNode.get("data").asText() : null;
                if (encodedData == null || encodedData.isEmpty()) {
                    throw new DataStoreClientException("Entry '" + name + "' has no data");
                }

                return new String(Base64.getDecoder().decode(encodedData), StandardCharsets.UTF_8);
            } else if (response.statusCode() == 404) {
                throw new DataStoreEntryNotFoundException("Entry '" + name + "' not found");
            } else {
                throw new DataStoreClientException(
                        "Failed to download data store entry: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DataStoreClientException("Failed to download data store entry", e);
        }
    }

    /**
     * Lists all entries in the data store.
     *
     * @return a list of entry names
     */
    public List<String> list() {
        LOG.debug("Listing data store entries");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_DATA_STORE_PATH + "/list")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                List<String> names = new ArrayList<>();
                if (dataNode.isArray()) {
                    for (JsonNode entry : dataNode) {
                        if (entry.has("name")) {
                            names.add(entry.get("name").asText());
                        }
                    }
                }
                return names;
            } else {
                throw new DataStoreClientException("Failed to list data store entries: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DataStoreClientException("Failed to list data store entries", e);
        }
    }

    /**
     * Removes a data store entry by name.
     *
     * @param name the entry name to remove
     * @return true if removed, false if not found
     */
    public boolean removeByName(String name) {
        LOG.debug("Removing data store entry: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(
                            WanakuTestConstants.ROUTER_DATA_STORE_PATH + "/remove?name=" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Remove response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Data store entry removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Data store entry not found: {}", name);
                return false;
            } else {
                throw new DataStoreClientException("Failed to remove data store entry: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DataStoreClientException("Failed to remove data store entry", e);
        }
    }

    /**
     * Removes all entries from the data store by listing them and removing each one.
     */
    public void clearAll() {
        LOG.debug("Clearing all data store entries");

        List<String> entries = list();
        for (String name : entries) {
            try {
                removeByName(name);
            } catch (Exception e) {
                LOG.warn("Failed to remove data store entry {}: {}", name, e.getMessage());
            }
        }
        LOG.debug("Cleared {} data store entries", entries.size());
    }

    /**
     * Checks if the data store API is available.
     *
     * @return true if the /list endpoint returns HTTP 200
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_DATA_STORE_PATH + "/list")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Data store API not available: {}", e.getMessage());
            return false;
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

    public String getBaseUrl() {
        return baseUrl;
    }

    // Exception classes
    public static class DataStoreClientException extends RuntimeException {
        public DataStoreClientException(String message) {
            super(message);
        }

        public DataStoreClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class DataStoreEntryNotFoundException extends DataStoreClientException {
        public DataStoreEntryNotFoundException(String message) {
            super(message);
        }
    }
}
