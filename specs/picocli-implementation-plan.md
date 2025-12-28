# Picocli Argument Parsing Implementation Plan

This document outlines the implementation plan for CLI argument parsing using picocli.

## Overview

The CLI entry point uses **picocli** to parse command-line arguments before passing control to validation and tournament execution. Picocli handles:
- Argument parsing and type conversion
- Help text generation
- Environment variable fallbacks
- Mutual exclusivity validation (basic)

## Maven Dependency

Add to `pom.xml`:

```xml
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>4.7.6</version>
</dependency>
```

## CLI Specification Summary

```
review-arena [options] <ref1> [ref2]
review-arena --staged [options]
```

### Arguments

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `ref1` | String | Conditional | Git commit hash (required unless `--staged`) |
| `ref2` | String | No | End commit hash for range comparison |

### Options

| Option | Short | Type | Default | Env Variable | Description |
|--------|-------|------|---------|--------------|-------------|
| `--help` | `-h` | flag | - | - | Show help |
| `--config` | `-c` | Path | `arena.yaml` | `REVIEW_ARENA_CONFIG` | Config file path |
| `--rounds` | `-r` | int | 5 | `REVIEW_ARENA_MAX_ROUNDS` | Maximum rounds |
| `--output` | `-o` | Path | `.arena` | `REVIEW_ARENA_OUTPUT_DIR` | Output directory |
| `--parallel` | - | flag | false | - | Force parallel execution |
| `--sequential` | - | flag | false | - | Force sequential execution |
| `--max-concurrent` | - | int | 0 | `REVIEW_ARENA_MAX_CONCURRENT` | Max concurrent agents (0=unlimited) |
| `--staged` | - | flag | false | - | Review staged changes |
| `--dry-run` | - | flag | false | - | Show what would happen |

### Mutual Exclusivity Rules

1. `--staged` and positional arguments (`ref1`, `ref2`) are mutually exclusive
2. `--parallel` and `--sequential` are mutually exclusive
3. At least one of `--staged` OR `ref1` must be provided

## Implementation

### File Location

`src/main/java/dev/reviewarena/cli/ReviewArenaCli.java`

### Class Structure

