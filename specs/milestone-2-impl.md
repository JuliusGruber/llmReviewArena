# Milestone 2: Agent Process Layer - Implementation Guide

## Implementation Order

```
Step 1: AgentResult          ──┐
Step 2: OutputValidator      ──┼── Independent, can be parallel
Step 3: Mock Agents          ──┘
Step 4: CommandBuilder       ←── Depends on AgentConfig (exists)
Step 5: AgentProcess         ←── Depends on Steps 1, 2, 4
Step 6: AgentExecutor        ←── Depends on Step 5
Step 7: Integration Tests    ←── Depends on all above
Step 8: CLI Integration      ←── Wire into ReviewArenaCli
```

---

## Step 1: AgentResult

**File**: `src/main/java/dev/reviewarena/agent/AgentResult.java`

```java
package dev.reviewarena.agent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable result of a single agent execution.
 */
public record AgentResult(
    String agentName,
    int round,
    Status status,
    int exitCode,
    long durationMs,
    Path outputFile,
    String failureReason
) {
    public enum Status {
        SUCCESS,
        FAILED,
        TIMEOUT,
        INVALID_OUTPUT
    }

    public AgentResult {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (round < 0) {
            throw new IllegalArgumentException("round must be non-negative");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    // Factory methods
    public static AgentResult success(String agentName, int round, int exitCode,
                                       long durationMs, Path outputFile) {
        return new AgentResult(agentName, round, Status.SUCCESS, exitCode,
                               durationMs, outputFile, null);
    }

    public static AgentResult failed(String agentName, int round, int exitCode,
                                      long durationMs, String reason) {
        return new AgentResult(agentName, round, Status.FAILED, exitCode,
                               durationMs, null, reason);
    }

    public static AgentResult timeout(String agentName, int round, long durationMs) {
        return new AgentResult(agentName, round, Status.TIMEOUT, -1,
                               durationMs, null, "Process timed out");
    }

    public static AgentResult invalidOutput(String agentName, int round, int exitCode,
                                             long durationMs, String reason) {
        return new AgentResult(agentName, round, Status.INVALID_OUTPUT, exitCode,
                               durationMs, null, reason);
    }
}
```

**Test file**: `src/test/java/dev/reviewarena/agent/AgentResultTest.java`

```java
package dev.reviewarena.agent;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AgentResultTest {

    @Test
    void success_createsSuccessResult() {
        Path output = Path.of("/test/review.md");
        AgentResult result = AgentResult.success("claude", 0, 0, 1000, output);

        assertEquals("claude", result.agentName());
        assertEquals(0, result.round());
        assertEquals(AgentResult.Status.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertEquals(1000, result.durationMs());
        assertEquals(output, result.outputFile());
        assertNull(result.failureReason());
        assertTrue(result.isSuccess());
    }

    @Test
    void failed_createsFailedResult() {
        AgentResult result = AgentResult.failed("codex", 1, 1, 500, "crash");

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertEquals("crash", result.failureReason());
        assertNull(result.outputFile());
        assertFalse(result.isSuccess());
    }

    @Test
    void timeout_createsTimeoutResult() {
        AgentResult result = AgentResult.timeout("gemini", 2, 30000);

        assertEquals(AgentResult.Status.TIMEOUT, result.status());
        assertEquals(-1, result.exitCode());
        assertEquals("Process timed out", result.failureReason());
        assertFalse(result.isSuccess());
    }

    @Test
    void invalidOutput_createsInvalidOutputResult() {
        AgentResult result = AgentResult.invalidOutput("claude", 0, 0, 100, "empty");

        assertEquals(AgentResult.Status.INVALID_OUTPUT, result.status());
        assertFalse(result.isSuccess());
    }

    @Test
    void constructor_rejectsNullAgentName() {
        assertThrows(NullPointerException.class, () ->
            new AgentResult(null, 0, AgentResult.Status.SUCCESS, 0, 0, null, null));
    }

    @Test
    void constructor_rejectsNegativeRound() {
        assertThrows(IllegalArgumentException.class, () ->
            AgentResult.success("test", -1, 0, 0, null));
    }

    @Test
    void constructor_rejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () ->
            AgentResult.success("test", 0, 0, -1, null));
    }
}
```

---

## Step 2: OutputValidator

**File**: `src/main/java/dev/reviewarena/agent/OutputValidator.java`

