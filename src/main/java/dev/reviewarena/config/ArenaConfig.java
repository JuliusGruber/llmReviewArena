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
