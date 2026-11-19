package ai.wanaku.fixtures.capture;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Shared invocation counters for the governance capture fixture. The MCP handlers increment these
 * whenever a governed request actually reaches this upstream server; the REST endpoint exposes them
 * so governance tests can prove a denied request never got here (counter stays at zero) while an
 * allowed request did (counter increments).
 */
@ApplicationScoped
public class CaptureState {

    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger resourceReads = new AtomicInteger();
    private final AtomicInteger promptGets = new AtomicInteger();
    private final AtomicReference<JsonNode> lastToolCall =
            new AtomicReference<>(JsonNodeFactory.instance.objectNode());
    private final AtomicReference<String> toolAuthorization = new AtomicReference<>("absent");
    private final AtomicReference<String> promptAuthorization = new AtomicReference<>("absent");
    private final boolean syntheticAuthorizationEnabled;
    private final String primaryToken;
    private final String secondaryToken;

    public CaptureState() {
        this(Boolean.getBoolean("capture.synthetic-authorization"),
                System.getenv("CAPTURE_SYNTHETIC_PRIMARY_TOKEN"),
                System.getenv("CAPTURE_SYNTHETIC_SECONDARY_TOKEN"));
    }

    CaptureState(boolean syntheticAuthorizationEnabled, String primaryToken, String secondaryToken) {
        this.syntheticAuthorizationEnabled = syntheticAuthorizationEnabled;
        this.primaryToken = primaryToken;
        this.secondaryToken = secondaryToken;
    }

    public String recordToolAuthorization(String authorization, boolean loopback) {
        String observation = identifyAuthorization(authorization, loopback);
        toolAuthorization.set(observation);
        return syntheticEcho(observation);
    }

    public String recordPromptAuthorization(String authorization, boolean loopback) {
        String observation = identifyAuthorization(authorization, loopback);
        promptAuthorization.set(observation);
        return syntheticEcho(observation);
    }

    private String identifyAuthorization(String authorization, boolean loopback) {
        if (!syntheticAuthorizationEnabled) {
            return "disabled";
        }
        if (!loopback) {
            return "remote";
        }
        if (authorization == null) {
            return "absent";
        }
        if (matchesSyntheticToken(authorization, primaryToken)) {
            return "primary";
        }
        if (matchesSyntheticToken(authorization, secondaryToken)) {
            return "secondary";
        }
        return "unmatched";
    }

    private boolean matchesSyntheticToken(String authorization, String expectedToken) {
        return expectedToken != null && expectedToken.startsWith("synthetic-")
                && authorization.equals("Bearer " + expectedToken);
    }

    private String syntheticEcho(String observation) {
        return switch (observation) {
            case "primary" -> " authorization=Bearer " + primaryToken;
            case "secondary" -> " authorization=Bearer " + secondaryToken;
            default -> "";
        };
    }

    public String getToolAuthorization() {
        return toolAuthorization.get();
    }

    public String getPromptAuthorization() {
        return promptAuthorization.get();
    }

    public void recordToolCall(String name, JsonNode arguments) {
        var call = JsonNodeFactory.instance.objectNode();
        call.put("name", name);
        call.set("arguments", arguments.deepCopy());
        lastToolCall.set(call);
    }

    public JsonNode getLastToolCall() {
        return lastToolCall.get().deepCopy();
    }

    public int incrementToolCalls() {
        return toolCalls.incrementAndGet();
    }

    public int incrementResourceReads() {
        return resourceReads.incrementAndGet();
    }

    public int incrementPromptGets() {
        return promptGets.incrementAndGet();
    }

    public int getToolCalls() {
        return toolCalls.get();
    }

    public int getResourceReads() {
        return resourceReads.get();
    }

    public int getPromptGets() {
        return promptGets.get();
    }
}
