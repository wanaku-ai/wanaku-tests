package ai.wanaku.test.stub;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Deterministic, in-JVM stand-in for an OpenAI-compatible chat-completions LLM, used by the
 * governance tests so the evaluator engine produces reproducible verdicts without ever contacting a
 * paid or external model.
 *
 * <p>The Wanaku evaluator LLM engine POSTs to {@code <connection.url>/chat/completions} with an
 * OpenAI chat request whose user message embeds the request context ({@code Tool: <name>},
 * {@code Arguments:} ...). This stub inspects that user message: when it contains the configured
 * {@link #denyMarker}, it returns a {@code red} verdict (which the safety-review WASM processor maps
 * to {@code Block}); otherwise it returns {@code green} ({@code Pass}). The connection URL written
 * into the bootstrap config ends in {@code /v1/}, so the server dials {@code /v1/chat/completions};
 * the stub serves exactly that path.
 *
 * <p>{@link #getCallCount()} lets a test assert the evaluator was (or was not) invoked, e.g. to
 * prove a statically denied request never reached the evaluator stage.
 *
 * <p>Failure scenarios can replace the verdict: {@link #failWithStatus(int)} answers with an HTTP
 * error, {@link #respondWithContent(String)} returns a fixed message content (for example an empty
 * or schema-invalid result), {@link #delayResponses(Duration)} holds each response, and
 * {@link #stop()} makes the endpoint refuse connections.
 */
public final class DeterministicLlmStub {

    private static final Logger LOG = LoggerFactory.getLogger(DeterministicLlmStub.class);

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile String denyMarker;
    private volatile int failureStatus;
    private volatile String fixedContent;
    private volatile Duration responseDelay = Duration.ZERO;

    private HttpServer server;
    private ExecutorService executor;
    private String host;
    private int port;

    /**
     * @param denyMarker substring that, when present in the evaluator's user prompt, makes this stub
     *     return a blocking ({@code red}) verdict. A {@code null} or blank marker makes every request
     *     pass.
     */
    public DeterministicLlmStub(String denyMarker) {
        this.denyMarker = denyMarker;
    }

    public void start() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        server.createContext(CHAT_COMPLETIONS_PATH, this::handleChatCompletion);
        // A held response must not block the next request, so each exchange gets its own thread.
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        host = loopback.getHostAddress();
        port = server.getAddress().getPort();
        LOG.debug("Deterministic LLM stub started on port {} (deny marker: {})", port, denyMarker);
    }

    /** Stops the endpoint. Later connections are refused. Calling it again has no effect. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            executor.shutdownNow();
            LOG.debug("Deterministic LLM stub stopped");
        }
    }

    /** Base URL to write into the bootstrap LLM connection; the server appends {@code chat/completions}. */
    public String getConnectionUrl() {
        return "http://" + host + ":" + port + "/v1/";
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void setDenyMarker(String denyMarker) {
        this.denyMarker = denyMarker;
    }

    /** Answers every chat completion with this HTTP status and no body; {@code 0} restores verdicts. */
    public void failWithStatus(int status) {
        this.failureStatus = status;
    }

    /** Returns this exact message content instead of a verdict; {@code null} restores verdicts. */
    public void respondWithContent(String content) {
        this.fixedContent = content;
    }

    /** Waits this long before each response, to exceed the client timeout of the server. */
    public void delayResponses(Duration delay) {
        this.responseDelay = delay;
    }

    private void handleChatCompletion(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            callCount.incrementAndGet();

            String userPrompt = extractUserPrompt(exchange);
            Thread.sleep(responseDelay.toMillis());
            if (failureStatus != 0) {
                exchange.sendResponseHeaders(failureStatus, -1);
                return;
            }
            String content = fixedContent;
            if (content == null) {
                String marker = denyMarker;
                boolean deny = marker != null && !marker.isBlank() && userPrompt.contains(marker);
                content = verdictContent(deny ? "red" : "green");
            }

            byte[] body = buildChatResponse(content).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
        } catch (Exception e) {
            // Catch everything (not just RuntimeException): request-body reads and JSON parsing throw
            // checked IOException/JsonProcessingException. If those escaped, the exchange would never be
            // answered and the evaluator LLM call would block until its own timeout instead of failing
            // fast, turning a bad request into a hang.
            LOG.warn("LLM stub failed to handle request: {}", e.getMessage());
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private String extractUserPrompt(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        JsonNode root = mapper.readTree(raw);
        JsonNode messages = root.get("messages");
        if (messages == null || !messages.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode message : messages) {
            JsonNode content = message.get("content");
            if (content != null && content.isTextual()) {
                sb.append(content.asText()).append('\n');
            }
        }
        return sb.toString();
    }

    private String verdictContent(String verdict) {
        // The safety-review WASM processor accepts either a bare level string or a JSON object with a
        // "level" field; a JSON object is used here so the content is unambiguous.
        return mapper.createObjectNode().put("level", verdict).toString();
    }

    private String buildChatResponse(String content) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("role", "assistant");
            message.put("content", content);
            ObjectNode choice = mapper.createObjectNode();
            choice.set("message", message);
            ObjectNode response = mapper.createObjectNode();
            response.putArray("choices").add(choice);
            return mapper.writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to build LLM stub response", e);
        }
    }
}
