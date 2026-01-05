package dev.reviewarena.config;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private final Path baseDir;

    // Loaded from config at runtime (fail-fast if missing)
    private Set<String> knownTypes;
    private Map<String, List<String>> typeAliases;

    /**
     * Creates a ConfigLoader using the current working directory as base.
     */
    public ConfigLoader() {
        this(Path.of("."));
    }

    /**
     * Creates a ConfigLoader with a specified base directory.
     *
     * @param baseDir the base directory for resolving config files
     */
    public ConfigLoader(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * CLI overrides that take highest precedence.
     */
    public record CliOverrides(
        Integer maxRounds,
        Integer maxConcurrent,
        Boolean showAgentOutput,
        Path outputDir
    ) {
        public static CliOverrides none() {
            return new CliOverrides(null, null, null, null);
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
        return load(baseDir.resolve(DEFAULT_CONFIG_FILE), CliOverrides.none());
    }

    private SmallRyeConfig buildConfig(Path arenaYamlPath) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
            .addDefaultSources()           // System props + env vars
            .addDefaultInterceptors();

        // Add classpath application.yaml (built-in defaults)
        try (InputStream appYaml = ConfigLoader.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            if (appYaml != null) {
                String yamlContent = new String(appYaml.readAllBytes(), StandardCharsets.UTF_8);
                builder.withSources(new YamlConfigSource(
                    "application.yaml",
                    yamlContent,
                    100  // Low ordinal = low priority
                ));
            }
        } catch (IOException e) {
            throw new ConfigException("Failed to read built-in application.yaml", e);
        }

        // Add arena.yaml from filesystem if exists
        if (Files.exists(arenaYamlPath)) {
            try {
                String yamlContent = Files.readString(arenaYamlPath, StandardCharsets.UTF_8);
                builder.withSources(new YamlConfigSource(
                    arenaYamlPath.toString(),
                    yamlContent,
                    200  // Higher ordinal = higher priority
                ));
            } catch (IOException e) {
                throw new ConfigException("Failed to read config file: " + arenaYamlPath, e);
            }
        }

        return builder.build();
    }

    private ArenaConfig buildArenaConfig(SmallRyeConfig config, CliOverrides overrides) {
        // Load metadata from config (fail-fast if missing)
        this.knownTypes = loadKnownTypes(config);

        // Load limits
        int maxRounds = overrides.maxRounds() != null
            ? overrides.maxRounds()
            : config.getOptionalValue("limits.rounds", Integer.class)
                    .orElse(ArenaConfig.DEFAULT_MAX_ROUNDS);

        // Cap rounds at 5 maximum
        if (maxRounds > 5) {
            maxRounds = 5;
        }

        int maxOutputSizeKb = config.getOptionalValue("limits.max-output-size-kb", Integer.class)
            .orElse(ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB);

        // Load execution settings
        int maxConcurrent = overrides.maxConcurrent() != null
            ? overrides.maxConcurrent()
            : config.getOptionalValue("execution.max-concurrent", Integer.class)
                    .orElse(ArenaConfig.DEFAULT_MAX_CONCURRENT);

        boolean showAgentOutput = overrides.showAgentOutput() != null
            ? overrides.showAgentOutput()
            : config.getOptionalValue("execution.show-agent-output", Boolean.class)
                    .orElse(ArenaConfig.DEFAULT_SHOW_AGENT_OUTPUT);

        // Load timeouts
        long agentTimeoutMs = config.getOptionalValue("timeouts.agent-timeout-ms", Long.class)
            .orElse(ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS);

        long roundTimeoutMs = config.getOptionalValue("timeouts.round-timeout-ms", Long.class)
            .orElse(ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS);

        long gracePeriodMs = config.getOptionalValue("timeouts.grace-period-ms", Long.class)
            .orElse(ArenaConfig.DEFAULT_GRACE_PERIOD_MS);

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
            showAgentOutput,
            agentTimeoutMs,
            roundTimeoutMs,
            gracePeriodMs,
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
            if (key.startsWith("agents.") && key.contains(".command")) {
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
        // "agents.claude.command[0]" -> "claude"
        String withoutPrefix = key.substring("agents.".length());
        int dot = withoutPrefix.indexOf('.');
        return dot > 0 ? withoutPrefix.substring(0, dot) : withoutPrefix;
    }

    private AgentConfig loadAgentConfig(SmallRyeConfig config, String agentName) {
        String prefix = "agents." + agentName;

        // Load command as list
        Optional<List<String>> commandOpt = config.getOptionalValues(prefix + ".command", String.class);
        if (commandOpt.isEmpty() || commandOpt.get().isEmpty()) {
            throw new ConfigException(
                "Agent '" + agentName + "' missing required 'command' property");
        }
        List<String> command = commandOpt.get();

        // Load type (explicit or inferred from name)
        String type = config.getOptionalValue(prefix + ".type", String.class)
            .map(String::toLowerCase)
            .orElseGet(() -> inferTypeFromName(agentName));

        // Validate type
        if (type != null && !KNOWN_TYPES.contains(type)) {
            throw new ConfigException(
                "Agent '" + agentName + "' has unknown type '" + type + "'. " +
                "Valid types are: " + KNOWN_TYPES);
        }
        if (type == null) {
            throw new ConfigException(
                "Agent '" + agentName + "' has no 'type' specified and type could not be inferred from name. " +
                "Add 'type: <type>' to the agent configuration. Valid types are: " + KNOWN_TYPES);
        }

        // Load enabled flag (default true)
        boolean enabled = config.getOptionalValue(prefix + ".enabled", Boolean.class)
            .orElse(true);

        // Load flags as map
        Map<String, Object> flags = loadAgentFlags(config, prefix + ".flags");

        // Load docker config
        DockerConfig docker = loadDockerConfig(config, prefix + ".docker");

        return new AgentConfig(agentName, type, command, flags, enabled, docker);
    }

    /**
     * Infers agent type from agent name for backward compatibility.
     *
     * @param agentName the agent name
     * @return inferred type, or null if not recognizable
     */
    private String inferTypeFromName(String agentName) {
        String lowerName = agentName.toLowerCase();
        if (lowerName.startsWith("claude")) {
            return "claude";
        } else if (lowerName.startsWith("codex")) {
            return "codex";
        } else if (lowerName.startsWith("gemini")) {
            return "gemini";
        }
        return null;
    }

    /**
     * Loads Docker configuration for an agent using SmallRyeConfig.
     */
    private DockerConfig loadDockerConfig(SmallRyeConfig config, String prefix) {
        // Check if docker.enabled exists and is true
        boolean enabled = config.getOptionalValue(prefix + ".enabled", Boolean.class)
            .orElse(false);

        if (!enabled) {
            return DockerConfig.disabled();
        }

        // Sandbox mode uses 'docker sandbox run' (Docker Desktop feature)
        boolean sandbox = config.getOptionalValue(prefix + ".sandbox", Boolean.class)
            .orElse(false);

        String image = config.getOptionalValue(prefix + ".image", String.class)
            .orElse(null);
        String memory = config.getOptionalValue(prefix + ".memory", String.class)
            .orElse(null);
        String cpus = config.getOptionalValue(prefix + ".cpus", String.class)
            .orElse(null);
        String networkMode = config.getOptionalValue(prefix + ".network-mode", String.class)
            .orElse(null);

        return new DockerConfig(true, sandbox, image, memory, cpus, networkMode);
    }

    /**
     * Loads known agent types from meta.known-agent-types config.
     * Fails fast if missing or empty.
     */
    private Set<String> loadKnownTypes(SmallRyeConfig config) {
        Optional<List<String>> types = config.getOptionalValues("meta.known-agent-types", String.class);
        if (types.isEmpty() || types.get().isEmpty()) {
            throw new ConfigException(
                "Missing required 'meta.known-agent-types' in application.yaml. " +
                "Example: meta.known-agent-types: [claude, codex, gemini]");
        }
        return new HashSet<>(types.get());
    }

    private Map<String, Object> loadAgentFlags(SmallRyeConfig config, String prefix) {
        Map<String, Object> flags = new HashMap<>();

        for (String key : config.getPropertyNames()) {
            if (key.startsWith(prefix + ".")) {
                String flagName = key.substring(prefix.length() + 1);
                // Skip array indices in flag names
                if (flagName.contains("[")) {
                    continue;
                }
                // Get as String first, then detect type
                Optional<String> strVal = config.getOptionalValue(key, String.class);
                if (strVal.isPresent()) {
                    String value = strVal.get();
                    // Check if it's a boolean value
                    if ("true".equalsIgnoreCase(value)) {
                        flags.put(flagName, Boolean.TRUE);
                    } else if ("false".equalsIgnoreCase(value)) {
                        flags.put(flagName, Boolean.FALSE);
                    } else {
                        flags.put(flagName, value);
                    }
                }
            }
        }

        return flags;
    }
}
