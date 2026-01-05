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
    Map<String, AgentConfig> agents
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
        // Make agents map immutable
        agents = Map.copyOf(agents);
    }

    /**
     * Creates a config with all default values from application.yaml.
     * Delegates to ConfigLoader to ensure single source of truth.
     */
    public static ArenaConfig defaults() {
        return new ConfigLoader().load();
    }
}