```java
package dev.reviewarena.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates agent output files after execution.
 */
public class OutputValidator {

    private static final Logger log = LoggerFactory.getLogger(OutputValidator.class);

    private final int maxOutputSizeKb;

    public OutputValidator(int maxOutputSizeKb) {
        if (maxOutputSizeKb <= 0) {
            throw new IllegalArgumentException("maxOutputSizeKb must be positive");
        }
        this.maxOutputSizeKb = maxOutputSizeKb;
    }

    /**
     * Validates that the output file exists and is non-empty.
     *
     * @param outputFile path to the expected output file (review.md)
     * @return validation result
     */
    public ValidationResult validate(Path outputFile) {
        if (!Files.exists(outputFile)) {
            return ValidationResult.invalid("Output file does not exist: " + outputFile);
        }

        if (!Files.isRegularFile(outputFile)) {
            return ValidationResult.invalid("Output path is not a file: " + outputFile);
        }

        try {
            long sizeBytes = Files.size(outputFile);

            if (sizeBytes == 0) {
                return ValidationResult.invalid("Output file is empty: " + outputFile);
            }

            long sizeKb = sizeBytes / 1024;
            if (sizeKb > maxOutputSizeKb) {
                log.warn("Output exceeds size limit ({} KB > {} KB limit): {}",
                    sizeKb, maxOutputSizeKb, outputFile);
                // Warn but still valid per spec - no truncation
            }

            return ValidationResult.valid(outputFile);

        } catch (IOException e) {
            return ValidationResult.invalid("Failed to read output file: " + e.getMessage());
        }
    }

    /**
     * Result of output validation.
     */
    public record ValidationResult(boolean valid, String errorMessage, Path outputFile) {

        public static ValidationResult valid(Path file) {
            return new ValidationResult(true, null, file);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message, null);
        }
    }
}
```

**Test file**: `src/test/java/dev/reviewarena/agent/OutputValidatorTest.java`

```java
package dev.reviewarena.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OutputValidatorTest {

    @TempDir
    Path tempDir;

    private OutputValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OutputValidator(500); // 500 KB limit
    }

    @Test
    void validate_validFile_returnsValid() throws IOException {
        Path file = tempDir.resolve("review.md");
        Files.writeString(file, "# Review\nThis is a valid review.");

        var result = validator.validate(file);

        assertTrue(result.valid());
        assertNull(result.errorMessage());
        assertEquals(file, result.outputFile());
    }

    @Test
    void validate_missingFile_returnsInvalid() {
        Path file = tempDir.resolve("nonexistent.md");

        var result = validator.validate(file);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("does not exist"));
        assertNull(result.outputFile());
    }

    @Test
    void validate_emptyFile_returnsInvalid() throws IOException {
        Path file = tempDir.resolve("empty.md");
        Files.createFile(file);

        var result = validator.validate(file);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("empty"));
    }

    @Test
    void validate_directory_returnsInvalid() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectory(dir);

        var result = validator.validate(dir);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("not a file"));
    }

    @Test
    void validate_largeFile_warnsButReturnsValid() throws IOException {
        Path file = tempDir.resolve("large.md");
        // Create file larger than 500 KB
        byte[] content = new byte[600 * 1024];
        Files.write(file, content);

        var result = validator.validate(file);

        assertTrue(result.valid()); // Still valid, just warns
        assertEquals(file, result.outputFile());
    }

    @Test
    void constructor_rejectsZeroSize() {
        assertThrows(IllegalArgumentException.class, () -> new OutputValidator(0));
    }

    @Test
    void constructor_rejectsNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> new OutputValidator(-1));
    }
}
```

---

## Step 3: Mock Agents

Create test resources for simulating agent behavior.

**Directory**: `src/test/resources/mock-agents/`

**File**: `src/test/resources/mock-agents/success-agent.sh`
```bash
#!/bin/bash
# Mock agent that writes a valid review
# Usage: success-agent.sh -p <prompt> -o <output-file>
# The @output placeholder in command is replaced with actual output path

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -o) OUTPUT_FILE="$2"; shift 2 ;;
        -p) shift 2 ;;  # Ignore prompt file
        *) shift ;;
    esac
done

# Ensure parent directory exists
mkdir -p "$(dirname "$OUTPUT_FILE")"

cat > "$OUTPUT_FILE" << 'EOF'
# Summary
Mock review generated successfully.

## High-risk issues (must fix)
None identified in this mock review.

## Medium / low-risk issues
- Example issue for testing

## Suggested patches
No patches suggested.

## Test suggestions
Add tests for the mock functionality.

## Questions for the author
None.
EOF

exit 0
```