```java
package dev.reviewarena.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ArgGroup;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "review-arena",
    mixinStandardHelpOptions = true,
    version = "review-arena 1.0",
    description = "Multi-round code review tournament with AI agents",
    sortOptions = false,
    usageHelpAutoWidth = true
)
public class ReviewArenaCli implements Callable<Integer> {

    //==========================================================================
    // Mutually Exclusive: --staged vs positional refs
    //==========================================================================

    @ArgGroup(exclusive = true, multiplicity = "1")
    private ReviewTarget reviewTarget;

    static class ReviewTarget {
        @ArgGroup(exclusive = false)
        CommitRefs commitRefs;

        @Option(names = "--staged",
                description = "Review staged changes instead of commits")
        boolean staged;
    }

    static class CommitRefs {
        @Parameters(index = "0",
                    paramLabel = "<ref1>",
                    description = "Git commit hash to review")
        String ref1;

        @Parameters(index = "1",
                    paramLabel = "[ref2]",
                    arity = "0..1",
                    description = "End commit hash for range comparison")
        String ref2;
    }

    //==========================================================================
    // Configuration Options
    //==========================================================================

    @Option(names = {"-c", "--config"},
            paramLabel = "<file>",
            description = "Path to config file (default: ${DEFAULT-VALUE})",
            defaultValue = "arena.yaml",
            fallbackValue = "arena.yaml")
    private Path configFile;

    @Option(names = {"-r", "--rounds"},
            paramLabel = "<n>",
            description = "Maximum cross-pollination rounds (default: ${DEFAULT-VALUE})",
            defaultValue = "${REVIEW_ARENA_MAX_ROUNDS:-5}")
    private int maxRounds;

    @Option(names = {"-o", "--output"},
            paramLabel = "<dir>",
            description = "Output directory (default: ${DEFAULT-VALUE})",
            defaultValue = "${REVIEW_ARENA_OUTPUT_DIR:-.arena}")
    private Path outputDir;

    //==========================================================================
    // Execution Mode (mutually exclusive)
    //==========================================================================

    @ArgGroup(exclusive = true)
    private ExecutionMode executionMode;

    static class ExecutionMode {
        @Option(names = "--parallel",
                description = "Force parallel agent execution")
        boolean parallel;

        @Option(names = "--sequential",
                description = "Force sequential agent execution")
        boolean sequential;
    }

    @Option(names = "--max-concurrent",
            paramLabel = "<n>",
            description = "Limit concurrent agents (0=unlimited, 1=sequential)",
            defaultValue = "${REVIEW_ARENA_MAX_CONCURRENT:-0}")
    private int maxConcurrent;

    //==========================================================================
    // Other Options
    //==========================================================================

    @Option(names = "--dry-run",
            description = "Show what would happen without running agents")
    private boolean dryRun;

    //==========================================================================
    // Main Entry Point
    //==========================================================================

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ReviewArenaCli())
            .setExecutionExceptionHandler(new ExceptionHandler())
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // Extract values from ArgGroups for easier access
        boolean staged = reviewTarget != null && reviewTarget.staged;
        String ref1 = (reviewTarget != null && reviewTarget.commitRefs != null)
                      ? reviewTarget.commitRefs.ref1 : null;
        String ref2 = (reviewTarget != null && reviewTarget.commitRefs != null)
                      ? reviewTarget.commitRefs.ref2 : null;

        try {
            // 1. Git validation (delegated to GitService)
            GitService gitService = new GitService();

            if (!staged) {
                InputValidator.validateHashFormat(ref1);
                gitService.validateCommitExists(ref1);
                if (ref2 != null) {
                    InputValidator.validateHashFormat(ref2);
                    gitService.validateCommitExists(ref2);
                    gitService.validateAncestry(ref1, ref2);
                }
            }

            // 2. Resolve execution mode
            int effectiveConcurrency = resolveExecutionMode();

            // 3. Load configuration (SmallRye Config handles merging)
            // CLI values override config file values

            // 4. Continue to tournament orchestration...
            if (dryRun) {
                printDryRunSummary(staged, ref1, ref2, effectiveConcurrency);
                return 0;
            }

            // TODO: Start tournament
            return 0;

        } catch (GitValidationException e) {
            System.err.println(e.getMessage());
            return e.getExitCode();
        }
    }

    private int resolveExecutionMode() {
        if (executionMode != null) {
            if (executionMode.sequential) return 1;
            if (executionMode.parallel) return 0;
        }
        return maxConcurrent;
    }

    private void printDryRunSummary(boolean staged, String ref1, String ref2, int concurrency) {
        System.out.println("Dry run - would execute:");
        System.out.println("  Review target: " + (staged ? "--staged" : ref1 + (ref2 != null ? ".." + ref2 : "")));
        System.out.println("  Config file: " + configFile);
        System.out.println("  Output directory: " + outputDir);
        System.out.println("  Max rounds: " + maxRounds);
        System.out.println("  Concurrency: " + (concurrency == 0 ? "unlimited" : concurrency));
    }
}
```

### Exception Handler

`src/main/java/dev/reviewarena/cli/ExceptionHandler.java`

```java
package dev.reviewarena.cli;

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

public class ExceptionHandler implements IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
        if (ex instanceof GitValidationException gve) {
            cmd.getErr().println(gve.getMessage());
            return gve.getExitCode();
        }
        if (ex instanceof ConfigException ce) {
            cmd.getErr().println(ce.getMessage());
            return 5;
        }
        if (ex instanceof AgentException ae) {
            cmd.getErr().println(ae.getMessage());
            return 4;
        }

        // Unexpected error
        cmd.getErr().println("Error: " + ex.getMessage());
        return 1;
    }
}
```

### Parameter Validation

Picocli provides built-in validation via `IParameterConsumer` or `ITypeConverter`. For hash format validation at parse time:

`src/main/java/dev/reviewarena/cli/CommitHashConverter.java`

```java
package dev.reviewarena.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.util.regex.Pattern;

public class CommitHashConverter implements ITypeConverter<String> {

    private static final Pattern HASH_PATTERN = Pattern.compile("^[a-fA-F0-9]{7,40}$");

    @Override
    public String convert(String value) throws TypeConversionException {
        if (!HASH_PATTERN.matcher(value).matches()) {
            throw new TypeConversionException(
                "Invalid commit hash format: " + value + " (expected 7-40 hex characters)");
        }
        return value.toLowerCase();
    }
}
```

Update parameters to use the converter:

```java
@Parameters(index = "0",
            paramLabel = "<ref1>",
            converter = CommitHashConverter.class,
            description = "Git commit hash to review")
String ref1;
```

## Environment Variable Support

Picocli supports environment variable fallbacks using `${ENV_VAR:-default}` syntax:

