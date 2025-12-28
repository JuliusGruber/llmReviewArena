# Configuration Layer Implementation Plan

This document provides a detailed implementation plan for `ArenaConfig` and `ConfigLoader` - the configuration layer for Review Arena.

## Overview

The configuration layer provides type-safe configuration with multiple source support:

| Priority | Source | Description |
|----------|--------|-------------|
| 1 (highest) | CLI arguments | `--rounds`, `--max-concurrent`, etc. |
| 2 | Environment variables | `REVIEW_ARENA_MAX_ROUNDS`, etc. |
| 3 | `arena.yaml` (cwd) | Project-specific config |
| 4 | `application.yaml` (classpath) | Built-in defaults |

---

## Files to Create

```
src/main/java/dev/reviewarena/config/
├── ArenaConfig.java       # Main configuration record
├── AgentConfig.java       # Per-agent configuration record
├── ConfigLoader.java      # SmallRye Config integration
└── ConfigException.java   # Already exists

src/main/resources/
└── application.yaml       # Default configuration

src/test/java/dev/reviewarena/config/
├── ArenaConfigTest.java   # Config record tests
├── AgentConfigTest.java   # Agent config tests
└── ConfigLoaderTest.java  # Config loading tests
```

---

## Issue 1: ArenaConfig Record

### Description
Create the main configuration record that holds all tournament settings.

### File: `src/main/java/dev/reviewarena/config/ArenaConfig.java`

```java
package dev.reviewarena.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Immutable configuration for a review arena tournament.
 *
 * <p>Configuration is loaded from multiple sources with the following priority:
 * CLI arguments > Environment variables > arena.yaml > application.yaml defaults
 */
public record ArenaConfig(
    // Limits
    int maxRounds,
    int maxOutputSizeKb,

    // Execution
    int maxConcurrent,

    // Timeouts (milliseconds)
    long agentTimeoutMs,
    long roundTimeoutMs,
    long gracePeriodMs,

    // Timeout behavior
    String onTimeout,              // "kill-and-skip" or "abort"
    boolean preservePartialOutput,

    // Tournament constraints
    int minAgents,

    // Output
    Path outputDir,

    // Agents
    Map<String, AgentConfig> agents
) {
    /**
     * Default configuration values.
     */
    public static final int DEFAULT_MAX_ROUNDS = 5;
    public static final int DEFAULT_MAX_OUTPUT_SIZE_KB = 500;
    public static final int DEFAULT_MAX_CONCURRENT = 0; // unlimited
    public static final long DEFAULT_AGENT_TIMEOUT_MS = 300_000; // 5 minutes
    public static final long DEFAULT_ROUND_TIMEOUT_MS = 900_000; // 15 minutes
    public static final long DEFAULT_GRACE_PERIOD_MS = 5_000; // 5 seconds
    public static final String DEFAULT_ON_TIMEOUT = "kill-and-skip";
    public static final boolean DEFAULT_PRESERVE_PARTIAL_OUTPUT = false;
    public static final int DEFAULT_MIN_AGENTS = 2;
    public static final String DEFAULT_OUTPUT_DIR = ".arena";

    /**
     * Compact constructor with validation.
     */
    public ArenaConfig {
        if (maxRounds < 0) {
            throw new ConfigException("maxRounds must be non-negative, got: " + maxRounds);
        }
        if (maxOutputSizeKb <= 0) {
            throw new ConfigException("maxOutputSizeKb must be positive, got: " + maxOutputSizeKb);
        }
        if (maxConcurrent < 0) {
            throw new ConfigException("maxConcurrent must be non-negative, got: " + maxConcurrent);
        }
        if (agentTimeoutMs <= 0) {
            throw new ConfigException("agentTimeoutMs must be positive, got: " + agentTimeoutMs);
        }
        if (roundTimeoutMs <= 0) {
            throw new ConfigException("roundTimeoutMs must be positive, got: " + roundTimeoutMs);
        }
        if (gracePeriodMs < 0) {
            throw new ConfigException("gracePeriodMs must be non-negative, got: " + gracePeriodMs);
        }
        if (!onTimeout.equals("kill-and-skip") && !onTimeout.equals("abort")) {
            throw new ConfigException("onTimeout must be 'kill-and-skip' or 'abort', got: " + onTimeout);
        }
        if (minAgents < 1) {
            throw new ConfigException("minAgents must be at least 1, got: " + minAgents);
        }
        if (outputDir == null) {
            throw new ConfigException("outputDir must not be null");
        }
        if (agents == null) {
            throw new ConfigException("agents must not be null");
        }
        // Make agents map immutable
        agents = Map.copyOf(agents);
    }

    /**
     * Creates a config with all default values (no agents configured).
     */
    public static ArenaConfig defaults() {
        return new ArenaConfig(
            DEFAULT_MAX_ROUNDS,
            DEFAULT_MAX_OUTPUT_SIZE_KB,
            DEFAULT_MAX_CONCURRENT,
            DEFAULT_AGENT_TIMEOUT_MS,
            DEFAULT_ROUND_TIMEOUT_MS,
            DEFAULT_GRACE_PERIOD_MS,
            DEFAULT_ON_TIMEOUT,
            DEFAULT_PRESERVE_PARTIAL_OUTPUT,
            DEFAULT_MIN_AGENTS,
            Path.of(DEFAULT_OUTPUT_DIR),
            Map.of()
        );
    }
}
```

