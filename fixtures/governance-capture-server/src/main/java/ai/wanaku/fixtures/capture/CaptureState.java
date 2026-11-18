package ai.wanaku.fixtures.capture;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<String> toolAuthorization = new AtomicReference<>("");
    private final AtomicReference<String> promptAuthorization = new AtomicReference<>("");

    public void recordToolAuthorization(String authorization) {
        toolAuthorization.set(authorization == null ? "" : authorization);
    }

    public void recordPromptAuthorization(String authorization) {
        promptAuthorization.set(authorization == null ? "" : authorization);
    }

    public String getToolAuthorization() {
        return toolAuthorization.get();
    }

    public String getPromptAuthorization() {
        return promptAuthorization.get();
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
