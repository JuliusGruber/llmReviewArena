# Milestone 2: Agent Process Layer - Implementation Plan

## Overview

Milestone 2 implements the core agent execution infrastructure: spawning CLI agents as subprocesses, managing their lifecycle, handling timeouts, capturing output, and enforcing concurrency limits.

## Prerequisites

- **Milestone 1 complete**: GitService, ConfigLoader, WorkspaceManager, TemplateLoader all working
- **TemplateLoader status**: Currently in progress (check if complete before starting)

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         AgentExecutor                                    │
│  - Manages concurrent agent execution                                    │
│  - Semaphore for max-concurrent limit                                    │
│  - Collects results from all agents                                      │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ spawns via virtual threads
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         AgentProcess                                     │
│  - Wraps a single agent process                                          │
│  - Handles command construction (flag translation)                       │
│  - Manages lifecycle: start → monitor → terminate                        │
│  - Captures stdout/stderr                                                │
│  - Timeout enforcement                                                   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ produces
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         AgentResult                                      │
│  - Immutable record of execution outcome                                 │
│  - Success/failure status, exit code, duration                           │
│  - Path to output file (review.md)                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## File Structure

```
src/main/java/dev/reviewarena/agent/
├── AgentException.java          # Already exists
├── AgentResult.java             # NEW: Execution result record
├── AgentProcess.java            # NEW: Process wrapper with lifecycle
├── AgentExecutor.java           # NEW: Runs agents with concurrency control
├── CommandBuilder.java          # NEW: Builds CLI commands with flag translation
└── OutputValidator.java         # NEW: Validates agent output (review.md)

src/test/java/dev/reviewarena/agent/
├── AgentResultTest.java
├── AgentProcessTest.java
├── AgentExecutorTest.java
├── CommandBuilderTest.java
└── OutputValidatorTest.java

src/test/resources/mock-agents/
├── success-agent.sh             # Writes valid review.md
├── success-agent.bat            # Windows version
├── slow-agent.sh                # Takes 10+ seconds (for timeout tests)
├── failing-agent.sh             # Exits with non-zero code
└── empty-output-agent.sh        # Creates empty review.md
```

---

## Component Details

### 1. AgentResult (Record)

**Purpose**: Immutable record capturing the outcome of a single agent execution.

```java
public record AgentResult(
    String agentName,           // e.g., "claude"
    int round,                  // round number (0-indexed)
    Status status,              // SUCCESS, FAILED, TIMEOUT, INVALID_OUTPUT
    int exitCode,               // process exit code (-1 if not applicable)
    long durationMs,            // execution duration in milliseconds
    Path outputFile,            // path to review.md (null if failed)
    String failureReason        // human-readable failure message (null if success)
) {
    public enum Status {
        SUCCESS,          // Process completed, output valid
        FAILED,           // Process crashed (non-zero exit)
        TIMEOUT,          // Process exceeded timeout
        INVALID_OUTPUT    // Process succeeded but output missing/empty
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
```

**Factory methods**:
- `AgentResult.success(name, round, exitCode, durationMs, outputFile)`
- `AgentResult.failed(name, round, exitCode, durationMs, reason)`
- `AgentResult.timeout(name, round, durationMs)`
- `AgentResult.invalidOutput(name, round, exitCode, durationMs, reason)`

---

### 2. CommandBuilder (Class)

**Purpose**: Constructs the full command array for spawning an agent, handling flag translation.

**Flag Translation Table** (from spec):

| Config Flag | Claude CLI | Codex CLI | Gemini CLI |
|-------------|------------|-----------|------------|
| `auto-approve: true` | `--dangerously-skip-permissions` | `--full-auto` | `--yolo` |
| `allowed-tools: [...]` | `--allowedTools <csv>` | N/A | N/A |

**Key responsibilities**:
1. Start with base command from `AgentConfig.command()`
2. Replace `@prompt.md` placeholder with actual prompt file path
3. Translate portable flags to CLI-specific flags
4. Return immutable `List<String>` for ProcessBuilder