### Validation Rules

| Field | Rule | Error Message |
|-------|------|---------------|
| `maxRounds` | >= 0 | "maxRounds must be non-negative" |
| `maxOutputSizeKb` | > 0 | "maxOutputSizeKb must be positive" |
| `maxConcurrent` | >= 0 | "maxConcurrent must be non-negative" |
| `agentTimeoutMs` | > 0 | "agentTimeoutMs must be positive" |
| `roundTimeoutMs` | > 0 | "roundTimeoutMs must be positive" |
| `gracePeriodMs` | >= 0 | "gracePeriodMs must be non-negative" |
| `onTimeout` | "kill-and-skip" or "abort" | "onTimeout must be 'kill-and-skip' or 'abort'" |
| `minAgents` | >= 1 | "minAgents must be at least 1" |
| `outputDir` | not null | "outputDir must not be null" |
| `agents` | not null | "agents must not be null" |

### Tests

```java
@Test void testDefaults_hasExpectedValues()
@Test void testValidation_negativeMaxRounds_throws()
@Test void testValidation_zeroMaxOutputSizeKb_throws()
@Test void testValidation_negativeMaxConcurrent_throws()
@Test void testValidation_zeroAgentTimeout_throws()
@Test void testValidation_invalidOnTimeout_throws()
@Test void testValidation_zeroMinAgents_throws()
@Test void testValidation_nullOutputDir_throws()
@Test void testValidation_nullAgents_throws()
@Test void testAgentsMap_isImmutable()
```

---

## Issue 2: AgentConfig Record

### Description
Create the per-agent configuration record.

### File: `src/main/java/dev/reviewarena/config/AgentConfig.java`

```java
package dev.reviewarena.config;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single AI agent.
 *
 * @param name     Agent identifier (e.g., "claude", "codex", "gemini")
 * @param command  Command and arguments to spawn the agent
 * @param flags    Agent-specific flags (auto-approve, etc.)
 * @param enabled  Whether this agent participates in tournaments
 */
public record AgentConfig(
    String name,
    List<String> command,
    Map<String, Object> flags,
    boolean enabled
) {
    /**
     * Compact constructor with validation and immutability.
     */
    public AgentConfig {
        if (name == null || name.isBlank()) {
            throw new ConfigException("Agent name must not be null or blank");
        }
        if (command == null || command.isEmpty()) {
            throw new ConfigException("Agent command must not be null or empty for agent: " + name);
        }
        // Make collections immutable
        command = List.copyOf(command);
        flags = flags != null ? Map.copyOf(flags) : Map.of();
    }

    /**
     * Creates an AgentConfig with enabled=true and empty flags.
     */
    public static AgentConfig of(String name, List<String> command) {
        return new AgentConfig(name, command, Map.of(), true);
    }

    /**
     * Creates an AgentConfig with custom flags and enabled=true.
     */
    public static AgentConfig of(String name, List<String> command, Map<String, Object> flags) {
        return new AgentConfig(name, command, flags, true);
    }
}
```

### Tests

```java
@Test void testOf_createsEnabledAgentWithEmptyFlags()
@Test void testOf_withFlags_createsEnabledAgent()
@Test void testValidation_nullName_throws()
@Test void testValidation_blankName_throws()
@Test void testValidation_nullCommand_throws()
@Test void testValidation_emptyCommand_throws()
@Test void testCommand_isImmutable()
@Test void testFlags_isImmutable()
@Test void testFlags_nullBecomesEmptyMap()
```

---

## Issue 3: ConfigLoader

### Description
Implement configuration loading with SmallRye Config integration.

### File: `src/main/java/dev/reviewarena/config/ConfigLoader.java`

