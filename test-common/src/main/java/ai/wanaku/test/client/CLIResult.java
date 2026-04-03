package ai.wanaku.test.client;

import java.time.Duration;

/**
 * Result of a CLI command execution.
 */
public class CLIResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final Duration duration;

    /**
     * Creates a new CLI result.
     *
     * @param exitCode the process exit code
     * @param stdout   the standard output
     * @param stderr   the standard error output
     * @param duration the execution duration
     */
    public CLIResult(int exitCode, String stdout, String stderr, Duration duration) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.duration = duration;
    }

    /**
     * Gets the process exit code.
     *
     * @return the exit code
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Checks if the command executed successfully (exit code 0).
     *
     * @return true if exit code is 0
     */
    public boolean isSuccess() {
        return exitCode == 0;
    }

    /**
     * Gets the combined output (stdout + stderr).
     *
     * @return the combined output
     */
    public String getCombinedOutput() {
        if (stderr == null || stderr.isEmpty()) {
            return stdout;
        }
        return stdout + "\n" + stderr;
    }

    @Override
    public String toString() {
        return "CLIResult{" + "exitCode="
                + exitCode + ", duration="
                + duration.toMillis() + "ms" + ", stdout='"
                + (stdout.length() > 100 ? stdout.substring(0, 100) + "..." : stdout) + '\'' + '}';
    }
}
