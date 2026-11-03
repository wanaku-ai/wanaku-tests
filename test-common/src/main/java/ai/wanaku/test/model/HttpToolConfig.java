package ai.wanaku.test.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for registering an HTTP tool.
 *
 * For static headers, use {@link ai.wanaku.test.client.RouterClient#registerToolWithConfig}
 * with configurationData in properties format (e.g., "header.X-Api-Key=secret").
 */
public class HttpToolConfig {

    private String name;
    private String description;
    private String uri;
    private String method = "GET";
    private Map<String, Object> inputSchema;
    private final List<String> requiredProperties = new ArrayList<>();

    private HttpToolConfig() {}

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getUri() {
        return uri;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public static class Builder {
        private final HttpToolConfig config = new HttpToolConfig();

        public Builder name(String name) {
            config.name = name;
            return this;
        }

        public Builder description(String description) {
            config.description = description;
            return this;
        }

        public Builder uri(String uri) {
            config.uri = uri;
            return this;
        }

        public Builder method(String method) {
            config.method = method;
            return this;
        }

        /**
         * Adds a property to the input schema.
         * Use this for tools with parameters like {parameter.valueOrElse('count', 1)}.
         */
        public Builder property(String name, String type, String description) {
            ensureInputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) config.inputSchema.get("properties");
            properties.put(name, Map.of("type", type, "description", description));
            return this;
        }

        /**
         * Marks properties as required.
         */
        public Builder required(String... names) {
            config.requiredProperties.addAll(Arrays.asList(names));
            return this;
        }

        private void ensureInputSchema() {
            if (config.inputSchema == null) {
                config.inputSchema = new HashMap<>();
                config.inputSchema.put("type", "object");
                config.inputSchema.put("properties", new HashMap<>());
            }
        }

        public HttpToolConfig build() {
            if (config.name == null || config.name.isEmpty()) {
                throw new IllegalStateException("Tool name is required");
            }
            if (config.uri == null || config.uri.isEmpty()) {
                throw new IllegalStateException("Tool URI is required");
            }
            if (config.description == null) {
                config.description = "HTTP tool: " + config.name;
            }

            ensureInputSchema();

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) config.inputSchema.get("properties");

            // CamelHttpMethod tells Wanaku which HTTP method to use
            properties.put(
                    "CamelHttpMethod",
                    Map.of(
                            "type", "string",
                            "description", "HTTP method",
                            "target", "header",
                            "scope", "service",
                            "value", config.method));

            if (!config.requiredProperties.isEmpty()) {
                config.inputSchema.put("required", new ArrayList<>(config.requiredProperties));
            }

            return config;
        }
    }
}