```java
package dev.reviewarena.config;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads configuration from multiple sources with proper precedence.
 *
 * <p>Source priority (highest to lowest):
 * <ol>
 *   <li>CLI overrides (passed via {@link CliOverrides})</li>
 *   <li>Environment variables</li>
 *   <li>arena.yaml in current working directory</li>
 *   <li>application.yaml in classpath (built-in defaults)</li>
 * </ol>
 */
public class ConfigLoader {

    private static final String DEFAULT_CONFIG_FILE = "arena.yaml";

    /**
     * CLI overrides that take highest precedence.
     */
    public record CliOverrides(
        Integer maxRounds,
        Integer maxConcurrent,
        Path outputDir
    ) {
        public static CliOverrides none() {
            return new CliOverrides(null, null, null);
        }
    }

    /**
     * Loads configuration from all sources.
     *
     * @param configPath Path to arena.yaml (or null to use default)
     * @param overrides  CLI overrides
     * @return Fully resolved ArenaConfig
     * @throws ConfigException if configuration is invalid
     */
    public ArenaConfig load(Path configPath, CliOverrides overrides) {
        Path effectivePath = configPath != null ? configPath : Path.of(DEFAULT_CONFIG_FILE);

        SmallRyeConfig config = buildConfig(effectivePath);

        return buildArenaConfig(config, overrides);
    }

    /**
     * Loads configuration with default path and no overrides.
     */
    public ArenaConfig load() {
        return load(null, CliOverrides.none());
    }

    private SmallRyeConfig buildConfig(Path arenaYamlPath) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
            .addDefaultSources()                    // System props + env vars
            .addDefaultInterceptors()
            .withMapping(ArenaConfigMapping.class); // For type-safe mapping

        // Add classpath application.yaml (built-in defaults)
        builder.withSources(new YamlConfigSource(
            "application.yaml",
            ConfigLoader.class.getClassLoader().getResourceAsStream("application.yaml"),
            100  // Low ordinal = low priority
        ));

        // Add arena.yaml from filesystem if exists
        if (Files.exists(arenaYamlPath)) {
            try {
                builder.withSources(new YamlConfigSource(
                    arenaYamlPath.toString(),
                    Files.newInputStream(arenaYamlPath),
                    200  // Higher ordinal = higher priority
                ));
            } catch (IOException e) {
                throw new ConfigException("Failed to read config file: " + arenaYamlPath, e);
            }
        }

        return builder.build();
    }

    private ArenaConfig buildArenaConfig(SmallRyeConfig config, CliOverrides overrides) {
        // Load limits
        int maxRounds = overrides.maxRounds() != null
            ? overrides.maxRounds()
            : config.getOptionalValue("limits.max-rounds", Integer.class)
                    .orElse(ArenaConfig.DEFAULT_MAX_ROUNDS);

        int maxOutputSizeKb = config.getOptionalValue("limits.max-output-size-kb", Integer.class)
            .orElse(ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB);

        // Load execution settings
        int maxConcurrent = overrides.maxConcurrent() != null
            ? overrides.maxConcurrent()
            : config.getOptionalValue("execution.max-concurrent", Integer.class)
                    .orElse(ArenaConfig.DEFAULT_MAX_CONCURRENT);

        // Load timeouts
        long agentTimeoutMs = config.getOptionalValue("timeouts.agent-timeout-ms", Long.class)
            .orElse(ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS);

        long roundTimeoutMs = config.getOptionalValue("timeouts.round-timeout-ms", Long.class)
            .orElse(ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS);

        long gracePeriodMs = config.getOptionalValue("timeouts.grace-period-ms", Long.class)
            .orElse(ArenaConfig.DEFAULT_GRACE_PERIOD_MS);

        // Load timeout behavior
        String onTimeout = config.getOptionalValue("timeouts.on-timeout", String.class)
            .orElse(ArenaConfig.DEFAULT_ON_TIMEOUT);

        boolean preservePartialOutput = config.getOptionalValue("timeouts.preserve-partial-output", Boolean.class)
            .orElse(ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT);

        // Load tournament constraints
        int minAgents = config.getOptionalValue("tournament.min-agents", Integer.class)
            .orElse(ArenaConfig.DEFAULT_MIN_AGENTS);

        // Load output directory
        Path outputDir = overrides.outputDir() != null
            ? overrides.outputDir()
            : Path.of(config.getOptionalValue("output.dir", String.class)
                    .orElse(ArenaConfig.DEFAULT_OUTPUT_DIR));

        // Load agents
        Map<String, AgentConfig> agents = loadAgents(config);

        return new ArenaConfig(
            maxRounds,
            maxOutputSizeKb,
            maxConcurrent,
            agentTimeoutMs,
            roundTimeoutMs,
            gracePeriodMs,
            onTimeout,
            preservePartialOutput,
            minAgents,
            outputDir,
            agents
        );
    }

    private Map<String, AgentConfig> loadAgents(SmallRyeConfig config) {
        Map<String, AgentConfig> agents = new HashMap<>();

        // Get all keys starting with "agents."
        // Expected format: agents.<name>.command, agents.<name>.flags.<flag>
        for (String key : config.getPropertyNames()) {
            if (key.startsWith("agents.") && key.endsWith(".command")) {
                String agentName = extractAgentName(key);
                if (!agents.containsKey(agentName)) {
                    agents.put(agentName, loadAgentConfig(config, agentName));
                }
            }
        }

        return agents;
    }

    private String extractAgentName(String key) {
        // "agents.claude.command" -> "claude"
        String withoutPrefix = key.substring("agents.".length());
        int dot = withoutPrefix.indexOf('.');
        return dot > 0 ? withoutPrefix.substring(0, dot) : withoutPrefix;
    }

    private AgentConfig loadAgentConfig(SmallRyeConfig config, String agentName) {
        String prefix = "agents." + agentName;

        // Load command as list
        List<String> command = config.getOptionalValues(prefix + ".command", String.class)
            .orElseThrow(() -> new ConfigException(
                "Agent '" + agentName + "' missing required 'command' property"));

        // Load enabled flag (default true)
        boolean enabled = config.getOptionalValue(prefix + ".enabled", Boolean.class)
            .orElse(true);

        // Load flags as map
        Map<String, Object> flags = loadAgentFlags(config, prefix + ".flags");

        return new AgentConfig(agentName, command, flags, enabled);
    }

    private Map<String, Object> loadAgentFlags(SmallRyeConfig config, String prefix) {
        Map<String, Object> flags = new HashMap<>();

        for (String key : config.getPropertyNames()) {
            if (key.startsWith(prefix + ".")) {
                String flagName = key.substring(prefix.length() + 1);
                // Try to get as Boolean first, then String
                Optional<Boolean> boolVal = config.getOptionalValue(key, Boolean.class);
                if (boolVal.isPresent()) {
                    flags.put(flagName, boolVal.get());
                } else {
                    config.getOptionalValue(key, String.class)
                        .ifPresent(v -> flags.put(flagName, v));
                }
            }
        }

        return flags;
    }
}
```

