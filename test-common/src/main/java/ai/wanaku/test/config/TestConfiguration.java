package ai.wanaku.test.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import ai.wanaku.test.WanakuTestConstants;

public class TestConfiguration {

    private final Path serverBinaryPath;
    private final Path camelCapabilityJarPath;
    private final Path artifactsDir;
    private final Path tempDataDir;
    private final Path evaluatorWasmPath;
    private final Duration defaultTimeout;
    private final boolean mcpIdFilterEnabled;

    private TestConfiguration(Builder builder) {
        this.serverBinaryPath = builder.serverBinaryPath;
        this.camelCapabilityJarPath = builder.camelCapabilityJarPath;
        this.artifactsDir = builder.artifactsDir;
        this.tempDataDir = builder.tempDataDir;
        this.evaluatorWasmPath = builder.evaluatorWasmPath;
        this.defaultTimeout = builder.defaultTimeout;
        this.mcpIdFilterEnabled = builder.mcpIdFilterEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TestConfiguration fromSystemProperties() {
        String artifactsDirStr =
                System.getProperty(WanakuTestConstants.PROP_ARTIFACTS_DIR, WanakuTestConstants.DEFAULT_ARTIFACTS_DIR);
        Path artifactsDir = Path.of(artifactsDirStr).toAbsolutePath().normalize();

        String timeoutStr = System.getProperty(WanakuTestConstants.PROP_TIMEOUT, "60");
        Duration timeout = Duration.ofSeconds(Long.parseLong(timeoutStr.replaceAll("[^0-9]", "")));

        Path serverBinary = findServerBinary();

        return builder()
                .artifactsDir(artifactsDir)
                .serverBinaryPath(serverBinary)
                .camelCapabilityJarPath(findCicJar(artifactsDir))
                .evaluatorWasmPath(findEvaluatorWasm(serverBinary))
                .defaultTimeout(timeout)
                .mcpIdFilterEnabled(
                        Boolean.parseBoolean(System.getProperty(WanakuTestConstants.PROP_MCP_ID_FILTER, "false")))
                .build();
    }

    private static Path findServerBinary() {
        String explicitPath = System.getProperty(WanakuTestConstants.PROP_SERVER_BINARY);
        if (explicitPath == null) {
            return null;
        }
        return Path.of(expandTilde(explicitPath)).toAbsolutePath().normalize();
    }

    private static String expandTilde(String path) {
        if (path.startsWith("~" + java.io.File.separator) || path.equals("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private static Path findCicJar(Path artifactsDir) {
        String explicitPath = System.getProperty(WanakuTestConstants.PROP_CAMEL_CAPABILITY_JAR);
        if (explicitPath != null) {
            return Path.of(expandTilde(explicitPath)).toAbsolutePath().normalize();
        }

        if (Files.exists(artifactsDir)) {
            try (var stream = Files.list(artifactsDir)) {
                Path cicDir = stream.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("camel-integration-capability"))
                        .findFirst()
                        .orElse(null);

                if (cicDir != null) {
                    try (var jarStream = Files.list(cicDir)) {
                        return jarStream
                                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                .findFirst()
                                .orElse(null);
                    }
                }
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Resolves the path to a compiled evaluator WASM action used as a valid processor in
     * evaluator activation tests. The Wanaku server compiles every referenced WASM module before
     * activating a configuration, so these tests need a real, loadable component.
     *
     * <p>An explicit {@code wanaku.test.evaluator.wasm} system property always wins. Otherwise, as
     * a convenience for a standard cargo checkout, the path is derived from the server binary
     * location ({@code <wanaku>/target/<profile>/wanaku-server} → {@code
     * <wanaku>/actions/dist/safety_warn_action.wasm}) and only used when it actually exists.
     * Returns {@code null} when no valid WASM can be located, in which case the activation tests
     * skip rather than fail.
     */
    private static Path findEvaluatorWasm(Path serverBinary) {
        String explicitPath = System.getProperty(WanakuTestConstants.PROP_EVALUATOR_WASM);
        if (explicitPath != null) {
            return Path.of(expandTilde(explicitPath)).toAbsolutePath().normalize();
        }

        if (serverBinary == null) {
            return null;
        }

        // <wanaku>/target/<profile>/wanaku-server -> climb three parents to the repo root.
        Path wanakuRoot = serverBinary.getParent();
        for (int i = 0; i < 2 && wanakuRoot != null; i++) {
            wanakuRoot = wanakuRoot.getParent();
        }
        if (wanakuRoot == null) {
            return null;
        }

        Path candidate = wanakuRoot
                .resolve("actions/dist/safety_warn_action.wasm")
                .toAbsolutePath()
                .normalize();
        return Files.exists(candidate) ? candidate : null;
    }

    public Path getServerBinaryPath() {
        return serverBinaryPath;
    }

    public Path getEvaluatorWasmPath() {
        return evaluatorWasmPath;
    }

    public Path getCamelCapabilityJarPath() {
        return camelCapabilityJarPath;
    }

    public Path getArtifactsDir() {
        return artifactsDir;
    }

    public Path getTempDataDir() {
        return tempDataDir;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    /**
     * Whether the generated server pipeline should include the {@code wanaku_mcp_id} filter, which
     * extracts the JSON-RPC id from the request body once and exposes it to downstream filters as
     * {@code mcp.id} metadata (wanaku-ai/wanaku#1849). Servers that predate that change do not
     * register the filter and abort startup on an unknown filter type, so this defaults to
     * {@code false} and must be enabled only when the target server is known to support it.
     */
    public boolean isMcpIdFilterEnabled() {
        return mcpIdFilterEnabled;
    }

    public static class Builder {
        private Path serverBinaryPath;
        private Path camelCapabilityJarPath;
        private Path artifactsDir;
        private Path tempDataDir;
        private Path evaluatorWasmPath;
        private Duration defaultTimeout = WanakuTestConstants.DEFAULT_TIMEOUT;
        private boolean mcpIdFilterEnabled;

        public Builder serverBinaryPath(Path serverBinaryPath) {
            this.serverBinaryPath = serverBinaryPath;
            return this;
        }

        public Builder camelCapabilityJarPath(Path camelCapabilityJarPath) {
            this.camelCapabilityJarPath = camelCapabilityJarPath;
            return this;
        }

        public Builder artifactsDir(Path artifactsDir) {
            this.artifactsDir = artifactsDir;
            return this;
        }

        public Builder tempDataDir(Path tempDataDir) {
            this.tempDataDir = tempDataDir;
            return this;
        }

        public Builder evaluatorWasmPath(Path evaluatorWasmPath) {
            this.evaluatorWasmPath = evaluatorWasmPath;
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public Builder mcpIdFilterEnabled(boolean mcpIdFilterEnabled) {
            this.mcpIdFilterEnabled = mcpIdFilterEnabled;
            return this;
        }

        public TestConfiguration build() {
            return new TestConfiguration(this);
        }
    }
}
