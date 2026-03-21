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

    /**
     * Loads pure defaults from application.yaml only (no arena.yaml overrides).
     * Use this for testing or when you need the built-in defaults without
     * any filesystem config overrides.
     */
    public ArenaConfig loadPureDefaults() {
        SmallRyeConfig config = buildConfigFromApplicationYamlOnly();
        return buildArenaConfig(config, CliOverrides.none());
    }

    private SmallRyeConfig buildConfigFromApplicationYamlOnly() {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
            .addDefaultSources()
            .addDefaultInterceptors();

        // Only add classpath application.yaml (built-in defaults)
        try (InputStream appYaml = ConfigLoader.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            if (appYaml != null) {
                String yamlContent = new String(appYaml.readAllBytes(), StandardCharsets.UTF_8);
                builder.withSources(new YamlConfigSource(
                    "application.yaml",
                    yamlContent,
                    100
                ));
            }
        } catch (IOException e) {
            throw new ConfigException("Failed to read built-in application.yaml", e);
        }

        return builder.build();
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
        this.typeAliases = loadTypeAliases(config);

        // Load limits (fail-fast if missing from application.yaml)
        int maxRounds = overrides.maxRounds() != null
            ? overrides.maxRounds()
            : config.getOptionalValue("limits.rounds", Integer.class)
                    .orElseThrow(() -> new ConfigException(
                        "Missing required 'limits.rounds' in application.yaml"));

        // Cap rounds at 5 maximum
        if (maxRounds > 5) {
            maxRounds = 5;
        }

        int maxOutputSizeKb = config.getOptionalValue("limits.max-output-size-kb", Integer.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'limits.max-output-size-kb' in application.yaml"));

        // Load execution settings (fail-fast if missing from application.yaml)
        int maxConcurrent = overrides.maxConcurrent() != null
            ? overrides.maxConcurrent()
            : config.getOptionalValue("execution.max-concurrent", Integer.class)
                    .orElseThrow(() -> new ConfigException(
                        "Missing required 'execution.max-concurrent' in application.yaml"));

        boolean showAgentOutput = overrides.showAgentOutput() != null
            ? overrides.showAgentOutput()
            : config.getOptionalValue("execution.show-agent-output", Boolean.class)
                    .orElseThrow(() -> new ConfigException(
                        "Missing required 'execution.show-agent-output' in application.yaml"));

        // Load timeouts (fail-fast if missing from application.yaml)
        long agentTimeoutMs = config.getOptionalValue("timeouts.agent-timeout-ms", Long.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'timeouts.agent-timeout-ms' in application.yaml"));

        long roundTimeoutMs = config.getOptionalValue("timeouts.round-timeout-ms", Long.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'timeouts.round-timeout-ms' in application.yaml"));

        long gracePeriodMs = config.getOptionalValue("timeouts.grace-period-ms", Long.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'timeouts.grace-period-ms' in application.yaml"));

        // Load tournament constraints (fail-fast if missing from application.yaml)
        int minAgents = config.getOptionalValue("tournament.min-agents", Integer.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'tournament.min-agents' in application.yaml"));

        // Load output directory (fail-fast if missing from application.yaml)
        Path outputDir = overrides.outputDir() != null
            ? overrides.outputDir()
            : Path.of(config.getOptionalValue("output.dir", String.class)
                    .orElseThrow(() -> new ConfigException(
                        "Missing required 'output.dir' in application.yaml")));

        // Load agents
        Map<String, AgentConfig> agents = loadAgents(config);

        // Load review-agents shorthand (optional) - these are type shorthands, not instance names
        List<String> reviewAgentShorthands = config.getOptionalValues("review-agents", String.class)
            .map(list -> list.stream().map(String::trim).filter(s -> !s.isEmpty()).toList())
            .orElse(List.of());

        // Expand type shorthands into numbered instances
        List<String> reviewAgents;
        if (!reviewAgentShorthands.isEmpty()) {
            reviewAgents = expandReviewAgents(reviewAgentShorthands, agents);
        } else {
            reviewAgents = List.of();
        }

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
            agents,
            reviewAgents
        );
    }

    /**
     * Expands type shorthands in review-agents into numbered agent instances.
     *
     * <p>Each shorthand (e.g., "claude") is looked up as a template in the agents map.
     * A numbered instance is created (e.g., "claude-1", "claude-2") with the template's
     * config but always enabled. Template keys used for expansion are removed from the
     * agents map to prevent fallback pollution.
     *
     * @param shorthands the list of type shorthands from review-agents config
     * @param agents     the mutable agents map (templates will be removed after expansion)
     * @return the list of expanded instance names
     * @throws ConfigException if a shorthand references "synthesis" or an unknown template
     */
    private List<String> expandReviewAgents(List<String> shorthands, Map<String, AgentConfig> agents) {
        // Track which templates were used and per-type counters
        Map<String, Integer> typeCounters = new HashMap<>();
        Set<String> usedTemplates = new HashSet<>();
        List<String> expandedNames = new java.util.ArrayList<>();

        for (String shorthand : shorthands) {
            if ("synthesis".equals(shorthand)) {
                throw new ConfigException(
                    "review-agents cannot use reserved name 'synthesis'. " +
                    "Use agent type names like 'claude', 'codex', 'gemini'.");
            }

            AgentConfig template = agents.get(shorthand);
            if (template == null) {
                throw new ConfigException(
                    "review-agents references unknown agent type '" + shorthand + "'. " +
                    "Available templates: " + agents.keySet());
            }

            // Generate numbered instance name
            int counter = typeCounters.merge(shorthand, 1, Integer::sum);
            String instanceName = shorthand + "-" + counter;

            // Create instance config: always enabled, copies template's command/flags/docker
            AgentConfig instance = new AgentConfig(
                instanceName,
                template.type(),
                template.command(),
                template.flags(),
                true,  // Instances are always enabled
                template.docker()
            );

            agents.put(instanceName, instance);
            expandedNames.add(instanceName);
            usedTemplates.add(shorthand);
        }

        // Remove used templates from agents map (prevents fallback pollution)
        for (String template : usedTemplates) {
            agents.remove(template);
        }

        return expandedNames;
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
        if (type != null && !knownTypes.contains(type)) {
            throw new ConfigException(
                "Agent '" + agentName + "' has unknown type '" + type + "'. " +
                "Valid types are: " + knownTypes);
        }
        if (type == null) {
            throw new ConfigException(
                "Agent '" + agentName + "' has no 'type' specified and type could not be inferred from name. " +
                "Add 'type: <type>' to the agent configuration. Valid types are: " + knownTypes);
        }

        // Load enabled flag (default from agent-defaults)
        boolean defaultEnabled = config.getOptionalValue("agent-defaults.enabled", Boolean.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'agent-defaults.enabled' in application.yaml"));
        boolean enabled = config.getOptionalValue(prefix + ".enabled", Boolean.class)
            .orElse(defaultEnabled);

        // Load flags as map
        Map<String, Object> flags = loadAgentFlags(config, prefix + ".flags");

        // Load docker config
        DockerConfig docker = loadDockerConfig(config, prefix + ".docker");

        return new AgentConfig(agentName, type, command, flags, enabled, docker);
    }

    /**
     * Infers agent type from agent name using type-aliases from config.
     *
     * @param agentName the agent name
     * @return inferred type, or null if not recognizable
     */
    private String inferTypeFromName(String agentName) {
        String lowerName = agentName.toLowerCase();
        for (var entry : typeAliases.entrySet()) {
            for (String prefix : entry.getValue()) {
                if (lowerName.startsWith(prefix.toLowerCase())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Loads Docker configuration for an agent using SmallRyeConfig.
     */
    private DockerConfig loadDockerConfig(SmallRyeConfig config, String prefix) {
        // Get defaults from agent-defaults.docker
        boolean defaultEnabled = config.getOptionalValue("agent-defaults.docker.enabled", Boolean.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'agent-defaults.docker.enabled' in application.yaml"));
        boolean defaultSandbox = config.getOptionalValue("agent-defaults.docker.sandbox", Boolean.class)
            .orElseThrow(() -> new ConfigException(
                "Missing required 'agent-defaults.docker.sandbox' in application.yaml"));

        // Check if docker.enabled exists and is true
        boolean enabled = config.getOptionalValue(prefix + ".enabled", Boolean.class)
            .orElse(defaultEnabled);

        if (!enabled) {
            return DockerConfig.disabled();
        }

        // Sandbox mode uses 'docker sandbox run' (Docker Desktop feature)
        boolean sandbox = config.getOptionalValue(prefix + ".sandbox", Boolean.class)
            .orElse(defaultSandbox);

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

    /**
     * Loads type aliases from meta.type-aliases config.
     * Returns a map of type -> list of prefixes that map to that type.
     * Fails fast if missing.
     */
    private Map<String, List<String>> loadTypeAliases(SmallRyeConfig config) {
        Map<String, List<String>> aliases = new HashMap<>();

        // Find all keys matching meta.type-aliases.<type>[n]
        Set<String> typeNames = new HashSet<>();
        for (String key : config.getPropertyNames()) {
            if (key.startsWith("meta.type-aliases.")) {
                // Extract type name: "meta.type-aliases.claude[0]" -> "claude"
                String rest = key.substring("meta.type-aliases.".length());
                int bracket = rest.indexOf('[');
                String typeName = bracket > 0 ? rest.substring(0, bracket) : rest;
                typeNames.add(typeName);
            }
        }

        if (typeNames.isEmpty()) {
            throw new ConfigException(
                "Missing required 'meta.type-aliases' in application.yaml. " +
                "Example: meta.type-aliases.claude: [claude, anthropic]");
        }

        // Load alias lists for each type
        for (String typeName : typeNames) {
            Optional<List<String>> aliasList = config.getOptionalValues(
                "meta.type-aliases." + typeName, String.class);
            if (aliasList.isPresent() && !aliasList.get().isEmpty()) {
                aliases.put(typeName, aliasList.get());
            }
        }

        return aliases;
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
