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
| Template Engine | FreeMarker (${variableName} syntax) |

## Maven Coordinates

```xml
<groupId>dev.reviewarena</groupId>
<artifactId>review-arena</artifactId>
```

## Configuration with MicroProfile Config

The project uses **SmallRye Config** (the reference implementation of MicroProfile Config) for programmatic configuration loading.

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
SmallRyeConfig config = buildConfig(arenaYamlPath);
int maxRounds = config.getOptionalValue("limits.rounds", Integer.class)
    .orElseThrow(() -> new ConfigException("Missing required 'limits.rounds'"));
```

### YAML Configuration Example

```yaml
# application.yaml
limits:
  rounds: 5
  max-output-size-kb: 500

execution:
  max-concurrent: 0
  show-agent-output: true

timeouts:
  agent-timeout-ms: 600000    # 10 minutes
  round-timeout-ms: 900000    # 15 minutes
  grace-period-ms: 5000

tournament:
  min-agents: 1

agents:
  claude:
    command: ["claude", "-p"]
    flags:
      auto-approve: true
      output-format: json
  codex:
    command: ["codex", "exec", "--full-auto", "-o", "@output", "-"]
    flags:
      auto-approve: false
  gemini:
    command: ["gemini"]
    flags:
      auto-approve: true
```

### Programmatic Access

Configuration is loaded programmatically via SmallRye Config's builder API:

```java
import io.smallrye.config.SmallRyeConfig;

SmallRyeConfig config = buildConfig(arenaYamlPath);
int maxRounds = config.getValue("limits.rounds", Integer.class);
```

### Benefits

| Benefit | Description |
|---------|-------------|
| Type-safe access | Programmatic API with type conversion |
| Default values | Fallback values when config is missing |
| Multiple sources | Environment variables, system properties, YAML files |
| Nested YAML support | Complex hierarchical configuration structures |
| Validation | Integration with Bean Validation for config constraints |

## Package Structure

```
dev.reviewarena
├── cli          # CLI entry point, argument parsing (picocli)
├── config       # Configuration loading, YAML parsing, defaults, DockerConfig, EnvLoader
├── git          # Git operations (JGit), startup validation
├── agent        # AgentProcess, AgentExecutor, CommandBuilder, ReviewAggregator, OutputValidator
└── io           # WorkspaceManager, TemplateLoader, template contexts
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
├── WorkspaceException (exit 4 - workspace/IO errors)
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
| Timeout action | Always `kill-and-skip` - terminate agent, exclude from round, continue tournament |
| Partial output | Always discarded - incomplete output from timed-out agents is never kept |
| Per-agent timeout overrides | Not supported - all agents use `agent-timeout-ms` |

### Tournament Constraints
| Decision | Choice |
|----------|--------|
| Minimum agents | 1 - single-agent mode allowed by default; set to 2 for cross-pollination enforcement |

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
| `--quiet` flag | Yes - suppress agent stdout/stderr streaming to console |
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

**Milestone 1:** Git validation + Configuration + Workspace setup

**Milestone 2:** Agent process layer

## Docker Support

| Decision | Choice |
|----------|--------|
| Docker mode | Optional, per-agent configuration via `docker` section |
| Docker sandbox | Supported via `docker sandbox run` for Docker Desktop |
| Container naming | Agent name used as container name for deterministic tracking |
| Shutdown cleanup | JVM shutdown hook stops all active Docker containers |
| Credential handling | Claude `.credentials.json` mounted read-only; ANTHROPIC_API_KEY skipped for local agents |
| Path translation | Host paths translated to `/workspace/...` in DockerCommandBuilder |

## Environment Loading

| Decision | Choice |
|----------|--------|
| `.env` file | Supported via EnvLoader, loaded at startup before all other initialization |
| Purpose | API keys, credentials, and other environment variables |

## Review Agent Expansion

| Decision | Choice |
|----------|--------|
| `review-agents` config | Type shorthands expanded to numbered instances (e.g., `claude, claude` → `claude-1, claude-2`) |
| Default | `claude, claude, claude` (3 Claude instances) |
| Synthesis reserved | `synthesis` cannot be used as a review-agent shorthand |

## Summary of Key Choices

| Question | Answer |
|----------|--------|
| How to parse CLI args? | picocli |
| How to load config properties? | SmallRye Config (MicroProfile Config) programmatic API |
| How to parse YAML config? | SmallRye Config with YAML source (SnakeYAML internally) |
| How to log? | SLF4J + Logback |
| How to resolve template placeholders? | FreeMarker engine |
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
| Timeout action? | Always kill-and-skip (continue tournament) |
| Partial output on timeout? | Always discarded |
| Minimum agents? | 1 (default, allows single-agent mode) |
| Per-agent timeout overrides? | No (single timeout for all) |
| Raw CLI flag passthrough? | No (use structured flags only) |
| YAML key naming convention? | kebab-case |
| Synthesizer agent? | Claude required (no fallback) |
| Synthesis prompt persistence? | Yes, saved to `.arena/rounds/final/prompt.md` |
| TemplateContext for synthesis? | Separate SynthesisContext record |
| Docker support? | Optional per-agent, via DockerConfig |
| .env file? | Yes, loaded at startup via EnvLoader |
| Review agent expansion? | Type shorthands → numbered instances |
| Quiet mode? | --quiet flag suppresses agent output streaming |
| Synthesizer agent name? | "synthesis" (separate from review agents, type=claude) |