**File**: `src/test/resources/mock-agents/success-agent.bat`
```batch
@echo off
REM Mock agent that writes a valid review
REM Usage: success-agent.bat -p <prompt> -o <output-file>
REM The @output placeholder in command is replaced with actual output path

setlocal enabledelayedexpansion

REM Parse arguments
:parse
if "%~1"=="" goto :endparse
if "%~1"=="-o" (
    set "OUTPUT_FILE=%~2"
    shift
    shift
    goto :parse
)
if "%~1"=="-p" (
    shift
    shift
    goto :parse
)
shift
goto :parse
:endparse

REM Create parent directory if needed
for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul

(
echo # Summary
echo Mock review generated successfully.
echo.
echo ## High-risk issues ^(must fix^)
echo None identified in this mock review.
echo.
echo ## Medium / low-risk issues
echo - Example issue for testing
echo.
echo ## Suggested patches
echo No patches suggested.
echo.
echo ## Test suggestions
echo Add tests for the mock functionality.
echo.
echo ## Questions for the author
echo None.
) > "%OUTPUT_FILE%"

exit /b 0
```

**File**: `src/test/resources/mock-agents/slow-agent.sh`
```bash
#!/bin/bash
# Mock agent that takes too long (for timeout testing)
# Usage: slow-agent.sh <output-dir>
sleep 30
exit 0
```

**File**: `src/test/resources/mock-agents/slow-agent.bat`
```batch
@echo off
REM Mock agent that takes too long (for timeout testing)
timeout /t 30 /nobreak > nul
exit /b 0
```

**File**: `src/test/resources/mock-agents/failing-agent.sh`
```bash
#!/bin/bash
# Mock agent that crashes
echo "Simulated agent error" >&2
exit 1
```

**File**: `src/test/resources/mock-agents/failing-agent.bat`
```batch
@echo off
echo Simulated agent error 1>&2
exit /b 1
```

**File**: `src/test/resources/mock-agents/empty-output-agent.sh`
```bash
#!/bin/bash
# Mock agent that creates empty output
# Usage: empty-output-agent.sh -p <prompt> -o <output-file>

while [[ $# -gt 0 ]]; do
    case $1 in
        -o) OUTPUT_FILE="$2"; shift 2 ;;
        *) shift ;;
    esac
done

mkdir -p "$(dirname "$OUTPUT_FILE")"
touch "$OUTPUT_FILE"
exit 0
```

**File**: `src/test/resources/mock-agents/empty-output-agent.bat`
```batch
@echo off
REM Mock agent that creates empty output
REM Usage: empty-output-agent.bat -p <prompt> -o <output-file>

:parse
if "%~1"=="" goto :endparse
if "%~1"=="-o" (
    set "OUTPUT_FILE=%~2"
    shift
    shift
    goto :parse
)
shift
goto :parse
:endparse

for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul
type nul > "%OUTPUT_FILE%"
exit /b 0
```

---

## Step 4: CommandBuilder

**File**: `src/main/java/dev/reviewarena/agent/CommandBuilder.java`

