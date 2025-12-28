# Implementation Decisions

This document captures all implementation decisions made for the LLM Review Arena project.

## Technology Stack

| Component | Decision |
|-----------|----------|
| Language | Java 21 LTS |
| Build System | Maven |
| CLI Parsing | picocli |
| Configuration | SmallRye Config (MicroProfile Config) |
| YAML Parsing | SnakeYAML (via SmallRye Config) |
| Logging | SLF4J + Logback |
| Template Placeholders | String.replace() |

## Maven Coordinates

```xml
<groupId>dev.reviewarena</groupId>
<artifactId>review-arena</artifactId>
```

## Configuration with MicroProfile Config

The project uses **SmallRye Config** (the reference implementation of MicroProfile Config) to enable type-safe configuration injection via `@ConfigProperty`.

### Maven Dependencies

```xml
<dependency>
    <groupId>io.smallrye.config</groupId>
    <artifactId>smallrye-config</artifactId>
    <version>3.5.4</version>
</dependency>
<dependency>
    <groupId>io.smallrye.config</groupId>
    <artifactId>smallrye-config-source-yaml</artifactId>
    <version>3.5.4</version>
</dependency>
```

### Configuration Sources

SmallRye Config loads configuration from multiple sources in priority order:

| Priority | Source | Description |
|----------|--------|-------------|
| 1 (highest) | System properties | `-Dproperty=value` |
| 2 | Environment variables | `PROPERTY_NAME` |
| 3 | `application.yaml` | YAML config in classpath |
| 4 | `application.properties` | Properties file in classpath |
| 5 | `arena.yaml` (custom) | Project-specific config file |

### Usage Pattern

```java
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;

public class ArenaConfig {

    @Inject
    @ConfigProperty(name = "limits.max-rounds", defaultValue = "5")
    int maxRounds;

    @Inject
    @ConfigProperty(name = "limits.max-output-size-kb", defaultValue = "500")
    int maxOutputSizeKb;

    @Inject
    @ConfigProperty(name = "execution.max-concurrent", defaultValue = "0")
    int maxConcurrent;

    @Inject
    @ConfigProperty(name = "timeouts.agent-timeout-ms", defaultValue = "300000")
    long agentTimeoutMs;
}
```

### YAML Configuration Example

```yaml
# application.yaml
limits:
  max-rounds: 5
  max-output-size-kb: 500

execution:
  max-concurrent: 0

timeouts:
  agent-timeout-ms: 300000
  round-timeout-ms: 900000
  grace-period-ms: 5000

agents:
  claude:
    command: ["claude", "-p", "@prompt.md"]
    flags:
      auto-approve: true
  codex:
    command: ["codex", "exec", "@prompt.md"]
    flags:
      auto-approve: true
  gemini:
    command: ["gemini", "-p", "@prompt.md"]
    flags:
      auto-approve: true
```

### Programmatic Access

For cases where injection is not available (e.g., static contexts):

```java
import org.eclipse.microprofile.config.ConfigProvider;
import io.smallrye.config.SmallRyeConfig;

SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
int maxRounds = config.getValue("limits.max-rounds", Integer.class);
```

### Benefits

| Benefit | Description |
|---------|-------------|
| Type-safe injection | `@ConfigProperty` provides compile-time type checking |
| Default values | Fallback values when config is missing |
| Multiple sources | Environment variables, system properties, YAML files |
| Nested YAML support | Complex hierarchical configuration structures |
| Validation | Integration with Bean Validation for config constraints |

## Package Structure

```
dev.reviewarena
├── cli          # CLI entry point, argument parsing (picocli)
├── config       # Configuration loading, YAML parsing, defaults
├── git          # Git operations (JGit), startup validation
├── agent        # AgentProcess, AgentExecutor, process management
├── tournament   # Round execution, cross-pollination logic
├── io           # File operations, template loading, output writing
└── model        # Domain records (Review, RoundResult, etc.)
```

**Main class:** `dev.reviewarena.cli.ReviewArenaCli`

## Domain Model Approach

| Type | Use |
|------|-----|
| **Records** | Immutable data: config, results, review outputs |
| **Classes** | Mutable/stateful: running process wrappers, executors |

## Exception Hierarchy

Maps directly to exit codes:

```
ArenaException (exit 1 - general error)
├── UsageException (exit 2 - invalid arguments)
├── GitValidationException (exit 3 - git errors)
├── AgentException (exit 4 - agent failures)
└── ConfigException (exit 5 - config errors)
```

## Process Management

### Concurrency Model
- **Virtual threads** for lightweight agent execution
- **Semaphore** to enforce `max-concurrent` limit
- Each agent runs in its own virtual thread

### Process Termination (Timeout Handling)
1. Call `process.destroy()` (graceful termination request)
2. Wait `grace-period-ms` (default 5000ms)
3. Call `process.destroyForcibly()` if still running

