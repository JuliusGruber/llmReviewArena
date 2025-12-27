# Implementation Decisions

This document captures all implementation decisions made for the LLM Review Arena project.

## Technology Stack

| Component | Decision |
|-----------|----------|
| Language | Java 21 LTS |
| Build System | Maven |
| CLI Parsing | picocli |
| YAML Parsing | SnakeYAML |
| Logging | SLF4J + Logback |
| Template Placeholders | String.replace() |

## Maven Coordinates

```xml
<groupId>dev.reviewarena</groupId>
<artifactId>review-arena</artifactId>
```

## Package Structure

```
dev.reviewarena
├── cli          # CLI entry point, argument parsing (picocli)
├── config       # Configuration loading, YAML parsing, defaults
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
├── GitException (exit 3 - git errors)
├── AgentException (exit 4 - agent failures)
└── ConfigException (exit 5 - config errors)
```

## Process Management

### Concurrency Model
- **Virtual threads** for lightweight agent execution
- **Semaphore** to enforce `max_concurrent` limit
- Each agent runs in its own virtual thread

### Process Termination (Timeout Handling)
1. Call `process.destroy()` (graceful termination request)
2. Wait `grace_period_ms` (default 5000ms)
3. Call `process.destroyForcibly()` if still running

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
| Temp files (`prompt.txt`) | Keep for debugging (never delete) |

## Git Integration

| Scenario | Behavior |
|----------|----------|
| Validate git refs | Yes - basic validation with `git rev-parse` before starting |
| Not in git repository | Error immediately with exit code 3 |

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
| How to parse YAML config? | SnakeYAML |
| How to log? | SLF4J + Logback |
| How to resolve template placeholders? | String.replace() |
| How to handle agent stdout/stderr? | Drain to DEBUG logs + capture to files |
| How to run agents in parallel? | Virtual threads + Semaphore |
| How to terminate timed-out agents? | destroy() → wait → destroyForcibly() |
| What if arena.yaml missing? | Warn, use defaults |
| What if .arena/ exists? | Clear and recreate |
| What if output too large? | Warn but keep full |
| Validate git refs? | Yes, basic validation |
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