```java
package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds CLI commands for agent execution with flag translation.
 *
 * <p>Translates portable configuration flags to CLI-specific arguments:
 * <ul>
 *   <li>auto-approve: claude → --dangerously-skip-permissions, codex → --full-auto, gemini → --yolo</li>
 *   <li>allowed-tools: claude only → --allowedTools</li>
 * </ul>
 *
 * <p>Supports placeholders in command templates:
 * <ul>
 *   <li>{@code @prompt.md} → replaced with absolute path to prompt file</li>
 *   <li>{@code @output} → replaced with absolute path to output file (review.md)</li>
 * </ul>
 */
public class CommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(CommandBuilder.class);
    private static final String PROMPT_PLACEHOLDER = "@prompt.md";
    private static final String OUTPUT_PLACEHOLDER = "@output";

    /**
     * Builds the complete command for spawning an agent.
     *
     * @param agentConfig the agent configuration
     * @param promptFile  absolute path to the prompt file
     * @param outputFile  absolute path to the agent's output file (review.md)
     * @return immutable list of command arguments
     */
    public List<String> build(AgentConfig agentConfig, Path promptFile, Path outputFile) {
        List<String> command = new ArrayList<>(agentConfig.command());

        // Replace placeholders with actual paths
        replacePlaceholders(command, promptFile, outputFile);

        // Add translated flags based on agent name
        addTranslatedFlags(command, agentConfig);

        log.debug("Built command for {}: {}", agentConfig.name(), command);

        return List.copyOf(command);
    }

    private void replacePlaceholders(List<String> command, Path promptFile, Path outputFile) {
        String promptPath = promptFile.toAbsolutePath().toString();
        String outputPath = outputFile.toAbsolutePath().toString();

        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.contains(PROMPT_PLACEHOLDER)) {
                arg = arg.replace(PROMPT_PLACEHOLDER, promptPath);
            }
            if (arg.contains(OUTPUT_PLACEHOLDER)) {
                arg = arg.replace(OUTPUT_PLACEHOLDER, outputPath);
            }
            command.set(i, arg);
        }
    }

    private void addTranslatedFlags(List<String> command, AgentConfig config) {
        Map<String, Object> flags = config.flags();
        String agentName = config.name().toLowerCase();

        // Translate auto-approve flag
        if (Boolean.TRUE.equals(flags.get("auto-approve"))) {
            String autoApproveFlag = switch (agentName) {
                case "claude" -> "--dangerously-skip-permissions";
                case "codex" -> "--full-auto";
                case "gemini" -> "--yolo";
                default -> null;
            };
            if (autoApproveFlag != null && !command.contains(autoApproveFlag)) {
                command.add(autoApproveFlag);
            }
        }

        // Translate allowed-tools flag (Claude only)
        if ("claude".equals(agentName) && flags.containsKey("allowed-tools")) {
            Object allowedTools = flags.get("allowed-tools");
            if (allowedTools instanceof List<?> toolList && !toolList.isEmpty()) {
                String toolsCsv = toolList.stream()
                    .map(Object::toString)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
                if (!toolsCsv.isEmpty()) {
                    command.add("--allowedTools");
                    command.add(toolsCsv);
                }
            }
        }
    }
}
```

**Test file**: `src/test/java/dev/reviewarena/agent/CommandBuilderTest.java`

```java
package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandBuilderTest {

    private CommandBuilder builder;
    private Path promptFile;
    private Path outputFile;

    @BeforeEach
    void setUp() {
        builder = new CommandBuilder();
        promptFile = Path.of("/workspace/.arena/prompts/round-0-claude.md");
        outputFile = Path.of("/workspace/.arena/rounds/round-0/claude/review.md");
    }

    @Test
    void build_replacesPromptPlaceholder() {
        AgentConfig config = new AgentConfig("test",
            List.of("agent", "-p", "@prompt.md"), Map.of(), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains(promptFile.toAbsolutePath().toString()));
        assertFalse(command.contains("@prompt.md"));
    }

    @Test
    void build_replacesOutputPlaceholder() {
        AgentConfig config = new AgentConfig("test",
            List.of("agent", "-p", "@prompt.md", "-o", "@output"), Map.of(), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains(outputFile.toAbsolutePath().toString()));
        assertFalse(command.contains("@output"));
    }

    @Test
    void build_claude_addsAutoApproveFlag() {
        AgentConfig config = new AgentConfig("claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--dangerously-skip-permissions"));
    }

    @Test
    void build_codex_addsAutoApproveFlag() {
        AgentConfig config = new AgentConfig("codex",
            List.of("codex", "exec", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--full-auto"));
    }

    @Test
    void build_gemini_addsAutoApproveFlag() {
        AgentConfig config = new AgentConfig("gemini",
            List.of("gemini", "-p", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--yolo"));
    }

    @Test
    void build_claude_addsAllowedTools() {
        AgentConfig config = new AgentConfig("claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("allowed-tools", List.of("Read", "Write", "Edit")), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--allowedTools"));
        int idx = command.indexOf("--allowedTools");
        assertEquals("Read,Write,Edit", command.get(idx + 1));
    }

    @Test
    void build_nonClaude_ignoresAllowedTools() {
        AgentConfig config = new AgentConfig("codex",
            List.of("codex", "exec", "@prompt.md"),
            Map.of("allowed-tools", List.of("Read", "Write")), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertFalse(command.contains("--allowedTools"));
    }

    @Test
    void build_noAutoApprove_doesNotAddFlag() {
        AgentConfig config = new AgentConfig("claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", false), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertFalse(command.contains("--dangerously-skip-permissions"));
    }

    @Test
    void build_unknownAgent_noFlagTranslation() {
        AgentConfig config = new AgentConfig("custom-agent",
            List.of("custom", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        // No CLI-specific flags added for unknown agents
        assertFalse(command.contains("--dangerously-skip-permissions"));
        assertFalse(command.contains("--full-auto"));
        assertFalse(command.contains("--yolo"));
    }

    @Test
    void build_returnsImmutableList() {
        AgentConfig config = new AgentConfig("test",
            List.of("test", "@prompt.md"), Map.of(), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertThrows(UnsupportedOperationException.class, () -> command.add("extra"));
    }

    @Test
    void build_caseInsensitiveAgentName() {
        AgentConfig config = new AgentConfig("CLAUDE",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--dangerously-skip-permissions"));
    }
}
```

