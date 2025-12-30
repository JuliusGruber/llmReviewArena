package dev.reviewarena.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 */
public class AgentProcess {

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

    private Process process;
    private Instant startTime;

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
    }

    /**
     * Executes the agent process and waits for completion or timeout.
     *
     * @return the execution result
     */
    public AgentResult execute() {
        log.info("Starting agent '{}' for round {}", agentName, round);
        log.debug("Command: {}", command);
        log.debug("Working directory: {}", workingDir);

        startTime = Instant.now();

        try {
            // On Windows, wrap command with cmd /c to resolve .cmd/.bat files in PATH
            List<String> effectiveCommand = resolveCommand(command);
            log.debug("Effective command: {}", effectiveCommand);

            ProcessBuilder pb = new ProcessBuilder(effectiveCommand)
                .directory(workingDir.toFile());

            // Redirect stdin from prompt file if provided
            if (promptFile != null) {
                log.debug("Redirecting stdin from: {}", promptFile);
                pb.redirectInput(promptFile.toFile());
            }

            process = pb.start();

            // Start background threads to capture output
            Thread stdoutThread = startStreamDrain(process.getInputStream(), stdoutLog, "stdout");
            Thread stderrThread = startStreamDrain(process.getErrorStream(), stderrLog, "stderr");

            // Wait for process with timeout
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!completed) {
                return handleTimeout();
            }

            // Wait for stream threads to finish
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
                            synchronized (System.out) {
                                System.out.println("[" + agentName + "] " + line);
                            }
                        }
                        log.debug("[{}:{}] {}", agentName, name, line);
                    }

                } catch (IOException e) {
                    log.warn("Error draining {} for agent '{}': {}", name, agentName, e.getMessage());
                }
            });
    }

    private long getDurationMs() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    /**
     * Resolves command for Windows execution.
     * On Windows, commands like 'claude' are actually 'claude.cmd' scripts
     * that require cmd.exe to execute properly.
     */
    private List<String> resolveCommand(List<String> command) {
        if (!isWindows()) {
            return command;
        }

        // Wrap with cmd /c to resolve .cmd/.bat files in PATH
        var result = new java.util.ArrayList<String>();
        result.add("cmd");
        result.add("/c");
        result.addAll(command);
        return result;
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
            return new AgentProcess(this);
        }
    }
}
