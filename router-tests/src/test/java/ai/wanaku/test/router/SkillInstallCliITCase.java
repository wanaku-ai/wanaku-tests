package ai.wanaku.test.router;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ai.wanaku.test.client.CLIExecutor;
import ai.wanaku.test.client.CLIResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Exercises {@code wanaku skill install --to} and the version-aware references it installs (wanaku-barn#241).
 * {@code --claude} and {@code --codex} are deliberately not exercised: they target the real home directory, which a
 * native CLI cannot be redirected away from.
 */
@Timeout(120)
class SkillInstallCliITCase {
    private static final String DOCS_URL = "https://github.com/wanaku-ai/wanaku-barn/blob/";
    private static final String RAW_URL = "https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/";
    private static final String CRD_DIRECTORY = "apps/wanaku-operator/deploy/helm/wanaku-operator/crds/";
    private static final Pattern VERSION_LINE =
            Pattern.compile("(?m)^Wanaku CLI version (?<release>\\d+\\.\\d+\\.\\d+)(?<qualifier>-[A-Za-z0-9.-]+)?$");
    // The two URL forms the build renders (documentation pages and operator CRDs), without any #fragment.
    // Other GitHub URLs in the skills are copied unchanged and may be pinned to main on purpose.
    private static final Pattern RENDERED_URL = Pattern.compile("(?:" + Pattern.quote(DOCS_URL) + "[^/\\s)#]+/docs/|"
            + Pattern.quote(RAW_URL) + "[^/\\s)#]+/" + Pattern.quote(CRD_DIRECTORY) + ")[^\\s)#]+");
    // Links between skills such as ](../wanaku-operator/SKILL.md#section), captured without the fragment.
    // Repository-relative ](../../ links must not survive installation at all; the rendering test covers them.
    private static final Pattern CROSS_SKILL_LINK = Pattern.compile("\\]\\((?<target>\\.\\./(?!\\.\\./)[^\\s)#]+)");

    @TempDir
    Path tempDir;

    private CLIExecutor cli;

    @BeforeEach
    void setupCli() {
        cli = CLIExecutor.createDefault();
        assumeThat(cli.isAvailable()).as("Wanaku CLI must be available").isTrue();
    }

    @Test
    void shouldRenderVersionedReferencesOnInstall() throws IOException {
        String expectedRef = expectedRefFromCliVersion();
        // No part of this path exists yet: the CLI must create the missing parent directories.
        Path destination = tempDir.resolve("nested/custom/skills");

        assertSuccess(install(destination));

        assertThat(destination.resolve("wanaku-mcp-basics/SKILL.md")).isRegularFile();
        assertThat(destination.resolve("wanaku-operator/SKILL.md")).isRegularFile();
        assertThat(destination.resolve("wanaku-service-catalogs/SKILL.md")).isRegularFile();
        assertThat(destination.resolve("index.txt")).doesNotExist();
        Set<String> urls = new TreeSet<>();
        for (Path skillFile : installedSkillFiles(destination)) {
            String content = Files.readString(skillFile);
            List<String> unrenderedLines = content.lines()
                    .filter(line -> line.contains("<!-- wanaku:") || line.contains("](../../"))
                    .toList();
            assertThat(unrenderedLines)
                    .as("Unrendered repository references in %s", skillFile)
                    .isEmpty();
            Matcher url = RENDERED_URL.matcher(content);
            while (url.find()) {
                urls.add(url.group());
            }
        }
        String docsBase = DOCS_URL + expectedRef + "/";
        String rawBase = RAW_URL + expectedRef + "/";
        assertThat(urls)
                .allMatch(url -> url.startsWith(docsBase) || url.startsWith(rawBase), "uses ref " + expectedRef)
                .contains(docsBase + "docs/usage.md", rawBase + CRD_DIRECTORY + "wanakurouters.wanaku.ai-v1.yml");
    }

    @Test
    void shouldKeepCrossSkillLinksResolvable() throws IOException {
        Path destination = tempDir.resolve("skills");
        assertSuccess(install(destination));

        List<String> crossSkillLinks = new ArrayList<>();
        for (Path skillFile : installedSkillFiles(destination)) {
            Matcher link = CROSS_SKILL_LINK.matcher(Files.readString(skillFile));
            while (link.find()) {
                String target = link.group("target");
                crossSkillLinks.add(target);
                assertThat(skillFile.getParent().resolve(target).normalize())
                        .as("Link %s in %s", target, skillFile)
                        .isRegularFile()
                        .startsWith(destination);
            }
        }
        assertThat(crossSkillLinks)
                .as("Cross-skill links in the installed skills")
                .isNotEmpty();
    }

    @Test
    void shouldRefuseInstallWhenASkillAlreadyExists() throws IOException {
        Path destination = tempDir.resolve("skills");
        Path existingSkill = destination.resolve("wanaku-service-catalogs");
        Files.createDirectories(existingSkill);
        Files.writeString(existingSkill.resolve("SKILL.md"), "Local skill");

        CLIResult result = install(destination);

        assertThat(result.getExitCode())
                .as("CLI output: %s", result.getCombinedOutput())
                .isEqualTo(1);
        assertThat(result.getCombinedOutput()).contains("wanaku-service-catalogs", "already exists");
        // Every skill is checked before any is copied, so a refused install must leave nothing behind.
        try (Stream<Path> entries = Files.list(destination)) {
            assertThat(entries).containsExactly(existingSkill);
        }
        assertThat(existingSkill.resolve("SKILL.md")).hasContent("Local skill");
    }

    // Mirrors the build-time rule: a plain X.Y.Z release links to its release tag, any qualified build to main.
    private String expectedRefFromCliVersion() {
        CLIResult result = cli.execute("--version");
        assertSuccess(result);
        Matcher version = VERSION_LINE.matcher(result.getCombinedOutput());
        assertThat(version.find())
                .as("CLI version output: %s", result.getCombinedOutput())
                .isTrue();
        return version.group("qualifier") == null ? "wanaku-" + version.group("release") : "main";
    }

    private CLIResult install(Path destination) {
        // A relative --to resolves against the CLI's working directory, so always pass an absolute path.
        return cli.execute(
                "skill", "install", "--to", destination.toAbsolutePath().toString());
    }

    private static List<Path> installedSkillFiles(Path destination) throws IOException {
        try (Stream<Path> files = Files.walk(destination)) {
            return files.filter(file -> file.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .toList();
        }
    }

    private static void assertSuccess(CLIResult result) {
        assertThat(result.getExitCode())
                .as("CLI output: %s", result.getCombinedOutput())
                .isZero();
    }
}