---

## Step 5: AgentProcess

**File**: `src/main/java/dev/reviewarena/agent/AgentProcess.java`

```java
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
            return new AgentProcess(this);
        }
    }
}
```

**Test file**: `src/test/java/dev/reviewarena/agent/AgentProcessTest.java`

```java
package dev.reviewarena.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentProcessTest {

    @TempDir
    Path tempDir;

    private Path outputDir;
    private Path outputFile;
    private Path stdoutLog;
    private Path stderrLog;
    private OutputValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        outputFile = outputDir.resolve("review.md");
        stdoutLog = outputDir.resolve("stdout.log");
        stderrLog = outputDir.resolve("stderr.log");
        validator = new OutputValidator(500);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_successfulProcess_windows() throws IOException {
        // Create a simple batch script that writes output
        Path script = tempDir.resolve("test.bat");
        Files.writeString(script, """
            @echo off
            echo # Review > "%1\\review.md"
            echo Content >> "%1\\review.md"
            """);

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("cmd", "/c", script.toString(), outputDir.toString()))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.durationMs() >= 0);
        assertEquals(outputFile, result.outputFile());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_successfulProcess_unix() throws IOException {
        // Create a simple shell script that writes output
        Path script = tempDir.resolve("test.sh");
        Files.writeString(script, """
            #!/bin/bash
            echo "# Review" > "$1/review.md"
            echo "Content" >> "$1/review.md"
            """);
        script.toFile().setExecutable(true);

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("bash", script.toString(), outputDir.toString()))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.durationMs() >= 0);
        assertEquals(outputFile, result.outputFile());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_failingProcess_windows() {
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("cmd", "/c", "exit", "1"))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertNotNull(result.failureReason());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_failingProcess_unix() {
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("bash", "-c", "exit 1"))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertNotNull(result.failureReason());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_timeout_windows() {
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("cmd", "/c", "timeout", "/t", "30", "/nobreak"))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(500)  // Very short timeout
            .gracePeriodMs(100)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.TIMEOUT, result.status());
        assertEquals(-1, result.exitCode());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_timeout_unix() {
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("sleep", "30"))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(500)  // Very short timeout
            .gracePeriodMs(100)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.TIMEOUT, result.status());
        assertEquals(-1, result.exitCode());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_invalidOutput_windows() throws IOException {
        // Create a batch script that creates an empty file
        Path script = tempDir.resolve("empty.bat");
        Files.writeString(script, "@echo off\r\ntype nul > \"" + outputFile.toString().replace("\\", "\\\\") + "\"");

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("cmd", "/c", script.toString()))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        // Either INVALID_OUTPUT (empty file) or FAILED (no file)
        assertFalse(result.isSuccess());
    }

    @Test
    void execute_capturesStdout() throws IOException {
        String testOutput = "Hello from agent";
        Path script;
        List<String> command;

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            script = tempDir.resolve("echo.bat");
            Files.writeString(script, "@echo " + testOutput + "\r\necho x > " + outputFile);
            command = List.of("cmd", "/c", script.toString());
        } else {
            script = tempDir.resolve("echo.sh");
            Files.writeString(script, "#!/bin/bash\necho '" + testOutput + "'\necho x > " + outputFile);
            script.toFile().setExecutable(true);
            command = List.of("bash", script.toString());
        }

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(command)
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        agent.execute();

        // Give time for stream drain
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        assertTrue(Files.exists(stdoutLog));
        String stdout = Files.readString(stdoutLog);
        assertTrue(stdout.contains(testOutput) || stdout.contains("Hello"));
    }
}
```

---

## Step 6: AgentExecutor

**File**: `src/main/java/dev/reviewarena/agent/AgentExecutor.java`

