package ai.wanaku.test.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import ai.wanaku.test.WanakuTestConstants;

/**
 * Configuration holder for the entire test framework.
 * Created once per test suite and immutable after initialization.
 */
public class TestConfiguration {

    private final Path routerJarPath;
    private final Path httpToolServiceJarPath;
    private final Path fileProviderJarPath;
    private final Path camelCapabilityJarPath;
    private final Path artifactsDir;
    private final Path tempDataDir;
    private final Duration defaultTimeout;

    private TestConfiguration(Builder builder) {
        this.routerJarPath = builder.routerJarPath;
        this.httpToolServiceJarPath = builder.httpToolServiceJarPath;
        this.fileProviderJarPath = builder.fileProviderJarPath;
        this.camelCapabilityJarPath = builder.camelCapabilityJarPath;
        this.artifactsDir = builder.artifactsDir;
        this.tempDataDir = builder.tempDataDir;
        this.defaultTimeout = builder.defaultTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a configuration with sensible defaults from system properties.
     */
    public static TestConfiguration fromSystemProperties() {
        String artifactsDirStr =
                System.getProperty(WanakuTestConstants.PROP_ARTIFACTS_DIR, WanakuTestConstants.DEFAULT_ARTIFACTS_DIR);
        Path artifactsDir = Path.of(artifactsDirStr);

        String timeoutStr = System.getProperty(WanakuTestConstants.PROP_TIMEOUT, "60");
        Duration timeout = Duration.ofSeconds(Long.parseLong(timeoutStr.replaceAll("[^0-9]", "")));

        return builder()
                .artifactsDir(artifactsDir)
                .routerJarPath(findJar(artifactsDir, "wanaku-router"))
                .httpToolServiceJarPath(findJar(artifactsDir, "wanaku-tool-service-http"))
                .fileProviderJarPath(findJar(artifactsDir, "wanaku-provider-file"))
                .camelCapabilityJarPath(findJar(artifactsDir, "camel-integration-capability"))
                .defaultTimeout(timeout)
                .build();
    }

    private static Path findJar(Path artifactsDir, String prefix) {
        // Check system property first
        String propKey;
        if (prefix.contains("router")) {
            propKey = WanakuTestConstants.PROP_ROUTER_JAR;
        } else if (prefix.contains("provider-file")) {
            propKey = WanakuTestConstants.PROP_FILE_PROVIDER_JAR;
        } else if (prefix.contains("camel-integration-capability")) {
            propKey = WanakuTestConstants.PROP_CAMEL_CAPABILITY_JAR;
        } else {
            propKey = WanakuTestConstants.PROP_HTTP_SERVICE_JAR;
        }
        String explicitPath = System.getProperty(propKey);
        if (explicitPath != null) {
            return Path.of(explicitPath);
        }

        // Search in artifacts directory
        if (Files.exists(artifactsDir)) {
            try {
                // First try: look for Quarkus app directory (fast-jar format)
                Path quarkusAppDir = Files.list(artifactsDir)
                        .filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .findFirst()
                        .orElse(null);

                if (quarkusAppDir != null) {
                    // Try Quarkus fast-jar format (quarkus-run.jar)
                    Path quarkusRunJar = quarkusAppDir.resolve("quarkus-run.jar");
                    if (Files.exists(quarkusRunJar)) {
                        return quarkusRunJar;
                    }

                    // Try standalone JAR inside the directory (fat JAR format, e.g., CIC)
                    Path fatJar = Files.list(quarkusAppDir)
                            .filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .findFirst()
                            .orElse(null);
                    if (fatJar != null) {
                        return fatJar;
                    }
                }

                // Last try: look for standalone JAR file directly in artifacts/
                return Files.list(artifactsDir)
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .findFirst()
                        .orElse(null);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public Path getRouterJarPath() {
        return routerJarPath;
    }

    public Path getHttpToolServiceJarPath() {
        return httpToolServiceJarPath;
    }

    public Path getFileProviderJarPath() {
        return fileProviderJarPath;
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

    public static class Builder {
        private Path routerJarPath;
        private Path httpToolServiceJarPath;
        private Path fileProviderJarPath;
        private Path camelCapabilityJarPath;
        private Path artifactsDir;
        private Path tempDataDir;
        private Duration defaultTimeout = WanakuTestConstants.DEFAULT_TIMEOUT;

        public Builder routerJarPath(Path routerJarPath) {
            this.routerJarPath = routerJarPath;
            return this;
        }

        public Builder httpToolServiceJarPath(Path httpToolServiceJarPath) {
            this.httpToolServiceJarPath = httpToolServiceJarPath;
            return this;
        }

        public Builder fileProviderJarPath(Path fileProviderJarPath) {
            this.fileProviderJarPath = fileProviderJarPath;
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

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public TestConfiguration build() {
            return new TestConfiguration(this);
        }
    }
}
