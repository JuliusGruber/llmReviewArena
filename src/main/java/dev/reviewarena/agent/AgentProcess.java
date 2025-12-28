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
    private final Path stdoutLog;
    private final Path stderrLog;
    private final long timeoutMs;
    private final long gracePeriodMs;
    private final OutputValidator outputValidator;

    private Process process;
    private Instant startTime;

    private AgentProcess(Builder builder) {
        this.agentName = Objects.requireNonNull(builder.agentName);
        this.round = builder.round;
        this.command = List.copyOf(Objects.requireNonNull(builder.command));
        this.workingDir = Objects.requireNonNull(builder.workingDir);
        this.outputFile = Objects.requireNonNull(builder.outputFile);
        this.stdoutLog = Objects.requireNonNull(builder.stdoutLog);
        this.stderrLog = Objects.requireNonNull(builder.stderrLog);
        this.timeoutMs = builder.timeoutMs;
        this.gracePeriodMs = builder.gracePeriodMs;
        this.outputValidator = Objects.requireNonNull(builder.outputValidator);
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
            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir.toFile());

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

        // Step 1: Request graceful termination
        process.destroy();

        try {
            // Step 2: Wait for grace period
            boolean exited = process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS);

            if (!exited) {
                // Step 3: Force kill
                log.warn("Agent '{}' did not terminate gracefully, force killing", agentName);
                process.destroyForcibly();
                process.waitFor(1000, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
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
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
        }
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
        private Path stdoutLog;
        private Path stderrLog;
        private long timeoutMs = 300_000; // 5 min default
        private long gracePeriodMs = 5_000; // 5 sec default
        private OutputValidator outputValidator;

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
