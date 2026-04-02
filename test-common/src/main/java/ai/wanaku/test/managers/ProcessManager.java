package ai.wanaku.test.managers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import ai.wanaku.test.utils.LogUtils;

/**
 * Base class for managing Java processes (Router, HTTP Tool Service).
 * Provides start/stop lifecycle management with graceful shutdown.
 */
public abstract class ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessManager.class);

    protected Process process;
    protected File logFile;
    protected ProcessState state = ProcessState.STOPPED;
    protected final Map<String, String> environment = new HashMap<>();
    protected final List<String> jvmArgs = new ArrayList<>();

    // Log context for structured logging
    protected String logProfile;
    protected String logTestClass;
    protected String logTestMethod;

    public enum ProcessState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    /**
     * Gets the name of this process manager for logging.
     */
    protected abstract String getProcessName();

    /**
     * Gets the path to the JAR file to run.
     */
    protected abstract Path getJarPath();

    /**
     * Gets the command line arguments for the process.
     */
    protected abstract List<String> getProcessArguments();

    /**
     * Performs health check after process starts.
     * @return true if the process is healthy
     */
    protected abstract boolean performHealthCheck();

    /**
     * Sets the log context for structured log file creation.
     *
     * @param profile    the capability profile (e.g., "http-capability")
     * @param testClass  the test class name
     * @param testMethod the test method name
     */
    public void setLogContext(String profile, String testClass, String testMethod) {
        this.logProfile = profile;
        this.logTestClass = testClass;
        this.logTestMethod = testMethod;
    }

    /**
     * Starts the process.
     *
     * @param testName the name of the test for log file naming
     * @throws IOException if the process cannot be started
     */
    public void start(String testName) throws IOException {
        if (state != ProcessState.STOPPED) {
            throw new IllegalStateException("Process is already running: " + getProcessName());
        }

        Path jarPath = getJarPath();
        if (jarPath == null || !jarPath.toFile().exists()) {
            throw new IllegalStateException("JAR not found: " + jarPath);
        }

        state = ProcessState.STARTING;

        // Isolate all Wanaku data to target/ so mvn clean removes it.
        // Without this, processes write to ~/.wanaku/ which conflicts with
        // local Wanaku usage and causes stale data between runs.
        // Each property is used by different process types:
        //   - infinispan.base-folder: Router (Infinispan .dat files)
        //   - service-home: Capabilities (provisioning .properties files)
        // Unused properties are harmlessly ignored by the receiving process.
        Path dataDir = Path.of("target", "wanaku-data");
        addSystemProperty(
                "wanaku.persistence.infinispan.base-folder",
                dataDir.resolve("router").toAbsolutePath().toString());
        addSystemProperty(
                "wanaku.service.service-home",
                dataDir.resolve("services").toAbsolutePath().toString());

        LOG.debug("Starting {}", getProcessName());

        // Create log file
        logFile = createLogFile(testName);

        // Build command
        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(jvmArgs);
        command.add("-jar");

        // For Quarkus fast-jar format, we need to run from the directory containing quarkus-run.jar
        // Resolve to absolute path so relative paths work correctly in CI
        Path workingDir = jarPath.getParent().toAbsolutePath().normalize();
        String jarName = jarPath.getFileName().toString();
        command.add(jarName);
        command.addAll(getProcessArguments());

        LOG.debug("Working directory: {}", workingDir);
        LOG.debug("Command: {}", String.join(" ", command));

        // Start process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());

        if (!environment.isEmpty()) {
            pb.environment().putAll(environment);
        }

        pb.redirectOutput(logFile);
        pb.redirectErrorStream(true);

        process = pb.start();
        LOG.debug("{} started with PID: {}", getProcessName(), process.pid());

        // Wait for health check
        if (performHealthCheck()) {
            state = ProcessState.RUNNING;
            LOG.debug("{} is healthy", getProcessName());
        } else {
            stop();
            throw new IllegalStateException(
                    getProcessName() + " failed health check. Check logs: " + logFile.getAbsolutePath());
        }
    }

    /**
     * Stops the process with graceful shutdown.
     */
    public void stop() {
        if (process == null || !process.isAlive()) {
            state = ProcessState.STOPPED;
            return;
        }

        state = ProcessState.STOPPING;
        LOG.debug("Stopping {}", getProcessName());

        try {
            // Try graceful shutdown first (SIGTERM)
            process.destroy();

            boolean terminated =
                    process.waitFor(WanakuTestConstants.GRACEFUL_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            if (!terminated) {
                LOG.warn("{} did not stop gracefully, forcing shutdown", getProcessName());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            LOG.debug("{} stopped", getProcessName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("{} stop interrupted", getProcessName());
            process.destroyForcibly();
        } finally {
            state = ProcessState.STOPPED;
            process = null;
        }
    }

    /**
     * Checks if the process is running.
     */
    public boolean isRunning() {
        return process != null && process.isAlive() && state == ProcessState.RUNNING;
    }

    /**
     * Adds a system property as a JVM argument.
     */
    public void addSystemProperty(String key, String value) {
        jvmArgs.add("-D" + key + "=" + value);
    }

    /**
     * Creates a log file for this process.
     * Override in subclasses for custom log file locations.
     *
     * @param testName the test name
     * @return the log file
     * @throws IOException if file creation fails
     */
    protected File createLogFile(String testName) throws IOException {
        if (logProfile != null && logTestClass != null && logTestMethod != null) {
            return LogUtils.createCapabilityLogFile(logProfile, logTestClass, logTestMethod);
        }
        return LogUtils.createLogFile(testName, getProcessName());
    }
}
