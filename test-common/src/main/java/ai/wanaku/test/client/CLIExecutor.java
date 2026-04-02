package ai.wanaku.test.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;

/**
 * Utility for executing Wanaku CLI commands.
 */
public class CLIExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(CLIExecutor.class);

    private final String cliPath;
    private Duration timeout = Duration.ofSeconds(30);

    public CLIExecutor(String cliPath) {
        this.cliPath = cliPath;
    }

    /**
     * Creates a CLIExecutor with default CLI path from system properties.
     */
    public static CLIExecutor createDefault() {
        String cliPath = System.getProperty(WanakuTestConstants.PROP_CLI_PATH, WanakuTestConstants.DEFAULT_CLI_PATH);
        return new CLIExecutor(cliPath);
    }

    /**
     * Executes a CLI command.
     * Automatically detects the CLI type and runs it appropriately:
     * - JAR files: runs with "java -jar"
     * - Quarkus fast-jar (quarkus-run.jar): runs from parent directory
     * - Binaries: runs directly
     *
     * @param args command arguments (without the CLI binary)
     * @return the execution result
     */
    public CLIResult execute(String... args) {
        List<String> command = new ArrayList<>();
        Path effectiveWorkingDir = null;

        // Auto-detect CLI type
        if (cliPath.endsWith(".jar")) {
            command.add("java");
            command.add("-jar");

            // Quarkus fast-jar format: need to run from the directory containing quarkus-run.jar
            if (cliPath.endsWith("quarkus-run.jar")) {
                Path jarPath = Path.of(cliPath).toAbsolutePath().normalize();
                effectiveWorkingDir = jarPath.getParent();
                command.add(jarPath.getFileName().toString());
            } else {
                command.add(cliPath);
            }
        } else {
            // Binary - run directly
            command.add(cliPath);
        }

        command.addAll(List.of(args));

        // Append --plain for subcommands so output goes to stdout instead of /dev/tty
        if (args.length > 0 && !args[0].startsWith("-")) {
            command.add("--plain");
        }

        LOG.debug("Executing: {} (workdir: {})", String.join(" ", command), effectiveWorkingDir);
        long startTime = System.currentTimeMillis();

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (effectiveWorkingDir != null) {
                if (!effectiveWorkingDir.toFile().exists()) {
                    LOG.error("The effective working dir {} doesn't exist", effectiveWorkingDir);
                }

                pb.directory(effectiveWorkingDir.toFile());
            }
            Process process = pb.start();

            // Close subprocess stdin so JLine doesn't block reading from System.in
            process.getOutputStream().close();

            // Wait for process to complete first
            boolean completed = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);

            if (!completed) {
                process.destroyForcibly();
                return new CLIResult(-1, "", "Command timed out after " + timeout.toSeconds() + "s", duration);
            }

            int exitCode = process.exitValue();

            // Read stdout and stderr after process completes
            String stdout;
            String stderr;
            try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                stdout = stdoutReader.lines().collect(Collectors.joining("\n"));
                stderr = stderrReader.lines().collect(Collectors.joining("\n"));
            }
            LOG.debug("CLI completed with exit code {} in {}ms", exitCode, duration.toMillis());

            return new CLIResult(exitCode, stdout, stderr, duration);

        } catch (IOException e) {
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
            LOG.error("CLI execution failed: {}", e.getMessage());
            return new CLIResult(-1, "", "IOException: " + e.getMessage(), duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
            return new CLIResult(-1, "", "Interrupted", duration);
        }
    }

    /**
     * Checks if the CLI is available.
     * Note: Wanaku CLI may return exit code 1 for --version even when working correctly,
     * so we check for output presence (stdout or stderr) instead of just exit code.
     */
    public boolean isAvailable() {
        try {
            CLIResult result = execute("--version");
            // Exit code -1 indicates an error (IOException, timeout, etc.)
            if (result.getExitCode() == -1) {
                return false;
            }
            // CLI is available if it produces any output or exits successfully
            return !result.getCombinedOutput().isEmpty() || result.isSuccess();
        } catch (Exception e) {
            return false;
        }
    }
}
