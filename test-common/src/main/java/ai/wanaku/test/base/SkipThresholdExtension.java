package ai.wanaku.test.base;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * JUnit 5 extension that fails the build when the percentage of skipped tests
 * exceeds a configurable threshold.
 *
 * <p>Tracks test outcomes (executed vs. skipped/aborted) within each Maven module's
 * failsafe execution and checks the skip percentage when the root store is closed.
 * Uses {@link ExtensionContext.Store.CloseableResource} so exceptions propagate
 * reliably through maven-failsafe-plugin.
 *
 * <p>Configure via system property {@code wanaku.test.skip.threshold} (0–100, default 30).
 */
public class SkipThresholdExtension implements TestWatcher, BeforeAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SkipThresholdExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(SkipTracker.class, key -> new SkipTracker(), SkipTracker.class);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        getTracker(context).recordExecuted();
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        getTracker(context).recordExecuted();
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        getTracker(context).recordSkipped();
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        getTracker(context).recordSkipped();
    }

    private SkipTracker getTracker(ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(SkipTracker.class, key -> new SkipTracker(), SkipTracker.class);
    }

    static class SkipTracker implements ExtensionContext.Store.CloseableResource {

        private static final Logger LOG = LoggerFactory.getLogger(SkipTracker.class);

        private final AtomicInteger executed = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();

        void recordExecuted() {
            executed.incrementAndGet();
        }

        void recordSkipped() {
            skipped.incrementAndGet();
        }

        @Override
        public void close() {
            int exec = executed.get();
            int skip = skipped.get();
            int total = exec + skip;

            if (total == 0) {
                return;
            }

            int threshold = WanakuTestConstants.DEFAULT_SKIP_THRESHOLD;
            String raw = System.getProperty(WanakuTestConstants.PROP_SKIP_THRESHOLD);
            if (raw != null) {
                try {
                    threshold = Math.max(0, Math.min(100, Integer.parseInt(raw)));
                } catch (NumberFormatException e) {
                    LOG.warn(
                            "Invalid skip threshold '{}', using default {}%",
                            raw, WanakuTestConstants.DEFAULT_SKIP_THRESHOLD);
                }
            }

            int skipPercent = skip * 100 / total;
            LOG.info("Test skip summary: {}/{} skipped ({}%), threshold {}%", skip, total, skipPercent, threshold);

            // Compare without integer truncation: skip/total > threshold/100
            if (skip * 100 > threshold * total) {
                throw new AssertionError(String.format(
                        "Skip threshold exceeded: %d%% of tests were skipped (%d/%d), threshold is %d%%",
                        skipPercent, skip, total, threshold));
            }
        }
    }
}
