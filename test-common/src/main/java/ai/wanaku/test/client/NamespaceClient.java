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

public class NamespaceClient {

    private static final Logger LOG = LoggerFactory.getLogger(NamespaceClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public NamespaceClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public void create(String name) {
        LOG.debug("Creating namespace: {}", name);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Namespace created: {}", name);
            } else if (response.statusCode() == 409) {
                throw new NamespaceExistsException("Namespace '" + name + "' already exists");
            } else {
                throw new NamespaceClientException(
                        "Failed to create namespace: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to create namespace", e);
        }
    }

    public List<JsonNode> list() {
        LOG.debug("Listing namespaces");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List namespaces response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                List<JsonNode> namespaces = new ArrayList<>();
                if (dataNode.isArray()) {
                    for (JsonNode ns : dataNode) {
                        namespaces.add(ns);
                    }
                }
                return namespaces;
            } else {
                throw new NamespaceClientException("Failed to list namespaces: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to list namespaces", e);
        }
    }

    public JsonNode show(String name) {
        LOG.debug("Showing namespace: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedName)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Show namespace response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    throw new NamespaceNotFoundException("Namespace '" + name + "' not found");
                }
                return dataNode;
            } else if (response.statusCode() == 404) {
                throw new NamespaceNotFoundException("Namespace '" + name + "' not found");
            } else {
                throw new NamespaceClientException(
                        "Failed to show namespace: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to show namespace", e);
        }
    }

    public boolean delete(String name) {
        LOG.debug("Deleting namespace: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Delete namespace response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Namespace deleted: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Namespace not found: {}", name);
                return false;
            } else {
                throw new NamespaceClientException("Failed to delete namespace: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to delete namespace", e);
        }
    }

    public void update(String name, Map<String, Object> updates) {
        LOG.debug("Updating namespace: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            String json = objectMapper.writeValueAsString(updates);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedName)
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                LOG.debug("Namespace updated: {}", name);
            } else if (response.statusCode() == 404) {
                throw new NamespaceNotFoundException("Namespace '" + name + "' not found");
            } else {
                throw new NamespaceClientException(
                        "Failed to update namespace: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to update namespace", e);
        }
    }

    public void cleanup(String name) {
        LOG.debug("Cleaning up namespace: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(
                            WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedName + "/cleanup")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                LOG.debug("Namespace cleaned up: {}", name);
            } else if (response.statusCode() == 404) {
                throw new NamespaceNotFoundException("Namespace '" + name + "' not found");
            } else {
                throw new NamespaceClientException(
                        "Failed to cleanup namespace: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to cleanup namespace", e);
        }
    }

    public void addLabel(String name, String labelKey, String labelValue) {
        LOG.debug("Adding label {}={} to namespace: {}", labelKey, labelValue, name);

        Map<String, String> label = new HashMap<>();
        label.put(labelKey, labelValue);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            String json = objectMapper.writeValueAsString(label);

            HttpRequest request = buildRequest(
                            WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedName + "/labels")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
                throw new NamespaceClientException(
                        "Failed to add label: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to add label", e);
        }
    }

    public void removeLabel(String name, String labelKey) {
        LOG.debug("Removing label {} from namespace: {}", labelKey, name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            String encodedKey = URLEncoder.encode(labelKey, StandardCharsets.UTF_8);

            HttpRequest request = buildRequest(
                            WanakuTestConstants.ROUTER_NAMESPACES_PATH + "/" + encodedName + "/labels/" + encodedKey)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw new NamespaceClientException(
                        "Failed to remove label: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NamespaceClientException("Failed to remove label", e);
        }
    }

    public boolean exists(String name) {
        try {
            show(name);
            return true;
        } catch (NamespaceNotFoundException e) {
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

    public static class NamespaceClientException extends RuntimeException {
        public NamespaceClientException(String message) {
            super(message);
        }

        public NamespaceClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NamespaceExistsException extends NamespaceClientException {
        public NamespaceExistsException(String message) {
            super(message);
        }
    }

    public static class NamespaceNotFoundException extends NamespaceClientException {
        public NamespaceNotFoundException(String message) {
            super(message);
        }
    }
}
