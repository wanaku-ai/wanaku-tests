package ai.wanaku.test.config;

import java.nio.file.Files;
import java.nio.file.Path;
import ai.wanaku.test.WanakuTestConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticRouterConfigurationTest {
    @TempDir
    Path artifacts;

    @Test
    void builderCopyPreservesSemanticRouterDistributionPath() {
        Path jar = artifacts.resolve("router.jar");
        assertThat(TestConfiguration.builder().semanticRouterJarPath(jar).build().toBuilder()
                        .build()
                        .getSemanticRouterJarPath())
                .isEqualTo(jar);
    }

    @Test
    void explicitPathOverridesDiscoveryEvenIfArtifactIsAbsent() {
        withProperties(artifacts.resolve("missing.jar").toString(), () -> assertThat(
                        TestConfiguration.fromSystemProperties().getSemanticRouterJarPath())
                .isEqualTo(artifacts.resolve("missing.jar")));
    }

    @Test
    void discoveryExcludesSourceAndDocumentationArchivesAndSortsCandidates() throws Exception {
        Path directory = Files.createDirectory(artifacts.resolve("wanaku-semantic-router"));
        Files.createFile(directory.resolve("wanaku-semantic-router-0-sources.jar"));
        Files.createFile(directory.resolve("wanaku-semantic-router-0-javadoc.jar"));
        Files.createFile(directory.resolve("wanaku-semantic-router-2.jar"));
        Path expected = Files.createFile(directory.resolve("wanaku-semantic-router-1.jar"));
        withProperties(
                null, () -> assertThat(TestConfiguration.fromSystemProperties().getSemanticRouterJarPath())
                        .isEqualTo(expected));
    }

    private void withProperties(String jar, Runnable assertion) {
        String previousArtifacts = System.getProperty(WanakuTestConstants.PROP_ARTIFACTS_DIR);
        String previousJar = System.getProperty(WanakuTestConstants.PROP_SEMANTIC_ROUTER_JAR);
        try {
            System.setProperty(WanakuTestConstants.PROP_ARTIFACTS_DIR, artifacts.toString());
            restore(WanakuTestConstants.PROP_SEMANTIC_ROUTER_JAR, jar);
            assertion.run();
        } finally {
            restore(WanakuTestConstants.PROP_ARTIFACTS_DIR, previousArtifacts);
            restore(WanakuTestConstants.PROP_SEMANTIC_ROUTER_JAR, previousJar);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
