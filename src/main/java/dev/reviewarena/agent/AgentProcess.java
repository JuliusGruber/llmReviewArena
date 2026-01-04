package dev.reviewarena.agent;

import dev.reviewarena.config.DockerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Manages a single agent process lifecycle.
 *
 * <p>Handles:
 * <ul>
 *   <li>Process spawning via ProcessBuilder</li>
 *   <li>stdout/stderr capture to log files</li>
 *   <li>Timeout enforcement with graceful termination</li>
 *   <li>Output validation</li>
 * </ul>
 *
 * <p>Implements {@link AutoCloseable} to ensure proper cleanup of process resources,
 * especially important on Windows where file handles may remain locked if not
 * explicitly closed.
 */
public class AgentProcess implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentProcess.class);

    private final String agentName;
    private final int round;
    private final List<String> command;
    private final Path workingDir;
    private final Path outputFile;
    private final Path promptFile;  // Optional: if set, redirects stdin from this file
    private final Path stdoutLog;
    private final Path stderrLog;
    private final long timeoutMs;
    private final long gracePeriodMs;
    private final OutputValidator outputValidator;
    private final boolean showOutput;
    private final DockerConfig dockerConfig;
    private final DockerCommandBuilder dockerCommandBuilder;

    private Process process;
    private Instant startTime;
    private Thread stdinThread;
    private volatile boolean closed = false;

    private AgentProcess(Builder builder) {
        this.agentName = Objects.requireNonNull(builder.agentName);
        this.round = builder.round;
        this.command = List.copyOf(Objects.requireNonNull(builder.command));
        this.workingDir = Objects.requireNonNull(builder.workingDir);
        this.outputFile = Objects.requireNonNull(builder.outputFile);
        this.promptFile = builder.promptFile;  // May be null
        this.stdoutLog = Objects.requireNonNull(builder.stdoutLog);
        this.stderrLog = Objects.requireNonNull(builder.stderrLog);
        this.timeoutMs = builder.timeoutMs;
        this.gracePeriodMs = builder.gracePeriodMs;
        this.outputValidator = Objects.requireNonNull(builder.outputValidator);
        this.showOutput = builder.showOutput;
        this.dockerConfig = Objects.requireNonNull(builder.dockerConfig);
        this.dockerCommandBuilder = new DockerCommandBuilder();
    }

    /**
     * Executes the agent process and waits for completion or timeout.
     *
     * <p>The prompt file (if specified) is read into memory before starting the process,
     * ensuring the file handle is closed immediately. This prevents file locking issues
     * on Windows when the process crashes or exits quickly.
     *
     * @return the execution result
     */
    public AgentResult execute() {
        log.info("Starting agent '{}' for round {}", agentName, round);
        log.debug("Command: {}", command);
        log.debug("Working directory: {}", workingDir);

        startTime = Instant.now();

        try {
            // Read prompt file into memory BEFORE starting process.
            // This ensures the file handle is closed immediately, preventing file locking
            // issues on Windows when the process crashes or exits quickly.
            byte[] promptContent = null;
            if (promptFile != null) {
                log.debug("Reading prompt file into memory: {}", promptFile);
                promptContent = Files.readAllBytes(promptFile);
                log.debug("Prompt file read: {} bytes", promptContent.length);
                // File handle is now CLOSED - no risk of locking
            }

            // On Windows, wrap command with cmd /c to resolve .cmd/.bat files in PATH
            List<String> effectiveCommand = resolveCommand(command);
            log.debug("Effective command: {}", effectiveCommand);

            ProcessBuilder pb = new ProcessBuilder(effectiveCommand)
                .directory(workingDir.toFile());

            // NOTE: We do NOT use pb.redirectInput(file) because that holds a file handle
            // that may not be released if the process crashes quickly (causes file locking on Windows)

            process = pb.start();

            // Pipe prompt content to stdin from memory (no file handle held)
            if (promptContent != null) {
                stdinThread = startStdinPipe(promptContent);
            }

            // Start background threads to capture output
            Thread stdoutThread = startStreamDrain(process.getInputStream(), stdoutLog, "stdout");
            Thread stderrThread = startStreamDrain(process.getErrorStream(), stderrLog, "stderr");

            // Wait for process with timeout
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!completed) {
                return handleTimeout();
            }

            // Wait for stream threads to finish
            if (stdinThread != null) {
                stdinThread.join(1000);
            }
            stdoutThread.join(1000);
            stderrThread.join(1000);

            return handleCompletion();

        } catch (IOException e) {
            log.error("Failed to start agent '{}': {}", agentName, e.getMessage());
            return AgentResult.failed(agentName, round, -1, getDurationMs(),
                "Failed to start process: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminateProcess();
            return AgentResult.failed(agentName, round, -1, getDurationMs(),
                "Execution interrupted");
        } finally {
            // Ensure all resources are cleaned up, even on unexpected exceptions
            close();
        }
    }

    private AgentResult handleTimeout() {
        log.warn("Agent '{}' timed out after {}ms, initiating graceful shutdown",
            agentName, timeoutMs);

        // Capture descendants BEFORE destroying the main process, because once
        // the parent is killed, child processes become orphans and are no longer
        // visible via process.descendants()
        List<ProcessHandle> descendants = process.descendants().toList();
        if (!descendants.isEmpty()) {
            log.debug("Found {} descendant processes to terminate", descendants.size());
        }

        // Step 1: Request graceful termination
        process.destroy();

        try {
            // Step 2: Wait for grace period
            boolean exited = process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS);

            if (!exited) {
                // Step 3: Force kill main process
                log.warn("Agent '{}' did not terminate gracefully, force killing", agentName);
                process.destroyForcibly();
                process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS);
            }

            // Step 4: Kill all descendants (on Windows, destroy() only kills the immediate
            // process like cmd.exe, but child processes like powershell.exe continue running)
            destroyDescendants(descendants);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            destroyDescendants(descendants);
        }

        return AgentResult.timeout(agentName, round, getDurationMs());
    }

    private AgentResult handleCompletion() {
        int exitCode = process.exitValue();
        long duration = getDurationMs();

        if (exitCode != 0) {
            log.error("Agent '{}' exited with code {}", agentName, exitCode);
            return AgentResult.failed(agentName, round, exitCode, duration,
                "Process exited with code " + exitCode);
        }

        // Validate output
        OutputValidator.ValidationResult validation = outputValidator.validate(outputFile);

        if (!validation.valid()) {
            log.error("Agent '{}' produced invalid output: {}", agentName, validation.errorMessage());
            return AgentResult.invalidOutput(agentName, round, exitCode, duration,
                validation.errorMessage());
        }

        log.info("Agent '{}' completed successfully in {}ms", agentName, duration);
        return AgentResult.success(agentName, round, exitCode, duration, outputFile);
    }

    private void terminateProcess() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS)) {
                    destroyProcessTree();
                }
            } catch (InterruptedException e) {
                destroyProcessTree();
            }
        }
    }

    /**
     * Destroys a list of descendant processes.
     * This is essential on Windows where killing a parent process (e.g., cmd.exe)
     * does not automatically kill child processes (e.g., powershell.exe).
     */
    private void destroyDescendants(List<ProcessHandle> descendants) {
        for (ProcessHandle ph : descendants) {
            if (ph.isAlive()) {
                log.debug("Force killing descendant process: PID {}", ph.pid());
                ph.destroyForcibly();
            }
        }

        // On Windows, wait a bit for file handles to be released after process termination
        if (isWindows() && !descendants.isEmpty()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Destroys the entire process tree (parent and all descendants).
     * This is essential on Windows where killing a parent process (e.g., cmd.exe)
     * does not automatically kill child processes (e.g., powershell.exe).
     */
    private void destroyProcessTree() {
        if (process == null) {
            return;
        }
        // Capture and kill all descendants first
        List<ProcessHandle> descendants = process.descendants().toList();
        destroyDescendants(descendants);
        // Then kill the main process
        process.destroyForcibly();
    }

    private Thread startStreamDrain(InputStream stream, Path logFile, String name) {
        return Thread.ofVirtual()
            .name(agentName + "-" + name + "-drain")
            .start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                     BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();

                        // Print to console with agent prefix if enabled
                        if (showOutput) {
                            log.info("[{}] {}", agentName, line);
                        }
                        log.debug("[{}:{}] {}", agentName, name, line);
                    }

                } catch (IOException e) {
                    log.warn("Error draining {} for agent '{}': {}", name, agentName, e.getMessage());
                }
            });
    }

    /**
     * Pipes prompt content from memory to the process's stdin.
     *
     * <p>This approach avoids file handle leaks because the prompt file was already
     * read into memory and its handle closed before this method is called.
     *
     * @param promptContent the prompt content to pipe
     * @return the thread performing the piping
     */
    private Thread startStdinPipe(byte[] promptContent) {
        return Thread.ofVirtual()
            .name(agentName + "-stdin-pipe")
            .start(() -> {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(promptContent);
                    stdin.flush();
                } catch (IOException e) {
                    // Process may have exited before we could write - this is normal for quick failures
                    log.debug("Error piping stdin for agent '{}': {}", agentName, e.getMessage());
                }
            });
    }

    private long getDurationMs() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    /**
     * Closes this agent process and releases all associated resources.
     *
     * <p>This method ensures:
     * <ul>
     *   <li>The process is terminated if still running</li>
     *   <li>All process streams (stdin, stdout, stderr) are closed</li>
     *   <li>The stdin piping thread is interrupted if still running</li>
     * </ul>
     *
     * <p>This method is idempotent - calling it multiple times has no additional effect.
     * It is automatically called by {@link #execute()} in a finally block.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        log.debug("Closing agent process resources for '{}'", agentName);

        // Interrupt stdin thread if still running
        if (stdinThread != null && stdinThread.isAlive()) {
            stdinThread.interrupt();
        }

        // Terminate process if still running
        if (process != null) {
            // Close all process streams explicitly
            closeQuietly(process.getOutputStream());  // stdin
            closeQuietly(process.getInputStream());   // stdout
            closeQuietly(process.getErrorStream());   // stderr

            // Destroy process tree if still alive
            if (process.isAlive()) {
                destroyProcessTree();
            }
        }
    }

    /**
     * Closes a Closeable without throwing exceptions.
     */
    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                log.trace("Error closing stream for agent '{}': {}", agentName, e.getMessage());
            }
        }
    }

    /**
     * Resolves command for execution, wrapping with Docker or Windows shell as needed.
     *
     * <p><b>Order matters:</b> Docker is checked FIRST because:
     * <ol>
     *   <li>docker.exe is a native Windows executable, not a .cmd/.bat script</li>
     *   <li>Docker commands must NOT be wrapped with cmd /c (would break argument parsing)</li>
     *   <li>The container handles all platform differences internally</li>
     * </ol>
     *
     * <p>Path translation: When Docker is enabled, this method passes the command
     * (with HOST paths from CommandBuilder) to DockerCommandBuilder, which translates
     * paths to container paths (/workspace/...).
     */
    private List<String> resolveCommand(List<String> command) {
        // IMPORTANT: Check Docker FIRST - docker.exe is native and must NOT be wrapped with cmd /c
        if (dockerConfig.enabled()) {
            // DockerCommandBuilder handles path translation from host paths to container paths
            return dockerCommandBuilder.build(
                agentName,
                dockerConfig,
                command,  // Contains HOST paths from CommandBuilder
                workingDir
            );
        }

        // Windows cmd /c wrapping (only for non-Docker execution)
        // This is needed because CLI tools like 'claude', 'codex' are actually .cmd scripts
        if (isWindows()) {
            var result = new ArrayList<String>();
            result.add("cmd");
            result.add("/c");
            result.addAll(command);
            return result;
        }

        return command;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentName;
        private int round;
        private List<String> command;
        private Path workingDir;
        private Path outputFile;
        private Path promptFile;  // Optional stdin source
        private Path stdoutLog;
        private Path stderrLog;
        private long timeoutMs = 300_000; // 5 min default
        private long gracePeriodMs = 5_000; // 5 sec default
        private OutputValidator outputValidator;
        private boolean showOutput = true; // Default to showing output
        private DockerConfig dockerConfig;

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder round(int round) {
            this.round = round;
            return this;
        }

        public Builder command(List<String> command) {
            this.command = command;
            return this;
        }

        public Builder workingDir(Path workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        public Builder outputFile(Path outputFile) {
            this.outputFile = outputFile;
            return this;
        }

        /**
         * Sets the prompt file to redirect to stdin.
         * If set, the process will receive the file contents on stdin.
         */
        public Builder promptFile(Path promptFile) {
            this.promptFile = promptFile;
            return this;
        }

        public Builder stdoutLog(Path stdoutLog) {
            this.stdoutLog = stdoutLog;
            return this;
        }

        public Builder stderrLog(Path stderrLog) {
            this.stderrLog = stderrLog;
            return this;
        }

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder gracePeriodMs(long gracePeriodMs) {
            this.gracePeriodMs = gracePeriodMs;
            return this;
        }

        public Builder outputValidator(OutputValidator outputValidator) {
            this.outputValidator = outputValidator;
            return this;
        }

        /**
         * Sets whether to print agent output to console with prefixes.
         */
        public Builder showOutput(boolean showOutput) {
            this.showOutput = showOutput;
            return this;
        }

        /**
         * Sets the Docker configuration for this agent process.
         * Docker config is required and determines whether the agent runs in a container.
         */
        public Builder dockerConfig(DockerConfig dockerConfig) {
            this.dockerConfig = dockerConfig;
            return this;
        }

        public AgentProcess build() {
            // Explicit validation of required fields
            if (agentName == null || agentName.isBlank()) {
                throw new IllegalStateException("agentName is required");
            }
            if (command == null || command.isEmpty()) {
                throw new IllegalStateException("command is required");
            }
            if (workingDir == null) {
                throw new IllegalStateException("workingDir is required");
            }
            if (outputFile == null) {
                throw new IllegalStateException("outputFile is required");
            }
            if (stdoutLog == null) {
                throw new IllegalStateException("stdoutLog is required");
            }
            if (stderrLog == null) {
                throw new IllegalStateException("stderrLog is required");
            }
            if (outputValidator == null) {
                throw new IllegalStateException("outputValidator is required");
            }
            if (timeoutMs <= 0) {
                throw new IllegalStateException("timeoutMs must be positive");
            }
            if (gracePeriodMs < 0) {
                throw new IllegalStateException("gracePeriodMs must be non-negative");
            }
            if (dockerConfig == null) {
                throw new IllegalStateException("dockerConfig is required");
            }
            return new AgentProcess(this);
        }
    }
}