```java
public class CommandBuilder {

    /**
     * Builds the full command for spawning an agent.
     *
     * @param agentConfig  the agent configuration
     * @param promptFile   path to the prompt file to pass to agent
     * @return immutable list of command arguments
     */
    public List<String> build(AgentConfig agentConfig, Path promptFile) {
        List<String> command = new ArrayList<>(agentConfig.command());

        // Replace @prompt.md with actual path
        replacePromptPlaceholder(command, promptFile);

        // Add translated flags
        addTranslatedFlags(command, agentConfig);

        return List.copyOf(command);
    }

    private void replacePromptPlaceholder(List<String> command, Path promptFile) {
        // Replace "@prompt.md" with actual absolute path
    }

    private void addTranslatedFlags(List<String> command, AgentConfig config) {
        // Translate auto-approve, allowed-tools based on agent name
    }
}
```

**Design decision**: Flag translation is based on agent name (claude, codex, gemini) rather than trying to detect CLI type from command.

---

### 3. AgentProcess (Class)

**Purpose**: Wraps a single agent subprocess with lifecycle management.

**State machine**:
```
CREATED → RUNNING → COMPLETED
              ↓
          TERMINATED (timeout/error)
```

**Key responsibilities**:
1. Start process via ProcessBuilder
2. Capture stdout/stderr in background threads
3. Monitor for timeout
4. Graceful termination: destroy() → wait grace period → destroyForcibly()
5. Return AgentResult when complete

```java
public class AgentProcess {

    private final String agentName;
    private final int round;
    private final List<String> command;
    private final Path workingDir;
    private final Path outputFile;      // expected review.md location
    private final Path stdoutLog;       // .arena/rounds/round-N/<agent>/stdout.log
    private final Path stderrLog;       // .arena/rounds/round-N/<agent>/stderr.log
    private final long timeoutMs;
    private final long gracePeriodMs;

    private Process process;
    private Instant startTime;

    /**
     * Starts the agent process and waits for completion or timeout.
     *
     * @return the execution result
     */
    public AgentResult execute() {
        startTime = Instant.now();

        try {
            process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .start();

            // Start stdout/stderr drain threads
            drainStreamsAsync();

            // Wait for completion with timeout
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!completed) {
                return handleTimeout();
            }

            return handleCompletion();

        } catch (IOException | InterruptedException e) {
            return handleError(e);
        }
    }

    private AgentResult handleTimeout() {
        // 1. Request graceful termination
        process.destroy();

        // 2. Wait grace period
        try {
            boolean exited = process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                // 3. Force kill
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
        }

        return AgentResult.timeout(agentName, round, getDuration());
    }

    private AgentResult handleCompletion() {
        int exitCode = process.exitValue();
        long duration = getDuration();

        if (exitCode != 0) {
            return AgentResult.failed(agentName, round, exitCode, duration,
                "Process exited with code " + exitCode);
        }

        // Validate output
        return validateOutput(duration);
    }

    private void drainStreamsAsync() {
        // Start virtual threads to drain stdout/stderr to log files
        Thread.startVirtualThread(() -> drainStream(process.getInputStream(), stdoutLog));
        Thread.startVirtualThread(() -> drainStream(process.getErrorStream(), stderrLog));
    }
}
```

**Builder pattern for construction**:
```java
AgentProcess agent = AgentProcess.builder()
    .agentName("claude")
    .round(0)
    .command(commandList)
    .workingDir(projectRoot)
    .outputFile(workspaceManager.getAgentDir(0, "claude").resolve("review.md"))
    .stdoutLog(workspaceManager.getAgentDir(0, "claude").resolve("stdout.log"))
    .stderrLog(workspaceManager.getAgentDir(0, "claude").resolve("stderr.log"))
    .timeoutMs(config.agentTimeoutMs())
    .gracePeriodMs(config.gracePeriodMs())
    .build();
```