```java
package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.io.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Executes agents for tournament rounds with concurrency control.
 */
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final ArenaConfig config;
    private final WorkspaceManager workspace;
    private final CommandBuilder commandBuilder;
    private final OutputValidator outputValidator;

    public AgentExecutor(ArenaConfig config, WorkspaceManager workspace) {
        this.config = config;
        this.workspace = workspace;
        this.commandBuilder = new CommandBuilder();
        this.outputValidator = new OutputValidator(config.maxOutputSizeKb());
    }

    /**
     * Executes all enabled agents for a given round.
     *
     * @param round the round number (0-indexed)
     * @return map of agent name to execution result
     * @throws AgentException if round execution fails catastrophically
     */
    public Map<String, AgentResult> executeRound(int round) {
        List<AgentConfig> enabledAgents = getEnabledAgents();

        if (enabledAgents.isEmpty()) {
            log.warn("No enabled agents to execute for round {}", round);
            return Map.of();
        }

        log.info("Starting round {} with {} agents: {}",
            round, enabledAgents.size(),
            enabledAgents.stream().map(AgentConfig::name).toList());

        // Concurrency control: 0 = unlimited, else use semaphore
        Semaphore semaphore = config.maxConcurrent() > 0
            ? new Semaphore(config.maxConcurrent())
            : null;

        ConcurrentHashMap<String, AgentResult> results = new ConcurrentHashMap<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (AgentConfig agent : enabledAgents) {
                Future<?> future = executor.submit(() -> {
                    if (semaphore != null) {
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    try {
                        AgentResult result = executeAgent(agent, round);
                        results.put(agent.name(), result);
                        logResult(result);
                    } finally {
                        if (semaphore != null) {
                            semaphore.release();
                        }
                    }
                });
                futures.add(future);
            }

            // Wait for all agents with round timeout
            waitForAllWithTimeout(futures, config.roundTimeoutMs());

        } catch (Exception e) {
            log.error("Round {} execution failed: {}", round, e.getMessage());
            throw new AgentException("Round execution failed: " + e.getMessage(), e);
        }

        int successes = (int) results.values().stream().filter(AgentResult::isSuccess).count();
        log.info("Round {} complete: {}/{} agents succeeded", round, successes, enabledAgents.size());

        return Map.copyOf(results);
    }

    private AgentResult executeAgent(AgentConfig agentConfig, int round) {
        Path promptFile = workspace.getRoundPromptPath(round, agentConfig.name());
        Path agentDir = workspace.getAgentDir(round, agentConfig.name());
        Path outputFile = agentDir.resolve("review.md");

        List<String> command = commandBuilder.build(agentConfig, promptFile, outputFile);

        AgentProcess process = AgentProcess.builder()
            .agentName(agentConfig.name())
            .round(round)
            .command(command)
            .workingDir(workspace.getArenaDir().getParent()) // project root
            .outputFile(outputFile)
            .stdoutLog(agentDir.resolve("stdout.log"))
            .stderrLog(agentDir.resolve("stderr.log"))
            .timeoutMs(config.agentTimeoutMs())
            .gracePeriodMs(config.gracePeriodMs())
            .outputValidator(outputValidator)
            .build();

        return process.execute();
    }

    private void waitForAllWithTimeout(List<Future<?>> futures, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        for (Future<?> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("Round timeout reached, cancelling remaining agents");
                futures.forEach(f -> f.cancel(true));
                break;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Agent execution timed out during round");
                future.cancel(true);
            } catch (CancellationException e) {
                // Already cancelled
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                break;
            } catch (ExecutionException e) {
                log.error("Agent execution error: {}", e.getCause().getMessage());
            }
        }
    }

    private List<AgentConfig> getEnabledAgents() {
        return config.agents().values().stream()
            .filter(AgentConfig::enabled)
            .sorted(Comparator.comparing(AgentConfig::name)) // Alphabetical for determinism
            .toList();
    }

    private void logResult(AgentResult result) {
        switch (result.status()) {
            case SUCCESS -> log.info("Agent '{}' completed successfully in {}ms",
                result.agentName(), result.durationMs());
            case FAILED -> log.error("Agent '{}' failed in round {}: {}",
                result.agentName(), result.round(), result.failureReason());
            case TIMEOUT -> log.error("Agent '{}' timed out in round {} after {}ms",
                result.agentName(), result.round(), result.durationMs());
            case INVALID_OUTPUT -> log.error("Agent '{}' produced invalid output in round {}: {}",
                result.agentName(), result.round(), result.failureReason());
        }
    }

    /**
     * Gets the names of agents that succeeded in the given results.
     */
    public static Set<String> getSuccessfulAgents(Map<String, AgentResult> results) {
        return results.entrySet().stream()
            .filter(e -> e.getValue().isSuccess())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }
}
```

