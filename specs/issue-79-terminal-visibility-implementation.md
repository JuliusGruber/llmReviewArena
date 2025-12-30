# Issue 79: Terminal Visibility Implementation Plan

## Overview

This document describes the implementation plan for displaying real-time agent output in separate terminal windows, giving users visibility into what agents are doing during execution.

**GitHub Issue**: [#79 - Display what agents are doing: real-time terminal output visibility](https://github.com/JuliusGruber/llmReviewArena/issues/79)

## Problem Statement

Users currently have no real-time visibility into agent execution. Output is captured to log files but never displayed. Users only see:
- Round-level progress messages
- Per-agent completion/failure messages
- No intermediate progress or real-time output

## Solution Summary

Implement a **terminal mode** where each agent spawns in its own visible terminal window. Users can watch all agents work in parallel. On success, terminals close automatically. On failure, terminals stay open for inspection.

---

## Architecture Changes

### Strategy Pattern: ProcessExecutor Interface

Introduce a `ProcessExecutor` interface with two implementations:

```
dev.reviewarena.agent
├── ProcessExecutor.java           (NEW - interface)
├── HeadlessExecutor.java          (NEW - current behavior, renamed from embedded in AgentProcess)
├── TerminalExecutor.java          (NEW - terminal mode)
├── terminal/
│   ├── TerminalDetector.java      (NEW - detects available terminals)
│   ├── TerminalType.java          (NEW - enum of supported terminals)
│   └── TerminalCommandBuilder.java (NEW - builds terminal-specific commands)
└── AgentProcess.java              (MODIFIED - delegates to ProcessExecutor)
```

### ProcessExecutor Interface

```java
package dev.reviewarena.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Strategy interface for agent process execution.
 * Implementations handle the actual spawning and monitoring of agent processes.
 */
public interface ProcessExecutor {

    /**
     * Start the agent process.
     *
     * @param config Agent configuration
     * @param workingDir Working directory for the agent
     * @param promptFile Path to the prompt file (for stdin redirection)
     * @param outputFile Expected output file (review.md)
     * @param command The resolved command to execute
     * @throws IOException if process cannot be started
     */
    void start(AgentExecutionContext context) throws IOException;

    /**
     * Wait for completion with timeout.
     *
     * @param timeout Maximum time to wait
     * @return true if completed successfully, false if timed out
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitCompletion(Duration timeout) throws InterruptedException;

    /**
     * Get the exit code after completion.
     *
     * @return exit code (0 = success, non-zero = failure)
     */
    int getExitCode();

    /**
     * Force terminate the process.
     */
    void destroy();

    /**
     * Check if process is still running.
     */
    boolean isRunning();
}
```

### AgentExecutionContext (New Record)

```java
package dev.reviewarena.agent;

import java.nio.file.Path;
import java.util.List;

/**
 * Context for agent execution, containing all necessary information for ProcessExecutor.
 */
public record AgentExecutionContext(
    String agentName,
    int round,
    List<String> command,
    Path workingDir,
    Path promptFile,
    Path outputFile,      // review.md
    Path stdoutLog,       // for log capture
    Path stderrLog,       // for log capture
    Path exitCodeFile,    // .exitcode file for terminal mode
    long gracePeriodMs
) {}
```

---

## Terminal Detection

### Supported Terminals

| Platform | Priority | Terminal | Detection |
|----------|----------|----------|-----------|
| Windows | 1 | Windows Terminal | `where wt.exe` succeeds |
| Windows | 2 | cmd.exe | Always available (fallback) |
| Linux | 1 | gnome-terminal | `which gnome-terminal` succeeds |
| Linux | 2 | konsole | `which konsole` succeeds |
| Linux | 3 | xfce4-terminal | `which xfce4-terminal` succeeds |
| Linux | 4 | xterm | `which xterm` succeeds |

### TerminalType Enum

```java
package dev.reviewarena.agent.terminal;

public enum TerminalType {
    // Windows
    WINDOWS_TERMINAL("wt.exe"),
    CMD("cmd.exe"),

    // Linux
    GNOME_TERMINAL("gnome-terminal"),
    KONSOLE("konsole"),
    XFCE4_TERMINAL("xfce4-terminal"),
    XTERM("xterm"),

    // Fallback
    NONE(null);

    private final String executable;

    // ... constructor and getter
}
```

### TerminalDetector

```java
package dev.reviewarena.agent.terminal;

/**
 * Detects the best available terminal emulator for the current platform.
 * Detection is performed once at startup and cached.
 */
public class TerminalDetector {

    private static TerminalType cachedTerminal = null;

    /**
     * Detect the best available terminal.
     * @return Detected terminal type, or NONE if no terminal available
     */
    public static TerminalType detect() {
        if (cachedTerminal != null) {
            return cachedTerminal;
        }

        if (isWindows()) {
            cachedTerminal = detectWindowsTerminal();
        } else {
            cachedTerminal = detectLinuxTerminal();
        }

        return cachedTerminal;
    }

    private static TerminalType detectWindowsTerminal() {
        // Try wt.exe first, then fall back to cmd.exe
        if (commandExists("where", "wt.exe")) {
            return TerminalType.WINDOWS_TERMINAL;
        }
        return TerminalType.CMD;
    }

    private static TerminalType detectLinuxTerminal() {
        // Try terminals in priority order
        String[] terminals = {"gnome-terminal", "konsole", "xfce4-terminal", "xterm"};
        TerminalType[] types = {
            TerminalType.GNOME_TERMINAL,
            TerminalType.KONSOLE,
            TerminalType.XFCE4_TERMINAL,
            TerminalType.XTERM
        };

        for (int i = 0; i < terminals.length; i++) {
            if (commandExists("which", terminals[i])) {
                return types[i];
            }
        }
        return TerminalType.NONE;
    }
}
```

---

## Configuration

### New Configuration Property

Add to `application.yaml`:

```yaml
execution:
  show-terminal: true    # Set to false for CI/headless environments
  max-concurrent: 0      # (existing)
```

### ArenaConfig Changes

```java
// Add to ArenaConfig.java
@ConfigProperty(name = "execution.show-terminal", defaultValue = "true")
boolean showTerminal;

public boolean isShowTerminal() {
    return showTerminal;
}
```

### Effective Mode Selection Logic

```
if (config.showTerminal && TerminalDetector.detect() != NONE) {
    use TerminalExecutor
} else {
    use HeadlessExecutor (with warning if terminal was requested but unavailable)
}
```

---

## Completion Detection

### WatchService-Based Detection

Instead of polling, use Java's `WatchService` API for event-driven file monitoring:

```java
package dev.reviewarena.agent;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class OutputFileWatcher implements AutoCloseable {

    private final WatchService watchService;
    private final Path outputDir;
    private final String targetFileName;  // "review.md"

    public OutputFileWatcher(Path outputDir, String targetFileName) throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.outputDir = outputDir;
        this.targetFileName = targetFileName;

        // Register for create and modify events
        outputDir.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY);
    }

    /**
     * Wait for the target file to appear and stabilize.
     *
     * @param timeout Maximum time to wait
     * @return true if file appeared and stabilized, false if timeout
     */
    public boolean awaitFile(Duration timeout) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < endTime) {
            long remaining = endTime - System.currentTimeMillis();
            WatchKey key = watchService.poll(remaining, TimeUnit.MILLISECONDS);

            if (key != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path fileName = (Path) event.context();
                    if (fileName.toString().equals(targetFileName)) {
                        // File detected - wait for stability
                        if (awaitFileStability(outputDir.resolve(targetFileName))) {
                            return true;
                        }
                    }
                }
                key.reset();
            }
        }
        return false;
    }

    /**
     * Wait for file size to stabilize (2 consecutive same-size readings 500ms apart).
     */
    private boolean awaitFileStability(Path file) throws InterruptedException {
        long lastSize = -1;
        int stableCount = 0;

        while (stableCount < 2) {
            Thread.sleep(500);
            try {
                long currentSize = Files.size(file);
                if (currentSize > 0 && currentSize == lastSize) {
                    stableCount++;
                } else {
                    stableCount = 0;
                    lastSize = currentSize;
                }
            } catch (IOException e) {
                // File might have been deleted - reset
                stableCount = 0;
                lastSize = -1;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        watchService.close();
    }
}
```

### Pre-Execution Cleanup

Before spawning each agent, delete stale files:

```java
// In TerminalExecutor.start()
Files.deleteIfExists(context.outputFile());      // review.md
Files.deleteIfExists(context.exitCodeFile());    // .exitcode
```

---

## Shell Command Templates

### Exit Code Capture

The shell command must write the exit code to a `.exitcode` file that the orchestrator reads after completion.

### Windows Commands

#### Windows Terminal (wt.exe)

```cmd
wt.exe -w 0 nt --title "Agent: claude (Round 1)" cmd /c "cd /d {workingDir} && {command} 2>&1 | tee {stdoutLog} && (echo 0 > {exitCodeFile} & exit) || (echo %errorlevel% > {exitCodeFile} & pause)"
```

**Breakdown:**
- `wt.exe -w 0 nt` - Open new tab in current window (or new window if none)
- `--title "Agent: <name> (Round N)"` - Window title for identification
- `cd /d {workingDir}` - Change to working directory
- `{command} 2>&1 | tee {stdoutLog}` - Run command, capture both streams to log
- `echo 0 > {exitCodeFile}` - Write success exit code
- `|| (echo %errorlevel% > {exitCodeFile} & pause)` - On failure: write exit code, pause for inspection

#### cmd.exe (Fallback)

```cmd
cmd /c start "Agent: claude (Round 1)" cmd /c "cd /d {workingDir} && {command} 2>&1 | tee {stdoutLog} && (echo 0 > {exitCodeFile} & exit) || (echo %errorlevel% > {exitCodeFile} & pause)"
```

**Note:** `tee` requires Git Bash or similar in PATH. Alternative: Use PowerShell's `Tee-Object`.

### Linux Commands

#### gnome-terminal

```bash
gnome-terminal --title="Agent: claude (Round 1)" -- bash -c "cd {workingDir} && {command} 2>&1 | tee {stdoutLog}; echo $? > {exitCodeFile}; [ $? -eq 0 ] || read -p 'Press Enter to close...'"
```

#### konsole

```bash
konsole --new-tab -p tabtitle="Agent: claude (Round 1)" -e bash -c "cd {workingDir} && {command} 2>&1 | tee {stdoutLog}; echo $? > {exitCodeFile}; [ $? -eq 0 ] || read -p 'Press Enter to close...'"
```

#### xfce4-terminal

```bash
xfce4-terminal --title="Agent: claude (Round 1)" -e "bash -c 'cd {workingDir} && {command} 2>&1 | tee {stdoutLog}; echo \$? > {exitCodeFile}; [ \$? -eq 0 ] || read -p \"Press Enter to close...\"'"
```

#### xterm

```bash
xterm -title "Agent: claude (Round 1)" -e bash -c "cd {workingDir} && {command} 2>&1 | tee {stdoutLog}; echo $? > {exitCodeFile}; [ $? -eq 0 ] || read -p 'Press Enter to close...'"
```

### TerminalCommandBuilder

```java
package dev.reviewarena.agent.terminal;

public class TerminalCommandBuilder {

    /**
     * Build the complete terminal command for the given context.
     */
    public static List<String> buildCommand(
            TerminalType terminal,
            AgentExecutionContext context,
            List<String> agentCommand
    ) {
        String title = String.format("Agent: %s (Round %d)",
            context.agentName(), context.round());

        // Build inner command with tee and exit code capture
        String innerCommand = buildInnerCommand(context, agentCommand);

        return switch (terminal) {
            case WINDOWS_TERMINAL -> buildWindowsTerminalCommand(title, innerCommand, context);
            case CMD -> buildCmdCommand(title, innerCommand, context);
            case GNOME_TERMINAL -> buildGnomeTerminalCommand(title, innerCommand);
            case KONSOLE -> buildKonsoleCommand(title, innerCommand);
            case XFCE4_TERMINAL -> buildXfce4TerminalCommand(title, innerCommand);
            case XTERM -> buildXtermCommand(title, innerCommand);
            case NONE -> throw new IllegalStateException("No terminal available");
        };
    }

    // ... implementation methods for each terminal type
}
```

---

## TerminalExecutor Implementation

```java
package dev.reviewarena.agent;

import dev.reviewarena.agent.terminal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;

public class TerminalExecutor implements ProcessExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalExecutor.class);

    private final TerminalType terminalType;
    private Process terminalProcess;
    private OutputFileWatcher watcher;
    private AgentExecutionContext context;
    private int exitCode = -1;

    public TerminalExecutor(TerminalType terminalType) {
        this.terminalType = terminalType;
    }

    @Override
    public void start(AgentExecutionContext context) throws IOException {
        this.context = context;

        // Clean up stale files
        Files.deleteIfExists(context.outputFile());
        Files.deleteIfExists(context.exitCodeFile());

        // Build terminal command
        List<String> terminalCommand = TerminalCommandBuilder.buildCommand(
            terminalType, context, context.command());

        LOG.debug("Starting terminal for agent '{}': {}",
            context.agentName(), String.join(" ", terminalCommand));

        // Start file watcher BEFORE spawning process
        watcher = new OutputFileWatcher(
            context.outputFile().getParent(),
            context.outputFile().getFileName().toString());

        // Spawn terminal process
        ProcessBuilder pb = new ProcessBuilder(terminalCommand);
        pb.directory(context.workingDir().toFile());
        pb.inheritIO();  // Terminal handles its own I/O

        terminalProcess = pb.start();
    }

    @Override
    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
        try {
            // Wait for review.md to appear and stabilize
            boolean completed = watcher.awaitFile(timeout);

            if (completed) {
                // Read exit code from .exitcode file
                exitCode = readExitCode();
                return exitCode == 0;
            }

            return false;
        } finally {
            closeWatcher();
        }
    }

    private int readExitCode() {
        try {
            if (Files.exists(context.exitCodeFile())) {
                String content = Files.readString(context.exitCodeFile()).trim();
                return Integer.parseInt(content);
            }
        } catch (IOException | NumberFormatException e) {
            LOG.warn("Failed to read exit code for agent '{}': {}",
                context.agentName(), e.getMessage());
        }
        // If review.md exists but .exitcode doesn't, assume success
        return Files.exists(context.outputFile()) ? 0 : 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void destroy() {
        closeWatcher();

        if (terminalProcess != null && terminalProcess.isAlive()) {
            // Destroy terminal and all descendants
            terminalProcess.descendants().forEach(ProcessHandle::destroy);
            terminalProcess.destroy();

            try {
                boolean terminated = terminalProcess.waitFor(
                    context.gracePeriodMs(), TimeUnit.MILLISECONDS);
                if (!terminated) {
                    terminalProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminalProcess.destroyForcibly();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return terminalProcess != null && terminalProcess.isAlive();
    }

    private void closeWatcher() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                LOG.debug("Error closing file watcher: {}", e.getMessage());
            }
        }
    }
}
```

---

## HeadlessExecutor Implementation

Refactor current logic from `AgentProcess` into `HeadlessExecutor`:

```java
package dev.reviewarena.agent;

/**
 * Headless executor - current behavior.
 * Captures stdout/stderr to log files without displaying to user.
 */
public class HeadlessExecutor implements ProcessExecutor {

    private Process process;
    private AgentExecutionContext context;
    private Thread stdoutDrain;
    private Thread stderrDrain;

    @Override
    public void start(AgentExecutionContext context) throws IOException {
        this.context = context;

        ProcessBuilder pb = new ProcessBuilder(wrapCommand(context.command()));
        pb.directory(context.workingDir().toFile());
        pb.redirectInput(context.promptFile().toFile());

        process = pb.start();

        // Start stream drain threads (existing logic)
        stdoutDrain = startStreamDrain(
            process.getInputStream(), context.stdoutLog(), "stdout");
        stderrDrain = startStreamDrain(
            process.getErrorStream(), context.stderrLog(), "stderr");
    }

    @Override
    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

        if (completed) {
            // Wait for drain threads to finish
            stdoutDrain.join(1000);
            stderrDrain.join(1000);
            return process.exitValue() == 0;
        }

        return false;
    }

    @Override
    public int getExitCode() {
        return process != null ? process.exitValue() : -1;
    }

    @Override
    public void destroy() {
        // Existing graceful termination logic
        // ...
    }

    @Override
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    // ... existing stream drain logic moved from AgentProcess
}
```

---

## AgentProcess Modifications

Modify `AgentProcess` to delegate to `ProcessExecutor`:

```java
public class AgentProcess {

    private final ProcessExecutor executor;
    private final OutputValidator validator;
    // ... other fields

    public AgentProcess(Builder builder) {
        this.executor = createExecutor(builder);
        // ... initialize other fields
    }

    private ProcessExecutor createExecutor(Builder builder) {
        if (builder.showTerminal) {
            TerminalType terminal = TerminalDetector.detect();
            if (terminal != TerminalType.NONE) {
                return new TerminalExecutor(terminal);
            }
            LOG.warn("Terminal mode requested but no terminal available. " +
                     "Falling back to headless mode.");
        }
        return new HeadlessExecutor();
    }

    public AgentResult execute() {
        long startTime = System.currentTimeMillis();

        try {
            executor.start(buildContext());

            boolean completed = executor.awaitCompletion(Duration.ofMillis(timeoutMs));

            if (!completed) {
                executor.destroy();
                return AgentResult.timeout(agentName, round, timeoutMs);
            }

            int exitCode = executor.getExitCode();
            if (exitCode != 0) {
                return AgentResult.failed(agentName, round, exitCode,
                    "Process exited with code " + exitCode,
                    System.currentTimeMillis() - startTime);
            }

            // Validate output
            ValidationResult validation = validator.validate(outputFile);
            if (!validation.valid()) {
                return AgentResult.invalidOutput(agentName, round,
                    validation.errorMessage(),
                    System.currentTimeMillis() - startTime);
            }

            return AgentResult.success(agentName, round,
                System.currentTimeMillis() - startTime, outputFile);

        } catch (IOException e) {
            return AgentResult.failed(agentName, round, -1,
                e.getMessage(),
                System.currentTimeMillis() - startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.destroy();
            return AgentResult.timeout(agentName, round,
                System.currentTimeMillis() - startTime);
        }
    }

    // Builder adds new field
    public static class Builder {
        private boolean showTerminal = false;

        public Builder showTerminal(boolean showTerminal) {
            this.showTerminal = showTerminal;
            return this;
        }
    }
}
```

---

## Implementation Steps

### Phase 1: Infrastructure (Core Components)

1. **Create ProcessExecutor interface** (`agent/ProcessExecutor.java`)
2. **Create AgentExecutionContext record** (`agent/AgentExecutionContext.java`)
3. **Create terminal package** with:
   - `TerminalType.java` - enum of supported terminals
   - `TerminalDetector.java` - terminal detection logic
   - `TerminalCommandBuilder.java` - builds terminal commands
4. **Create OutputFileWatcher** (`agent/OutputFileWatcher.java`)

### Phase 2: Executor Implementations

5. **Create HeadlessExecutor** - extract existing logic from AgentProcess
6. **Create TerminalExecutor** - new terminal mode implementation

### Phase 3: Integration

7. **Modify ArenaConfig** - add `showTerminal` property
8. **Modify AgentProcess** - delegate to ProcessExecutor
9. **Modify AgentProcess.Builder** - add `showTerminal` field
10. **Modify AgentExecutor** - pass `showTerminal` config to AgentProcess

### Phase 4: Testing

11. **Unit tests for TerminalDetector** - mock process execution
12. **Unit tests for TerminalCommandBuilder** - verify command generation
13. **Unit tests for OutputFileWatcher** - mock file system events
14. **Integration tests** - mock terminal execution

### Phase 5: Documentation

15. **Update spec.md** - document terminal mode behavior
16. **Update implementation-decisions.md** - document new components
17. **Update README** - add terminal mode usage instructions

---

## Configuration Example

### Full Configuration

```yaml
# application.yaml
execution:
  show-terminal: true        # NEW: Enable terminal mode (default: true)
  max-concurrent: 0          # 0 = unlimited parallel agents

timeouts:
  agent-timeout-ms: 300000   # 5 minutes
  round-timeout-ms: 900000   # 15 minutes
  grace-period-ms: 5000      # 5 seconds

agents:
  claude:
    command: ["claude", "-p", "@prompt.md", "-o", "@output"]
    flags:
      auto-approve: true
```

### CI/Headless Configuration

```yaml
# For CI/headless environments
execution:
  show-terminal: false
```

---

## Fallback Behavior

| Condition | Behavior |
|-----------|----------|
| `show-terminal: false` | Use HeadlessExecutor (current behavior) |
| `show-terminal: true` + terminal found | Use TerminalExecutor |
| `show-terminal: true` + no terminal | Log warning, use HeadlessExecutor |
| Terminal spawn fails | Log error, treat as agent failure |
| review.md not found within timeout | Kill terminal, mark agent as timed out |

---

## Files to Modify

| File | Change Type |
|------|-------------|
| `src/main/java/dev/reviewarena/agent/ProcessExecutor.java` | NEW |
| `src/main/java/dev/reviewarena/agent/AgentExecutionContext.java` | NEW |
| `src/main/java/dev/reviewarena/agent/HeadlessExecutor.java` | NEW |
| `src/main/java/dev/reviewarena/agent/TerminalExecutor.java` | NEW |
| `src/main/java/dev/reviewarena/agent/OutputFileWatcher.java` | NEW |
| `src/main/java/dev/reviewarena/agent/terminal/TerminalType.java` | NEW |
| `src/main/java/dev/reviewarena/agent/terminal/TerminalDetector.java` | NEW |
| `src/main/java/dev/reviewarena/agent/terminal/TerminalCommandBuilder.java` | NEW |
| `src/main/java/dev/reviewarena/agent/AgentProcess.java` | MODIFY |
| `src/main/java/dev/reviewarena/agent/AgentExecutor.java` | MODIFY |
| `src/main/java/dev/reviewarena/config/ArenaConfig.java` | MODIFY |
| `src/main/resources/application.yaml` | MODIFY |

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| `tee` not available on Windows | Require Git Bash in PATH, or use PowerShell alternative |
| WatchService platform differences | Test on both Windows and Linux; fallback to polling if needed |
| Terminal process tree issues | Use process descendants API for cleanup |
| Exit code file race condition | Stability polling ensures file is fully written |
| Orphaned terminal windows on failure | Leave for user to close manually (per spec) |

---

## Testing Strategy

### Unit Tests

- `TerminalDetectorTest` - Mock process execution to test detection logic
- `TerminalCommandBuilderTest` - Verify correct command generation for all terminal types
- `OutputFileWatcherTest` - Test with temporary files/directories

### Integration Tests

- Mock terminal that writes review.md after a delay
- Test timeout handling with slow/stuck mock terminal
- Test failure handling with mock terminal that exits with non-zero code

### Manual Testing Checklist

- [ ] Windows Terminal opens and displays agent output
- [ ] cmd.exe fallback works when wt.exe unavailable
- [ ] Terminal closes automatically on success
- [ ] Terminal stays open on failure (with "Press Enter to close")
- [ ] Timeout kills terminal and marks agent as failed
- [ ] Log files are captured via tee
- [ ] Exit code is correctly read from .exitcode file
- [ ] Fallback to headless mode when terminal unavailable
- [ ] Configuration toggle works (`show-terminal: false`)

---

## Success Criteria

1. **Visibility**: Users can see real-time agent output in terminal windows
2. **Auto-close on success**: Terminals close when agents complete successfully
3. **Stay open on failure**: Terminals remain open for inspection on failure
4. **Log capture**: Output is captured to log files even in terminal mode
5. **Fallback**: Graceful fallback to headless mode when terminal unavailable
6. **Configuration**: Users can disable terminal mode via config
7. **Cross-platform**: Works on Windows (wt.exe/cmd.exe) and Linux (gnome-terminal/konsole/xfce4-terminal/xterm)