```java
@Option(names = {"-r", "--rounds"},
        defaultValue = "${REVIEW_ARENA_MAX_ROUNDS:-5}")
private int maxRounds;
```

### Precedence Order

Picocli handles this automatically:
1. **CLI arguments** (highest priority)
2. **Environment variables** (via `${ENV:-default}` syntax)
3. **Default values** (lowest priority)

Config file values (arena.yaml) are loaded separately via SmallRye Config and merged in `call()`.

## Generated Help Text

```
Usage: review-arena [-h] [--dry-run] [--parallel | --sequential]
                    [-c=<file>] [--max-concurrent=<n>] [-o=<dir>] [-r=<n>]
                    (<ref1> [<ref2>] | --staged)

Multi-round code review tournament with AI agents

      <ref1>              Git commit hash to review
      [<ref2>]            End commit hash for range comparison
      --staged            Review staged changes instead of commits

  -c, --config=<file>     Path to config file (default: arena.yaml)
  -r, --rounds=<n>        Maximum cross-pollination rounds (default: 5)
  -o, --output=<dir>      Output directory (default: .arena)
      --parallel          Force parallel agent execution
      --sequential        Force sequential agent execution
      --max-concurrent=<n>
                          Limit concurrent agents (0=unlimited, 1=sequential)
      --dry-run           Show what would happen without running agents
  -h, --help              Show this help message and exit
```

## Exit Codes

| Code | Constant | When |
|------|----------|------|
| 0 | SUCCESS | Review completed successfully |
| 1 | GENERAL_ERROR | Unexpected error |
| 2 | USAGE_ERROR | Invalid arguments, picocli parse error |
| 3 | GIT_ERROR | Not a repo, commit not found, invalid ancestry |
| 4 | AGENT_ERROR | Agent not found, execution failed |
| 5 | CONFIG_ERROR | Invalid config file |

Picocli automatically returns exit code 2 for parse errors. Custom exceptions map to their respective codes via `ExceptionHandler`.

## File Structure

```
src/main/java/dev/reviewarena/
├── cli/
│   ├── ReviewArenaCli.java        # Main CLI class with picocli annotations
│   ├── ExceptionHandler.java      # Maps exceptions to exit codes
│   └── CommitHashConverter.java   # Validates commit hash format at parse time
└── git/
    ├── GitService.java            # JGit operations (from startup-validation-plan)
    ├── GitValidationException.java
    └── InputValidator.java
```

## Implementation Steps

### Step 1: Add Maven Dependency

```xml
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>4.7.6</version>
</dependency>
```

### Step 2: Create CommitHashConverter

Validates hash format during parsing (before `call()` is invoked).

### Step 3: Create ReviewArenaCli

Main CLI class with:
- `@Command` annotation for metadata
- `@ArgGroup` for mutual exclusivity (`--staged` vs refs, `--parallel` vs `--sequential`)
- `@Option` for all flags with environment variable fallbacks
- `@Parameters` for positional commit refs
- `call()` method that orchestrates validation → config → execution

### Step 4: Create ExceptionHandler

Maps domain exceptions to exit codes:
- `GitValidationException` → 2 or 3 (depending on type)
- `ConfigException` → 5
- `AgentException` → 4
- Other → 1

### Step 5: Integration with GitService

The `call()` method:
1. Extracts parsed values from `@ArgGroup` structures
2. Creates `GitService` instance (validates git repo)
3. Calls `InputValidator` for hash format (if not using converter)
4. Calls `GitService` for commit existence and ancestry
5. Proceeds to configuration loading and tournament execution

### Step 6: Integration with SmallRye Config

After picocli parses CLI args:
1. Load `arena.yaml` via SmallRye Config
2. CLI values override config file values
3. Build final `ArenaConfig` record for tournament execution

## Testing Strategy

### Unit Tests

**ReviewArenaCliTest.java:**