### Key Design Decisions

1. **CliOverrides record**: Encapsulates CLI argument overrides cleanly
2. **SmallRye Config ordinals**: Lower ordinal = lower priority (application.yaml=100, arena.yaml=200)
3. **Agent discovery**: Iterates config keys to find all `agents.<name>.command` entries
4. **Immutable output**: ArenaConfig and AgentConfig enforce immutability

### Tests

```java
// Loading from defaults
@Test void testLoad_noConfigFile_usesDefaults()
@Test void testLoad_withApplicationYaml_loadsValues()

// Loading from arena.yaml
@Test void testLoad_arenaYamlExists_overridesDefaults()
@Test void testLoad_arenaYamlNotExists_usesDefaults()
@Test void testLoad_customConfigPath_loadsFromPath()

// CLI overrides
@Test void testLoad_cliOverridesMaxRounds_takePrecedence()
@Test void testLoad_cliOverridesMaxConcurrent_takePrecedence()
@Test void testLoad_cliOverridesOutputDir_takePrecedence()
@Test void testLoad_cliOverridesNull_usesConfigValue()

// Agent loading
@Test void testLoad_singleAgent_createsAgentConfig()
@Test void testLoad_multipleAgents_createsAllConfigs()
@Test void testLoad_agentWithFlags_loadsFlags()
@Test void testLoad_agentDisabled_setsEnabledFalse()
@Test void testLoad_agentMissingCommand_throws()

// Error handling
@Test void testLoad_invalidYaml_throwsConfigException()
@Test void testLoad_unreadableFile_throwsConfigException()
```

---

## Issue 4: application.yaml (Default Configuration)

### Description
Create the built-in default configuration file.

### File: `src/main/resources/application.yaml`

