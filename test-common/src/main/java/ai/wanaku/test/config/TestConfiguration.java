package ai.wanaku.test.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import ai.wanaku.test.WanakuTestConstants;

public class TestConfiguration {

    private final Path serverBinaryPath;
    private final Path camelCapabilityJarPath;
    private final Path semanticRouterJarPath;
    private final Path artifactsDir;
    private final Path tempDataDir;
    private final Path evaluatorWasmPath;
    private final Path opaBinaryPath;
    private final Path opaWasmPath;
    private final String opaConnectionsJson;
    private final Duration defaultTimeout;
    private final boolean a2aEnabled;
    private final boolean mcpIdFilterEnabled;
    private final String forwardHeaders;
    private final boolean governanceEnabled;
    private final String actionPolicyJson;
    private final String governanceJson;
    private final String auditJson;
    private final String extraWanakuConfig;
    private final boolean persistenceEnabled;
    private final String llmConnectionUrl;

    private TestConfiguration(Builder builder) {
        this.serverBinaryPath = builder.serverBinaryPath;
        this.camelCapabilityJarPath = builder.camelCapabilityJarPath;
        this.semanticRouterJarPath = builder.semanticRouterJarPath;
        this.artifactsDir = builder.artifactsDir;
        this.tempDataDir = builder.tempDataDir;
        this.evaluatorWasmPath = builder.evaluatorWasmPath;
        this.opaBinaryPath = builder.opaBinaryPath;
        this.opaWasmPath = builder.opaWasmPath;
        this.opaConnectionsJson = builder.opaConnectionsJson;
        this.defaultTimeout = builder.defaultTimeout;
        this.a2aEnabled = builder.a2aEnabled;
        this.mcpIdFilterEnabled = builder.mcpIdFilterEnabled;
        this.forwardHeaders = builder.forwardHeaders;
        this.governanceEnabled = builder.governanceEnabled;
        this.actionPolicyJson = builder.actionPolicyJson;
        this.governanceJson = builder.governanceJson;
        this.auditJson = builder.auditJson;
        this.extraWanakuConfig = builder.extraWanakuConfig;
        this.persistenceEnabled = builder.persistenceEnabled;
        this.llmConnectionUrl = builder.llmConnectionUrl;
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
                .semanticRouterJarPath(findSemanticRouterJar(artifactsDir))
                .evaluatorWasmPath(findEvaluatorWasm(serverBinary))
                .opaBinaryPath(findExplicitPath(WanakuTestConstants.PROP_OPA_BINARY))
                .opaWasmPath(findOpaWasm(serverBinary))
                .defaultTimeout(timeout)
                .actionPolicyJson(System.getProperty(WanakuTestConstants.PROP_ACTION_POLICY))
                .a2aEnabled(Boolean.parseBoolean(System.getProperty(WanakuTestConstants.PROP_A2A_ENABLED, "false")))
                .mcpIdFilterEnabled(
                        Boolean.parseBoolean(System.getProperty(WanakuTestConstants.PROP_MCP_ID_FILTER, "false")))
                .forwardHeaders(System.getProperty(WanakuTestConstants.PROP_FORWARD_HEADERS))
                .governanceEnabled(
                        Boolean.parseBoolean(System.getProperty(WanakuTestConstants.PROP_GOVERNANCE_ENABLED, "false")))
                .build();
    }

    /**
     * Creates a builder pre-populated with this configuration's values. Governance tests use this to
     * derive a per-scenario configuration (enabling the governance filters and supplying the
     * scenario's action-policy / posture / LLM-stub URL) from the shared, system-property-driven
     * base configuration without re-resolving binary and artifact paths.
     */
    public Builder toBuilder() {
        return builder()
                .serverBinaryPath(serverBinaryPath)
                .camelCapabilityJarPath(camelCapabilityJarPath)
                .semanticRouterJarPath(semanticRouterJarPath)
                .artifactsDir(artifactsDir)
                .tempDataDir(tempDataDir)
                .evaluatorWasmPath(evaluatorWasmPath)
                .opaBinaryPath(opaBinaryPath)
                .opaWasmPath(opaWasmPath)
                .opaConnectionsJson(opaConnectionsJson)
                .defaultTimeout(defaultTimeout)
                .a2aEnabled(a2aEnabled)
                .mcpIdFilterEnabled(mcpIdFilterEnabled)
                .forwardHeaders(forwardHeaders)
                .governanceEnabled(governanceEnabled)
                .actionPolicyJson(actionPolicyJson)
                .governanceJson(governanceJson)
                .auditJson(auditJson)
                .extraWanakuConfig(extraWanakuConfig)
                .persistenceEnabled(persistenceEnabled)
                .llmConnectionUrl(llmConnectionUrl);
    }