### Timeout Behavior
| Decision | Choice |
|----------|--------|
| Default timeout action | `kill-and-skip` - terminate agent, exclude from round, continue tournament |
| Preserve partial output | `false` - discard incomplete output from timed-out agents |
| Per-agent timeout overrides | Not supported - all agents use `agent-timeout-ms` |

### Tournament Constraints
| Decision | Choice |
|----------|--------|
| Minimum agents | 2 - tournament aborts if fewer remain (cross-pollination requires 2+) |

### stdout/stderr Handling
- **Drain to logs:** Capture in background threads, log at DEBUG level
- **Capture to files:** Write to `.arena/rounds/round-N/<agent>/stdout.log` and `stderr.log`

## File I/O Decisions

| Scenario | Behavior |
|----------|----------|
| Missing `arena.yaml` | Warn and use built-in defaults |
| Existing `.arena/` directory | Clear and recreate (fresh start) |
| Output exceeds `max_output_size_kb` | Warn but keep full content (no truncation) |
| Non-UTF8 agent output | Replace invalid bytes with `�`, log warning |
| Temp files (`prompt.md`) | Keep for debugging (never delete) |

## Git Integration

| Scenario | Behavior |
|----------|----------|
| Git library | JGit - clean Java API, better error handling, no subprocess overhead |
| Validate git refs | Yes - validate commit hashes exist using JGit before starting |
| Not in git repository | Error immediately with exit code 3 |
| Ref types accepted | Commit hashes only (7-40 hex chars) |

## Agent Output Handling

| Decision | Choice |
|----------|--------|
| Order in `all_reviews.md` | Alphabetical (Claude, Codex, Gemini) |
| Final summary extraction | Skip for v1 (just show output path) |

## CLI Features

| Feature | Decision |
|---------|----------|
| `--dry-run` mode | Yes - show what would happen without running agents |
| `--verbose` flag | No - not for v1 |
| Security warning | No - flag names are self-documenting |

## Progress Output

- **Logger only** - all output through SLF4J
- Configure console appender for INFO+ level for user-facing messages
- DEBUG level for troubleshooting details

## Build & Distribution

| Component | Decision |
|-----------|----------|
| Packaging | Fat JAR with all dependencies |
| Launcher scripts | `review-arena` (bash) + `review-arena.bat` (Windows) |

## Testing Strategy

**Combination approach:**
- **Unit tests:** Abstract `AgentExecutor` interface, mock in tests
- **Integration tests:** Shell script mock agents that write canned `review.md` files

## Development Milestones

**Milestone 1 (MVP):** Full tournament with mock agents
- Complete flow implemented
- Shell script mock agents for testing
- Validates entire architecture

**Milestone 2:** Real agent integration
- Claude CLI integration
- Codex CLI integration
- Gemini CLI integration

## Summary of Key Choices

| Question | Answer |
|----------|--------|
| How to parse CLI args? | picocli |
| How to inject config properties? | SmallRye Config (MicroProfile Config) with `@ConfigProperty` |
| How to parse YAML config? | SmallRye Config with YAML source (SnakeYAML internally) |
| How to log? | SLF4J + Logback |
| How to resolve template placeholders? | String.replace() |
| How to handle agent stdout/stderr? | Drain to DEBUG logs + capture to files |
| How to run agents in parallel? | Virtual threads + Semaphore |
| How to terminate timed-out agents? | destroy() → wait → destroyForcibly() |
| What if arena.yaml missing? | Warn, use defaults |
| What if .arena/ exists? | Clear and recreate |
| What if output too large? | Warn but keep full (no truncation) |
| Validate git refs? | Yes, via JGit (commit hashes only) |
| Order in all_reviews.md? | Alphabetical |
| Keep temp files? | Yes, for debugging |
| Progress output? | Logger only (INFO+) |
| How to test? | Interface mocks + shell script mocks |
| How to package? | Fat JAR + launcher scripts |
| Dry-run mode? | Yes |
| Verbose flag? | No |
| Security warning? | No |
| Extract summary from review? | No (v1) |
| Records vs classes? | Records for immutable, classes for mutable |
| Exception handling? | Hierarchy mapping to exit codes |
| Main class? | ReviewArenaCli |
| Package structure? | By layer |
| Maven groupId? | dev.reviewarena |
| Launcher scripts? | Unix + Windows |
| Timeout action? | kill-and-skip (continue tournament) |
| Preserve partial output? | No (discard on timeout) |
| Minimum agents? | 2 (required for cross-pollination) |
| Per-agent timeout overrides? | No (single timeout for all) |
| Raw CLI flag passthrough? | No (use structured flags only) |
| YAML key naming convention? | kebab-case |