```yaml
# Review Arena - Default Configuration
# These values are used when not overridden by arena.yaml or CLI arguments

limits:
  max-rounds: 5
  max-output-size-kb: 500

execution:
  max-concurrent: 0  # 0 = unlimited

timeouts:
  agent-timeout-ms: 300000    # 5 minutes
  round-timeout-ms: 900000    # 15 minutes
  grace-period-ms: 5000       # 5 seconds
  on-timeout: kill-and-skip
  preserve-partial-output: false

tournament:
  min-agents: 2

output:
  dir: .arena

# Default agents (can be overridden in arena.yaml)
agents:
  claude:
    command:
      - claude
      - -p
      - "@prompt.md"
    flags:
      auto-approve: true
    enabled: true

  codex:
    command:
      - codex
      - exec
      - "@prompt.md"
    flags:
      auto-approve: true
    enabled: true

  gemini:
    command:
      - gemini
      - -p
      - "@prompt.md"
    flags:
      auto-approve: true
    enabled: true
```

---

## Issue 5: CLI Integration

### Description
Integrate ConfigLoader into ReviewArenaCli.

### Changes to `ReviewArenaCli.java`

```java
// Add import
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.config.ConfigLoader;
import dev.reviewarena.config.ConfigLoader.CliOverrides;

// Add field
private ConfigLoader configLoader = new ConfigLoader();

// Add setter for testing
void setConfigLoader(ConfigLoader loader) {
    this.configLoader = loader;
}

// Update call() method
@Override
public Integer call() {
    // ... existing code ...

    // Load configuration (after git validation, before tournament)
    CliOverrides overrides = new CliOverrides(
        maxRounds != 5 ? maxRounds : null,  // Only override if changed from default
        resolveExecutionMode() != 0 ? resolveExecutionMode() : null,
        !outputDir.equals(Path.of(".arena")) ? outputDir : null
    );
    ArenaConfig config = configLoader.load(configFile, overrides);

    // Use config for tournament...
    // TODO: Pass config to tournament orchestrator

    return 0;
}
```

### Integration Tests

```java
@Test void testCli_loadsConfigFromDefaultPath()
@Test void testCli_loadsConfigFromCustomPath()
@Test void testCli_cliArgsOverrideConfig()
@Test void testCli_invalidConfig_exitCode5()
```

---

## Issue 6: logback.xml (Logging Configuration)

### Description
Create logging configuration for consistent output.

### File: `src/main/resources/logback.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Console appender for user-facing output -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%msg%n</pattern>
        </encoder>
    </appender>

    <!-- Console appender with timestamps for debug mode -->
    <appender name="CONSOLE_DEBUG" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Root logger - INFO level by default -->
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>

    <!-- Package-specific logging -->
    <logger name="dev.reviewarena" level="INFO" />

    <!-- Quiet noisy libraries -->
    <logger name="org.eclipse.jgit" level="WARN" />
    <logger name="io.smallrye.config" level="WARN" />
</configuration>
```

---

## Implementation Order

| Order | Issue | Est. Complexity | Dependencies |
|-------|-------|-----------------|--------------|
| 1 | AgentConfig record | Low | None |
| 2 | ArenaConfig record | Medium | AgentConfig |
| 3 | application.yaml | Low | None |
| 4 | logback.xml | Low | None |
| 5 | ConfigLoader | High | ArenaConfig, AgentConfig |
| 6 | CLI Integration | Medium | ConfigLoader |

---

## Acceptance Criteria

### Milestone 1.2 Complete When:

- [ ] `ArenaConfig` record with validation passes all tests
- [ ] `AgentConfig` record with validation passes all tests
- [ ] `ConfigLoader` loads from application.yaml defaults
- [ ] `ConfigLoader` loads and merges arena.yaml overrides
- [ ] `ConfigLoader` applies CLI overrides correctly
- [ ] `application.yaml` contains sensible defaults
- [ ] `logback.xml` provides clean INFO-level output
- [ ] `ReviewArenaCli` integrates ConfigLoader
- [ ] Invalid config produces exit code 5
- [ ] All tests pass: `mvn verify`

---

## Environment Variable Mapping

For reference, SmallRye Config automatically maps YAML keys to environment variables:

| YAML Key | Environment Variable |
|----------|---------------------|
| `limits.max-rounds` | `LIMITS_MAX_ROUNDS` |
| `limits.max-output-size-kb` | `LIMITS_MAX_OUTPUT_SIZE_KB` |
| `execution.max-concurrent` | `EXECUTION_MAX_CONCURRENT` |
| `timeouts.agent-timeout-ms` | `TIMEOUTS_AGENT_TIMEOUT_MS` |
| `output.dir` | `OUTPUT_DIR` |

The existing `REVIEW_ARENA_*` environment variables in the CLI will continue to work as picocli defaults, providing an additional layer of customization.