---

### 4. OutputValidator (Class)

**Purpose**: Validates agent output after execution.

**Validation checks** (from spec):
1. Output file (`review.md`) exists
2. File is non-empty
3. File size within limit (warn but don't fail if exceeded)

```java
public class OutputValidator {

    private final int maxOutputSizeKb;

    public OutputValidator(int maxOutputSizeKb) {
        this.maxOutputSizeKb = maxOutputSizeKb;
    }

    /**
     * Validates the agent's output file.
     *
     * @param outputFile path to review.md
     * @return validation result
     */
    public ValidationResult validate(Path outputFile) {
        if (!Files.exists(outputFile)) {
            return ValidationResult.invalid("Output file does not exist: " + outputFile);
        }

        try {
            long size = Files.size(outputFile);

            if (size == 0) {
                return ValidationResult.invalid("Output file is empty: " + outputFile);
            }

            if (size > maxOutputSizeKb * 1024L) {
                log.warn("Output exceeds size limit ({} KB > {} KB): {}",
                    size / 1024, maxOutputSizeKb, outputFile);
                // Warn but still valid per spec
            }

            return ValidationResult.valid(outputFile);

        } catch (IOException e) {
            return ValidationResult.invalid("Failed to read output file: " + e.getMessage());
        }
    }

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

---

### 5. AgentExecutor (Class)

**Purpose**: Orchestrates concurrent execution of multiple agents for a single round.

**Key responsibilities**:
1. Accept list of agents to run
2. Enforce `max-concurrent` via Semaphore
3. Execute via virtual threads
4. Collect and return all results
5. Log progress

```java
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final ArenaConfig config;
    private final WorkspaceManager workspace;
    private final CommandBuilder commandBuilder;
    private final OutputValidator outputValidator;

    /**
     * Executes all enabled agents for a given round.
     *
     * @param round the round number (0-indexed)
     * @return map of agent name to execution result
     */
    public Map<String, AgentResult> executeRound(int round) {
        List<AgentConfig> enabledAgents = getEnabledAgents();

        log.info("Starting round {} with {} agents", round, enabledAgents.size());

        // Semaphore for concurrency control (0 = unlimited)
        Semaphore semaphore = config.maxConcurrent() > 0
            ? new Semaphore(config.maxConcurrent())
            : null;

        // Results collected from all agents
        ConcurrentHashMap<String, AgentResult> results = new ConcurrentHashMap<>();

        // Launch all agents via virtual threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (AgentConfig agent : enabledAgents) {
                Future<?> future = executor.submit(() -> {
                    acquirePermit(semaphore);
                    try {
                        AgentResult result = executeAgent(agent, round);
                        results.put(agent.name(), result);
                        logResult(result);
                    } finally {
                        releasePermit(semaphore);
                    }
                });
                futures.add(future);
            }

            // Wait for all to complete (with round timeout)
            waitForCompletion(futures, config.roundTimeoutMs());
        }

        log.info("Round {} complete: {}/{} agents succeeded",
            round, countSuccesses(results), enabledAgents.size());

        return Map.copyOf(results);
    }

    private AgentResult executeAgent(AgentConfig agentConfig, int round) {
        log.info("Starting agent '{}' for round {}", agentConfig.name(), round);

        // Get prompt file path (pre-generated by WorkspaceManager)
        Path promptFile = workspace.getRoundPromptPath(round);

        // Build command
        List<String> command = commandBuilder.build(agentConfig, promptFile);

        // Build and execute agent process
        AgentProcess process = AgentProcess.builder()
            .agentName(agentConfig.name())
            .round(round)
            .command(command)
            .workingDir(workspace.getArenaDir().getParent()) // project root
            .outputFile(workspace.getAgentDir(round, agentConfig.name()).resolve("review.md"))
            .stdoutLog(workspace.getAgentDir(round, agentConfig.name()).resolve("stdout.log"))
            .stderrLog(workspace.getAgentDir(round, agentConfig.name()).resolve("stderr.log"))
            .timeoutMs(config.agentTimeoutMs())
            .gracePeriodMs(config.gracePeriodMs())
            .build();

        return process.execute();
    }

    private void logResult(AgentResult result) {
        switch (result.status()) {
            case SUCCESS -> log.info("Agent '{}' completed successfully in {}ms",
                result.agentName(), result.durationMs());
            case FAILED -> log.error("Agent '{}' crashed in round {}: {}",
                result.agentName(), result.round(), result.failureReason());
            case TIMEOUT -> log.error("Agent '{}' timed out in round {} after {}ms",
                result.agentName(), result.round(), result.durationMs());
            case INVALID_OUTPUT -> log.error("Agent '{}' produced invalid output in round {}: {}",
                result.agentName(), result.round(), result.failureReason());
        }
    }
}
```

---

## Implementation Order

| Step | Component | Est. Complexity | Dependencies |
|------|-----------|-----------------|--------------|
| 1 | `AgentResult` | Simple | None |
| 2 | `OutputValidator` | Simple | None |
| 3 | `CommandBuilder` | Medium | `AgentConfig` |
| 4 | `AgentProcess` | Complex | `AgentResult`, `OutputValidator` |
| 5 | `AgentExecutor` | Complex | All above + `WorkspaceManager` |
| 6 | Mock agents | Simple | None (can be done early) |
| 7 | Integration tests | Medium | All components |

**Recommended implementation sequence**:
1. Start with mock agents (shell scripts) to enable testing
2. Build bottom-up: AgentResult → OutputValidator → CommandBuilder → AgentProcess → AgentExecutor
3. Write unit tests alongside each component
4. End with integration test of full round execution

---

## Mock Agents for Testing

Create simple shell scripts that simulate agent behavior:

### success-agent.sh
```bash
#!/bin/bash
# Simulates a successful agent that writes a valid review

