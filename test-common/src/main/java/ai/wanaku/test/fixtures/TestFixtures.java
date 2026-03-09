package ai.wanaku.test.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for loading test fixture directories from the classpath into a temporary directory.
 *
 * <p>Fixtures are expected to reside under {@code src/test/resources/fixtures/} on the classpath.
 * Each fixture is a named subdirectory containing one or more files that represent test data
 * (configuration files, JSON payloads, etc.).</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * // Simple copy
 * Path fixtureDir = TestFixtures.load("my-fixture", tempDir);
 *
 * // Copy with variable substitution
 * Path fixtureDir = TestFixtures.load("my-fixture", tempDir, Map.of("JDBC_URL", jdbcUrl));
 * </pre>
 */
public final class TestFixtures {

    private static final Logger LOG = LoggerFactory.getLogger(TestFixtures.class);

    private TestFixtures() {
        // Utility class
    }

    /**
     * Loads a fixture directory from the classpath into the given temporary directory.
     *
     * <p>All files under {@code fixtures/{fixtureName}/} on the classpath are copied
     * to {@code tempDir/{fixtureName}/}, preserving the directory structure.</p>
     *
     * @param fixtureName the name of the fixture directory (e.g., "my-fixture")
     * @param tempDir     the temporary directory to copy files into
     * @return the path to the copied fixture directory ({@code tempDir/{fixtureName}/})
     * @throws IllegalArgumentException if the fixture directory is not found on the classpath
     * @throws UncheckedIOException     if an I/O error occurs during copying
     */
    public static Path load(String fixtureName, Path tempDir) {
        return load(fixtureName, tempDir, Collections.emptyMap());
    }

    /**
     * Loads a fixture directory from the classpath into the given temporary directory,
     * replacing {@code ${VAR}} placeholders in each file with values from the provided map.
     *
     * <p>All files under {@code fixtures/{fixtureName}/} on the classpath are copied
     * to {@code tempDir/{fixtureName}/}. After copying, each regular file is read and any
     * occurrences of {@code ${KEY}} are replaced with the corresponding value from {@code vars}.</p>
     *
     * @param fixtureName the name of the fixture directory (e.g., "my-fixture")
     * @param tempDir     the temporary directory to copy files into
     * @param vars        a map of variable names to replacement values (e.g., "JDBC_URL" -> "jdbc:...")
     * @return the path to the copied fixture directory ({@code tempDir/{fixtureName}/})
     * @throws IllegalArgumentException if the fixture directory is not found on the classpath
     * @throws UncheckedIOException     if an I/O error occurs during copying or substitution
     */
    public static Path load(String fixtureName, Path tempDir, Map<String, String> vars) {
        String resourcePath = "fixtures/" + fixtureName;
        URL resourceUrl = TestFixtures.class.getClassLoader().getResource(resourcePath);

        if (resourceUrl == null) {
            throw new IllegalArgumentException("Fixture directory not found on classpath: " + resourcePath
                    + ". Ensure the fixture exists under src/test/resources/fixtures/" + fixtureName + "/");
        }

        Path destDir = tempDir.resolve(fixtureName);
        LOG.debug("Loading fixture '{}' from {} to {}", fixtureName, resourceUrl, destDir);

        try {
            copyFixtureFiles(resourceUrl, resourcePath, destDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy fixture directory: " + fixtureName, e);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid fixture resource URI: " + resourceUrl, e);
        }

        if (!vars.isEmpty()) {
            substituteVariables(destDir, vars);
        }

        LOG.debug("Fixture '{}' loaded successfully to {}", fixtureName, destDir);
        return destDir;
    }

    /**
     * Copies fixture files from the classpath resource to the destination directory.
     */
    private static void copyFixtureFiles(URL resourceUrl, String resourcePath, Path destDir)
            throws IOException, URISyntaxException {
        URI uri = resourceUrl.toURI();

        if ("jar".equals(uri.getScheme())) {
            // Resource is inside a JAR file
            try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                Path sourcePath = fs.getPath(resourcePath);
                copyDirectory(sourcePath, destDir);
            }
        } else {
            // Resource is on the filesystem
            Path sourcePath = Path.of(uri);
            copyDirectory(sourcePath, destDir);
        }
    }

    /**
     * Recursively copies all files from the source directory to the destination directory.
     */
    private static void copyDirectory(Path sourceDir, Path destDir) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            paths.forEach(source -> {
                Path relative = sourceDir.relativize(source);
                Path destination = destDir.resolve(relative.toString());
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                        LOG.trace("Copied fixture file: {}", destination);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to copy fixture file: " + source, e);
                }
            });
        }
    }

    /**
     * Walks the destination directory and replaces {@code ${VAR}} placeholders in each regular file.
     */
    private static void substituteVariables(Path destDir, Map<String, String> vars) {
        try (Stream<Path> paths = Files.walk(destDir)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    String original = content;

                    for (Map.Entry<String, String> entry : vars.entrySet()) {
                        String placeholder = "${" + entry.getKey() + "}";
                        content = content.replace(placeholder, entry.getValue());
                    }

                    if (!content.equals(original)) {
                        Files.writeString(file, content, StandardCharsets.UTF_8);
                        LOG.trace("Substituted variables in: {}", file);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to substitute variables in file: " + file, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk fixture directory for substitution: " + destDir, e);
        }
    }
}