```java
@Test
void testParsesSingleCommit() {
    ReviewArenaCli cli = new ReviewArenaCli();
    new CommandLine(cli).parseArgs("abc1234");
    // Assert ref1 == "abc1234", ref2 == null, staged == false
}

@Test
void testParsesCommitRange() {
    ReviewArenaCli cli = new ReviewArenaCli();
    new CommandLine(cli).parseArgs("abc1234", "def5678");
    // Assert ref1, ref2 set correctly
}

@Test
void testParsesStaged() {
    ReviewArenaCli cli = new ReviewArenaCli();
    new CommandLine(cli).parseArgs("--staged");
    // Assert staged == true, ref1 == null
}

@Test
void testRejectsStagedWithCommit() {
    ReviewArenaCli cli = new ReviewArenaCli();
    assertThrows(MutuallyExclusiveArgsException.class, () ->
        new CommandLine(cli).parseArgs("--staged", "abc1234"));
}

@Test
void testRejectsParallelAndSequential() {
    ReviewArenaCli cli = new ReviewArenaCli();
    assertThrows(MutuallyExclusiveArgsException.class, () ->
        new CommandLine(cli).parseArgs("--parallel", "--sequential", "abc1234"));
}

@Test
void testEnvironmentVariableFallback() {
    // Set REVIEW_ARENA_MAX_ROUNDS=10
    ReviewArenaCli cli = new ReviewArenaCli();
    new CommandLine(cli).parseArgs("abc1234");
    // Assert maxRounds == 10
}

@Test
void testCliOverridesEnvVar() {
    // Set REVIEW_ARENA_MAX_ROUNDS=10
    ReviewArenaCli cli = new ReviewArenaCli();
    new CommandLine(cli).parseArgs("-r", "3", "abc1234");
    // Assert maxRounds == 3
}
```

**CommitHashConverterTest.java:**

```java
@Test
void testValid40CharHash() {
    CommitHashConverter converter = new CommitHashConverter();
    assertEquals("abc1234567890abcdef1234567890abcdef123456",
        converter.convert("ABC1234567890ABCDEF1234567890ABCDEF123456"));
}

@Test
void testValid7CharHash() {
    CommitHashConverter converter = new CommitHashConverter();
    assertEquals("abc1234", converter.convert("ABC1234"));
}

@Test
void testRejectsTooShort() {
    CommitHashConverter converter = new CommitHashConverter();
    assertThrows(TypeConversionException.class, () -> converter.convert("abc12"));
}

@Test
void testRejectsInvalidChars() {
    CommitHashConverter converter = new CommitHashConverter();
    assertThrows(TypeConversionException.class, () -> converter.convert("xyz1234"));
}
```

### Integration Tests

1. Run CLI with `--help` → verify exit 0, help text printed
2. Run CLI with no args → verify exit 2
3. Run CLI with valid commit (in test repo) → verify exit 0
4. Run CLI with invalid commit → verify exit 3
5. Run CLI with `--staged` → verify exit 0 (if staged changes exist)

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Mutual exclusivity | `@ArgGroup(exclusive = true)` | Picocli handles validation and error messages |
| Hash validation | `ITypeConverter` | Fail-fast at parse time, clean error messages |
| Env var fallbacks | `${ENV:-default}` syntax | Built-in picocli feature, no extra code |
| Exception handling | `IExecutionExceptionHandler` | Centralized exit code mapping |
| Help generation | `mixinStandardHelpOptions` | Automatic `--help` and `--version` |

## Interaction with Other Components

```
┌─────────────────────────────────────────────────────────────────────┐
│                         User invokes CLI                            │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Picocli (ReviewArenaCli)                         │
│  • Parse arguments                                                  │
│  • Validate hash format (CommitHashConverter)                       │
│  • Handle mutual exclusivity (@ArgGroup)                            │
│  • Apply env var fallbacks                                          │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    GitService (JGit)                                │
│  • Validate git repository                                          │
│  • Validate commits exist                                           │
│  • Validate ancestry (if two commits)                               │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                SmallRye Config (ArenaConfig)                        │
│  • Load arena.yaml                                                  │
│  • Merge with CLI-provided values                                   │
│  • Build final configuration                                        │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  Tournament Orchestration                           │
│  • Setup workspace                                                  │
│  • Execute rounds                                                   │
│  • Generate final output                                            │
└─────────────────────────────────────────────────────────────────────┘
```

## Summary

Picocli provides:
- **Declarative argument parsing** via annotations
- **Automatic help generation** with `mixinStandardHelpOptions`
- **Mutual exclusivity** via `@ArgGroup(exclusive = true)`
- **Environment variable fallbacks** via `${ENV:-default}` syntax
- **Type conversion with validation** via `ITypeConverter`
- **Custom exception handling** via `IExecutionExceptionHandler`

This keeps the CLI layer thin and focused on argument parsing, delegating:
- Git validation → `GitService` (JGit)
- Configuration loading → SmallRye Config
- Tournament execution → orchestration layer