PROMPT_FILE=$1
OUTPUT_DIR=$(dirname "$PROMPT_FILE")/../rounds/round-0/mock

mkdir -p "$OUTPUT_DIR"
cat > "$OUTPUT_DIR/review.md" << 'EOF'
# Summary
This is a mock review for testing purposes.

## High-risk issues (must fix)
None identified.

## Medium / low-risk issues
- Minor formatting inconsistencies

## Suggested patches
No patches suggested.

## Test suggestions
Add unit tests for new functionality.

## Questions for the author
None.
EOF

exit 0
```

### slow-agent.sh
```bash
#!/bin/bash
# Simulates a slow agent for timeout testing
sleep 15
echo "Should have timed out" > /tmp/slow-agent-should-not-exist.txt
exit 0
```

### failing-agent.sh
```bash
#!/bin/bash
# Simulates an agent that crashes
echo "Agent encountered an error" >&2
exit 1
```

### empty-output-agent.sh
```bash
#!/bin/bash
# Simulates an agent that creates empty output
PROMPT_FILE=$1
OUTPUT_DIR=$(dirname "$PROMPT_FILE")/../rounds/round-0/mock
mkdir -p "$OUTPUT_DIR"
touch "$OUTPUT_DIR/review.md"  # Empty file
exit 0
```

---

## Test Coverage Plan

### AgentResultTest
- Factory methods create correct instances
- `isSuccess()` returns correct boolean
- Record equality and immutability

### OutputValidatorTest
- Valid file passes validation
- Missing file fails validation
- Empty file fails validation
- Large file warns but passes
- IO errors handled gracefully

### CommandBuilderTest
- `@prompt.md` placeholder replaced
- Claude: `auto-approve` → `--dangerously-skip-permissions`
- Codex: `auto-approve` → `--full-auto`
- Gemini: `auto-approve` → `--yolo`
- `allowed-tools` translated for Claude only
- Unknown agent names use no flag translation

### AgentProcessTest
- Successful execution returns SUCCESS result
- Non-zero exit returns FAILED result
- Timeout triggers graceful then forced termination
- stdout/stderr captured to log files
- Duration correctly measured

### AgentExecutorTest
- Single agent execution works
- Multiple agents execute concurrently
- `max-concurrent` limits parallelism (use CountDownLatch to verify)
- Failed agents don't block others
- Round timeout terminates all running agents
- Results collected from all agents

### Integration Tests (AgentExecutorIT)
- Full round with mock agents
- Mix of success/failure agents
- Timeout handling with slow agent
- Output files in correct locations

---

## Error Handling

| Error Type | Detection | Behavior |
|------------|-----------|----------|
| Process crash | Non-zero exit code | Return FAILED result, log error |
| Timeout | `waitFor()` returns false | Graceful → force kill, return TIMEOUT result |
| Missing output | File doesn't exist after process completes | Return INVALID_OUTPUT result |
| Empty output | File exists but size = 0 | Return INVALID_OUTPUT result |
| IO error starting process | IOException from ProcessBuilder | Return FAILED result with exception message |
| Interrupted | InterruptedException | Force kill process, propagate or return FAILED |

---

## Logging Strategy

| Level | Message Type |
|-------|--------------|
| INFO | Round start/complete, agent start, success |
| WARN | Output size exceeded (but valid) |
| ERROR | Agent crash, timeout, invalid output |
| DEBUG | Command being executed, stdout/stderr content |

---

## Integration Points

### With WorkspaceManager
- `workspace.getRoundPromptPath(round)` → get pre-generated prompt file
- `workspace.getAgentDir(round, agentName)` → get agent output directory
- `workspace.getArenaDir().getParent()` → get project root for working directory

### With ArenaConfig
- `config.agentTimeoutMs()` → per-agent timeout
- `config.gracePeriodMs()` → graceful termination window
- `config.maxConcurrent()` → concurrency limit (0 = unlimited)
- `config.maxOutputSizeKb()` → output size limit for validation
- `config.agents()` → get enabled agents to execute

### With Tournament Orchestrator (Milestone 3)
- `AgentExecutor.executeRound(round)` → returns `Map<String, AgentResult>`
- Orchestrator iterates rounds, calls executor, then aggregates reviews

---

## Success Criteria

Milestone 2 is complete when:

- [ ] `AgentResult` record captures all execution outcomes
- [ ] `CommandBuilder` correctly translates flags for Claude/Codex/Gemini
- [ ] `AgentProcess` spawns processes and captures output
- [ ] `AgentProcess` handles timeouts with graceful → force termination
- [ ] `OutputValidator` checks existence and non-empty
- [ ] `AgentExecutor` runs agents concurrently with semaphore control
- [ ] stdout/stderr captured to `stdout.log` / `stderr.log`
- [ ] All unit tests pass
- [ ] Integration tests with mock agents pass
- [ ] `mvn verify` passes

---

## Open Questions (to resolve during implementation)

1. **Prompt file path in command**: Should we use absolute or relative path when replacing `@prompt.md`?
   - **Recommendation**: Absolute path for reliability across different working directories

2. **Agent-specific working directory**: Spec says agents run in project root. Should each agent have its own copy of the prompt file in its directory, or reference the shared prompt?
   - **Recommendation**: Reference shared prompt from `.arena/prompts/round-N.md` - it's already pre-generated

3. **Stream encoding**: What encoding for stdout/stderr?
   - **Decision from spec**: UTF-8, replace invalid bytes with replacement character

4. **Process cleanup on JVM shutdown**: Should we add shutdown hooks?
   - **Recommendation**: Not for v1 - processes are short-lived and OS will clean up

---

## Dependencies / Imports

No new external dependencies required. Uses:
- `java.lang.ProcessBuilder` / `java.lang.Process`
- `java.util.concurrent.Semaphore`
- `java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()` (Java 21)
- `java.nio.file.*` for file operations
- SLF4J for logging (already in project)
