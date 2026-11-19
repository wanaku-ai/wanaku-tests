package ai.wanaku.fixtures.capture;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusIntegrationTest
@Timeout(30)
class CaptureMcpServerITCase {

    @TestHTTPResource("/mcp")
    URI endpoint;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsStructuredResultsAndRecordsTypedArguments() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String session = initialize(client);
            int before = counts(client).path("toolCalls").asInt();
            List<String> values = List.of(
                    "0", "\"0\"", "false", "[null,false,0,\"0\"]", "{\"nested\":{\"empty\":[],\"value\":null}}");
            for (String value : values) {
                var arguments = mapper.createObjectNode();
                arguments.set("value", mapper.readTree(value));
                var request = mapper.createObjectNode();
                request.put("jsonrpc", "2.0").put("id", "structured").put("method", "tools/call");
                request.putObject("params").put("name", "capture_structured_tool").set("arguments", arguments);

                JsonNode response = envelope(send(client, session, request.toString()));
                assertThat(response.path("id").asText()).isEqualTo("structured");
                assertThat(response.has("error")).isFalse();
                JsonNode result = response.path("result");
                assertThat(result.path("isError").asBoolean()).isFalse();
                assertThat(result.get("structuredContent")).isEqualTo(arguments);
                assertThat(result.path("content").size()).isEqualTo(2);
                assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
                assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("capture structured result");
                assertThat(result.path("content").get(1).path("type").asText()).isEqualTo("text");
                assertThat(result.path("content").get(1).path("text").asText()).isEqualTo("capture structured result end");

                JsonNode captured = get(client, "/capture/last-tool-call");
                assertThat(captured.path("name").asText()).isEqualTo("capture_structured_tool");
                assertThat(captured.get("arguments")).isEqualTo(arguments);
            }
            assertThat(counts(client).path("toolCalls").asInt()).isEqualTo(before + values.size());
        }
    }

    @Test
    void distinguishesToolExecutionErrorsFromProtocolErrors() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String session = initialize(client);
            int before = counts(client).path("toolCalls").asInt();
            JsonNode toolError = envelope(send(client, session, """
                    {"jsonrpc":"2.0","id":17,"method":"tools/call",
                     "params":{"name":"capture_error_tool","arguments":{}}}
                    """));
            assertThat(toolError.path("id").asInt()).isEqualTo(17);
            assertThat(toolError.has("error")).isFalse();
            assertThat(toolError.path("result").path("isError").asBoolean()).isTrue();
            assertThat(toolError.path("result").path("content").get(0).path("text").asText())
                    .isEqualTo("capture tool execution failed");
            assertThat(counts(client).path("toolCalls").asInt()).isEqualTo(before + 1);

            JsonNode protocolError = envelope(send(client, session, """
                    {"jsonrpc":"2.0","id":"unsupported","method":"capture/unsupported"}
                    """));
            assertThat(protocolError.path("id").asText()).isEqualTo("unsupported");
            assertThat(protocolError.has("result")).isFalse();
            assertThat(protocolError.path("error").path("code").asInt()).isEqualTo(-32601);
            assertThat(counts(client).path("toolCalls").asInt()).isEqualTo(before + 1);
        }
    }

    private String initialize(HttpClient client) throws Exception {
        HttpResponse<String> response = send(client, null, """
                {"jsonrpc":"2.0","id":"init","method":"initialize","params":{
                 "protocolVersion":"2025-03-26","capabilities":{},
                 "clientInfo":{"name":"capture-fixture-test","version":"1.0"}}}
                """);
        assertThat(envelope(response).path("id").asText()).isEqualTo("init");
        String session = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertThat(send(client, session, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """).statusCode()).isEqualTo(202);
        return session;
    }

    private HttpResponse<String> send(HttpClient client, String session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Protocol-Version", "2025-03-26")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode envelope(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        JsonNode envelope = mapper.readTree(response.body());
        assertThat(envelope.path("jsonrpc").asText()).isEqualTo("2.0");
        return envelope;
    }

    private JsonNode counts(HttpClient client) throws Exception {
        return get(client, "/capture/counts");
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
}