**Test file**: `src/test/java/dev/reviewarena/agent/AgentExecutorTest.java`

```java
package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.io.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutorTest {

    @TempDir
    Path tempDir;

    private ArenaConfig config;
    private WorkspaceManager workspace;

    @BeforeEach
    void setUp() throws IOException {
        // Create mock agent scripts
        createMockAgents();
    }

    private void createMockAgents() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Files.createDirectories(scriptsDir);

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Windows batch scripts
            Files.writeString(scriptsDir.resolve("success.bat"), """
                @echo off
                set OUTPUT=%~dp0..\\..\\rounds\\round-0\\success\\review.md
                echo # Mock Review > "%OUTPUT%"
                echo Content >> "%OUTPUT%"
                exit /b 0
                """);
        } else {
            // Unix shell scripts
            Path successScript = scriptsDir.resolve("success.sh");
            Files.writeString(successScript, """
                #!/bin/bash
                OUTPUT_DIR="$(dirname "$0")/../../rounds/round-0/success"
                mkdir -p "$OUTPUT_DIR"
                echo "# Mock Review" > "$OUTPUT_DIR/review.md"
                echo "Content" >> "$OUTPUT_DIR/review.md"
                exit 0
                """);
            successScript.toFile().setExecutable(true);
        }
    }

    @Test
    void executeRound_noEnabledAgents_returnsEmptyMap() {
        // All agents disabled
        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1", List.of("echo"), Map.of(), false)
        );
        config = createConfig(agents);
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize();

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertTrue(results.isEmpty());
    }

    @Test
    void getSuccessfulAgents_filtersCorrectly() {
        Map<String, AgentResult> results = Map.of(
            "success1", AgentResult.success("success1", 0, 0, 100, Path.of("/test")),
            "failed1", AgentResult.failed("failed1", 0, 1, 100, "error"),
            "success2", AgentResult.success("success2", 0, 0, 100, Path.of("/test2"))
        );

        var successful = AgentExecutor.getSuccessfulAgents(results);

        assertEquals(2, successful.size());
        assertTrue(successful.contains("success1"));
        assertTrue(successful.contains("success2"));
        assertFalse(successful.contains("failed1"));
    }

    private ArenaConfig createConfig(Map<String, AgentConfig> agents) {
        return new ArenaConfig(
            2, // maxRounds
            500, // maxOutputSizeKb
            0, // maxConcurrent (unlimited)
            30_000, // agentTimeoutMs
            90_000, // roundTimeoutMs
            1_000, // gracePeriodMs
            2, // minAgents
            Path.of(".arena"),
            agents
        );
    }
}
```

---

## Step 7: Integration Test

**File**: `src/test/java/dev/reviewarena/agent/AgentExecutorIT.java`