    private static Path findExplicitPath(String property) {
        String value = System.getProperty(property);
        return value == null
                ? null
                : Path.of(expandTilde(value)).toAbsolutePath().normalize();
    }

    private static Path findOpaWasm(Path serverBinary) {
        Path explicit = findExplicitPath(WanakuTestConstants.PROP_OPA_WASM);
        if (explicit != null) {
            return explicit;
        }
        Path wanakuRoot = serverBinary == null ? null : serverBinary.getParent();
        for (int i = 0; i < 2 && wanakuRoot != null; i++) {
            wanakuRoot = wanakuRoot.getParent();
        }
        if (wanakuRoot == null) {
            return null;
        }
        Path candidate = wanakuRoot.resolve("actions/dist/opa_allow_action.wasm");
        return Files.exists(candidate) ? candidate.toAbsolutePath().normalize() : null;
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

    private static Path findSemanticRouterJar(Path artifactsDir) {
        Path explicit = findExplicitPath(WanakuTestConstants.PROP_SEMANTIC_ROUTER_JAR);
        if (explicit != null) {
            return explicit;
        }
        if (!Files.isDirectory(artifactsDir)) {
            return null;
        }
        try (var files = Files.walk(artifactsDir, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("wanaku-semantic-router")
                                && name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar")
                                && !name.startsWith("original-");
                    })
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot discover semantic router artifacts in " + artifactsDir, e);
        }
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
     * <wanaku>/actions/dist/}) and the first compiled safety action found there is used. The
     * action has been renamed over time (safety-warn → safety-review), so both names are probed.
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

        Path distDir = wanakuRoot.resolve("actions/dist");
        // The safety evaluator action has been renamed (safety-warn -> safety-review); prefer the
        // current name and fall back to the older one so a checkout of either revision resolves.
        for (String actionName : new String[] {"safety_review_action.wasm", "safety_warn_action.wasm"}) {
            Path candidate = distDir.resolve(actionName).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public Path getServerBinaryPath() {
        return serverBinaryPath;
    }

    public Path getEvaluatorWasmPath() {
        return evaluatorWasmPath;
    }

    public Path getOpaBinaryPath() {
        return opaBinaryPath;
    }

    public Path getOpaWasmPath() {
        return opaWasmPath;
    }

    /** Named OPA connections rendered as JSON under the bootstrap {@code opa_connections} key. */
    public String getOpaConnectionsJson() {
        return opaConnectionsJson;
    }

    public Path getCamelCapabilityJarPath() {
        return camelCapabilityJarPath;
    }

    public Path getSemanticRouterJarPath() {
        return semanticRouterJarPath;
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

    /** Enable the managed A2A listener for servers containing wanaku-ai/wanaku#2071. */
    public boolean isA2aEnabled() {
        return a2aEnabled;
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

    /**
     * Comma-separated allowlist of request header names the server may forward to downstream MCP
     * servers, exposed as the {@code WANAKU_FORWARD_HEADERS} environment variable. Header forwarding
     * is default-deny (wanaku-ai/wanaku#873): nothing is forwarded unless a header is named here (or
     * via a per-tool {@code wanaku.forward_headers} label). Returns {@code null} when unset, in which
     * case the server keeps its default-deny posture and the environment variable is not set.
     */
    public String getForwardHeaders() {
        return forwardHeaders;
    }

    /**
     * Whether the generated server pipeline should include the governance filters
     * ({@code wanaku_action_policy} followed by {@code wanaku_evaluator}) and whether the bootstrap
     * config should carry {@code governance:} / {@code action_policy:} blocks. Defaults to
     * {@code false} so every other module keeps its ungoverned pipeline; only the governance-tests
     * module enables it. Servers that predate wanaku-ai/wanaku#1900 do not register these filters
     * and abort startup on an unknown filter type.
     */
    public boolean isGovernanceEnabled() {
        return governanceEnabled;
    }

    /**
     * Action-policy definition rendered as a JSON object (a valid YAML flow mapping) and written
     * under the {@code action_policy:} key of the bootstrap config, from which the server seeds the
     * policy at startup. Returns {@code null} when no static policy should be emitted.
     */
    public String getActionPolicyJson() {
        return actionPolicyJson;
    }

    /**
     * Governance posture rendered as a JSON object (a valid YAML flow mapping) and written under the
     * {@code governance:} key of the bootstrap config. Returns {@code null} to let the server apply
     * its fail-closed defaults.
     */
    public String getGovernanceJson() {
        return governanceJson;
    }

    public String getAuditJson() {
        return auditJson;
    }

    /**
     * YAML lines appended verbatim to the bootstrap config, for settings that have no dedicated
     * builder field (for example startup {@code evaluators:} or {@code evaluator_startup_failure:}).
     * Returns {@code null} when unset.
     */
    public String getExtraWanakuConfig() {
        return extraWanakuConfig;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    /**
     * URL of the LLM connection written into the bootstrap config. Returns
     * {@link WanakuTestConstants#DEFAULT_LLM_CONNECTION_URL} when unset. Governance tests point this
     * at a deterministic in-JVM stub so evaluator decisions never reach an external LLM.
     */
    public String getLlmConnectionUrl() {
        return llmConnectionUrl != null ? llmConnectionUrl : WanakuTestConstants.DEFAULT_LLM_CONNECTION_URL;
    }

    public static class Builder {
        private Path serverBinaryPath;
        private Path camelCapabilityJarPath;
        private Path semanticRouterJarPath;
        private Path artifactsDir;
        private Path tempDataDir;
        private Path evaluatorWasmPath;
        private Path opaBinaryPath;
        private Path opaWasmPath;
        private String opaConnectionsJson;
        private Duration defaultTimeout = WanakuTestConstants.DEFAULT_TIMEOUT;
        private boolean a2aEnabled;
        private boolean mcpIdFilterEnabled;
        private String forwardHeaders;
        private boolean governanceEnabled;
        private String actionPolicyJson;
        private String governanceJson;
        private String auditJson;
        private String extraWanakuConfig;
        private boolean persistenceEnabled = true;
        private String llmConnectionUrl;

        public Builder serverBinaryPath(Path serverBinaryPath) {
            this.serverBinaryPath = serverBinaryPath;
            return this;
        }

        public Builder camelCapabilityJarPath(Path camelCapabilityJarPath) {
            this.camelCapabilityJarPath = camelCapabilityJarPath;
            return this;
        }

        public Builder semanticRouterJarPath(Path semanticRouterJarPath) {
            this.semanticRouterJarPath = semanticRouterJarPath;
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

        public Builder opaBinaryPath(Path opaBinaryPath) {
            this.opaBinaryPath = opaBinaryPath;
            return this;
        }

        public Builder opaWasmPath(Path opaWasmPath) {
            this.opaWasmPath = opaWasmPath;
            return this;
        }

        public Builder opaConnectionsJson(String opaConnectionsJson) {
            this.opaConnectionsJson = opaConnectionsJson;
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public Builder a2aEnabled(boolean a2aEnabled) {
            this.a2aEnabled = a2aEnabled;
            return this;
        }

        public Builder mcpIdFilterEnabled(boolean mcpIdFilterEnabled) {
            this.mcpIdFilterEnabled = mcpIdFilterEnabled;
            return this;
        }

        public Builder forwardHeaders(String forwardHeaders) {
            this.forwardHeaders = forwardHeaders;
            return this;
        }

        public Builder governanceEnabled(boolean governanceEnabled) {
            this.governanceEnabled = governanceEnabled;
            return this;
        }

        public Builder actionPolicyJson(String actionPolicyJson) {
            this.actionPolicyJson = actionPolicyJson;
            return this;
        }

        public Builder governanceJson(String governanceJson) {
            this.governanceJson = governanceJson;
            return this;
        }

        public Builder auditJson(String auditJson) {
            this.auditJson = auditJson;
            return this;
        }

        public Builder extraWanakuConfig(String extraWanakuConfig) {
            this.extraWanakuConfig = extraWanakuConfig;
            return this;
        }

        public Builder persistenceEnabled(boolean persistenceEnabled) {
            this.persistenceEnabled = persistenceEnabled;
            return this;
        }

        public Builder llmConnectionUrl(String llmConnectionUrl) {
            this.llmConnectionUrl = llmConnectionUrl;
            return this;
        }

        public TestConfiguration build() {
            return new TestConfiguration(this);
        }
    }
}
