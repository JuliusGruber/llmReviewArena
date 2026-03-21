package dev.reviewarena.config;

import java.nio.file.Path;
import java.util.List;
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
    boolean showAgentOutput,

    // Timeouts (milliseconds)
    long agentTimeoutMs,
    long roundTimeoutMs,
    long gracePeriodMs,

    // Tournament constraints
    int minAgents,

    // Output
    Path outputDir,

    // Agents
    Map<String, AgentConfig> agents,

    // Review agent selection (shorthand override)
    List<String> reviewAgents
) {
    // All defaults now come from application.yaml (single source of truth)
    // See ConfigLoader for fail-fast loading from YAML config

    /**
     * Compact constructor with validation.
     */
    public ArenaConfig {
        if (maxRounds < 1) {
            throw new ConfigException(
                "maxRounds must be at least 1 (cross-pollination requires at least one round). Got: " + maxRounds);
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
        if (minAgents < 1) {
            throw new ConfigException("minAgents must be at least 1, got: " + minAgents);
        }
        if (outputDir == null) {
            throw new ConfigException("outputDir must not be null");
        }
        if (agents == null) {
            throw new ConfigException("agents must not be null");
        }
        if (reviewAgents == null) {
            throw new ConfigException("reviewAgents must not be null (use List.of() for empty)");
        }
        // Make agents map immutable
        agents = Map.copyOf(agents);
        // Make reviewAgents immutable and validate all names exist in agents map
        reviewAgents = List.copyOf(reviewAgents);
        for (String name : reviewAgents) {
            if (!agents.containsKey(name)) {
                throw new ConfigException(
                    "review-agents references unknown agent '" + name + "'. " +
                    "Available agents: " + agents.keySet());
            }
        }
    }

    /**
     * Returns the list of agent names to use for review rounds.
     *
     * <p>If {@code reviewAgents} is non-empty, returns it as-is (preserving order).
     * Otherwise, falls back to all enabled agents sorted alphabetically.
     *
     * @return ordered list of agent names for tournament rounds
     */
    public List<String> reviewAgentNames() {
        if (!reviewAgents.isEmpty()) {
            return reviewAgents;
        }
        return agents.entrySet().stream()
            .filter(e -> e.getValue().enabled())
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }

    /**
     * Creates a config with all default values from application.yaml.
     * Delegates to ConfigLoader to ensure single source of truth.
     * Does not load arena.yaml overrides - use ConfigLoader.load() for that.
     */
    public static ArenaConfig defaults() {
        return new ConfigLoader().loadPureDefaults();
    }
}