```java
package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.io.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AgentExecutor with real process execution.
 */
class AgentExecutorIT {

    @TempDir
    Path tempDir;

    private Path scriptsDir;

    @BeforeEach
    void setUp() throws IOException {
        scriptsDir = tempDir.resolve("scripts");
        Files.createDirectories(scriptsDir);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void executeRound_multipleAgents_unix() throws IOException {
        // Create success script
        Path successScript = scriptsDir.resolve("success.sh");
        Files.writeString(successScript, """
            #!/bin/bash
            AGENT_DIR="$2"
            echo "# Review from $1" > "$AGENT_DIR/review.md"
            echo "Content" >> "$AGENT_DIR/review.md"
            """);
        successScript.toFile().setExecutable(true);

        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1",
                List.of("bash", successScript.toString(), "agent1"), Map.of(), true),
            "agent2", new AgentConfig("agent2",
                List.of("bash", successScript.toString(), "agent2"), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 0); // unlimited concurrency
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize();

        // Fix command to include output dir
        // Re-create with proper commands that use workspace paths
        agents = Map.of(
            "agent1", new AgentConfig("agent1",
                List.of("bash", "-c",
                    "echo '# Review' > " + workspace.getAgentDir(0, "agent1").resolve("review.md")),
                Map.of(), true),
            "agent2", new AgentConfig("agent2",
                List.of("bash", "-c",
                    "echo '# Review' > " + workspace.getAgentDir(0, "agent2").resolve("review.md")),
                Map.of(), true)
        );
        config = createConfig(agents, 0);
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize();

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(2, results.size());
        // At least the execution completes without exception
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void executeRound_singleAgent_windows() throws IOException {
        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1",
                List.of("cmd", "/c", "echo # Review"), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 1);
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize();

        AgentExecutor executor = new AgentExecutor(config, workspace);

        // Just verify no exception - actual file creation depends on command
        assertDoesNotThrow(() -> executor.executeRound(0));
    }

    @Test
    void executeRound_respectsMaxConcurrent() throws IOException {
        // This test verifies semaphore behavior
        // Create agents that would fail (to quickly complete)
        Map<String, AgentConfig> agents = Map.of(
            "a1", new AgentConfig("a1", List.of("nonexistent-cmd"), Map.of(), true),
            "a2", new AgentConfig("a2", List.of("nonexistent-cmd"), Map.of(), true),
            "a3", new AgentConfig("a3", List.of("nonexistent-cmd"), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 1); // Sequential
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize();

        AgentExecutor executor = new AgentExecutor(config, workspace);

        // Should complete without deadlock
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(3, results.size());
        // All failed because command doesn't exist, but they ran
        results.values().forEach(r -> assertFalse(r.isSuccess()));
    }

    private ArenaConfig createConfig(Map<String, AgentConfig> agents, int maxConcurrent) {
        return new ArenaConfig(
            1, // maxRounds
            500, // maxOutputSizeKb
            maxConcurrent,
            10_000, // agentTimeoutMs
            30_000, // roundTimeoutMs
            1_000, // gracePeriodMs
            1, // minAgents
            Path.of(".arena"),
            agents
        );
    }
}
```

---

## Step 8: CLI Integration

**Update**: `src/main/java/dev/reviewarena/cli/ReviewArenaCli.java`

Add the agent executor integration after workspace initialization:

```java
// In ReviewArenaCli.call() method, replace the TODO comment:

// Initialize workspace
Path projectRoot = Path.of("").toAbsolutePath();
WorkspaceManager workspaceManager = workspaceManagerFactory.apply(projectRoot, config);
Path arenaDir = workspaceManager.initialize();

log.info("Workspace initialized: {}", arenaDir);
log.info("Review target: {}", reviewTargetStr);

// Execute Round 0
AgentExecutor executor = new AgentExecutor(config, workspaceManager);
Map<String, AgentResult> round0Results = executor.executeRound(0);

// Check minimum agents threshold
long successCount = round0Results.values().stream()
    .filter(AgentResult::isSuccess)
    .count();

if (successCount < config.minAgents()) {
    log.error("Only {} agents succeeded, minimum {} required. Aborting.",
        successCount, config.minAgents());
    return 4; // Agent error exit code
}

log.info("Round 0 complete: {} agents produced reviews", successCount);

// TODO: Implement rounds 1-N (Milestone 3)
// TODO: Implement review aggregation (Milestone 3)
// TODO: Implement final synthesis (Milestone 3)

return 0;
```

---

## Verification Checklist

After implementation, verify:

- [ ] `mvn compile` - All code compiles
- [ ] `mvn test` - All unit tests pass
- [ ] `mvn verify` - All integration tests pass
- [ ] Manual test with `--dry-run` still works
- [ ] Manual test with mock agents executes round 0

### Manual Testing Commands

```bash
# Build
mvn clean package -DskipTests

# Test dry-run still works
java -jar target/review-arena-*.jar --dry-run abc1234

# Test with echo as mock agent (modify application.yaml temporarily)
# Set agents.claude.command to ["echo", "test"]
java -jar target/review-arena-*.jar abc1234
```

---

## Summary

| Step | Component | Lines of Code (est.) | Test Lines (est.) |
|------|-----------|---------------------|-------------------|
| 1 | AgentResult | ~60 | ~60 |
| 2 | OutputValidator | ~50 | ~60 |
| 3 | Mock Agents | ~40 (scripts) | - |
| 4 | CommandBuilder | ~80 | ~100 |
| 5 | AgentProcess | ~200 | ~150 |
| 6 | AgentExecutor | ~150 | ~80 |
| 7 | Integration Tests | - | ~100 |
| 8 | CLI Integration | ~20 | - |
| **Total** | | **~600** | **~550** |
