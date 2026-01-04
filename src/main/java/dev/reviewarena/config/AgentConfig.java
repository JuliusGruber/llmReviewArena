package dev.reviewarena.config;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single AI agent.
 *
 * @param name     Agent identifier (e.g., "claude", "codex", "gemini", "fast-reviewer")
 * @param type     Agent type for flag translation (e.g., "claude", "codex", "gemini")
 * @param command  Command and arguments to spawn the agent
 * @param flags    Agent-specific flags (auto-approve, etc.)
 * @param enabled  Whether this agent participates in tournaments
 * @param docker   Docker configuration (null-safe, defaults to disabled)
 */
public record AgentConfig(
    String name,
    String type,
    List<String> command,
    Map<String, Object> flags,
    boolean enabled,
    DockerConfig docker
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
        // Make docker non-null with disabled default
        docker = docker != null ? docker : DockerConfig.disabled();
    }

    /**
     * Creates an AgentConfig with enabled=true, empty flags, and Docker disabled.
     * Type is set to null (should be inferred by ConfigLoader).
     */
    public static AgentConfig of(String name, List<String> command) {
        return new AgentConfig(name, null, command, Map.of(), true, DockerConfig.disabled());
    }

    /**
     * Creates an AgentConfig with explicit type, enabled=true, empty flags, and Docker disabled.
     */
    public static AgentConfig of(String name, String type, List<String> command) {
        return new AgentConfig(name, type, command, Map.of(), true, DockerConfig.disabled());
    }

    /**
     * Creates an AgentConfig with custom flags, enabled=true, and Docker disabled.
     * Type is set to null (should be inferred by ConfigLoader).
     */
    public static AgentConfig of(String name, List<String> command, Map<String, Object> flags) {
        return new AgentConfig(name, null, command, flags, true, DockerConfig.disabled());
    }

    /**
     * Creates an AgentConfig with explicit type, custom flags, enabled=true, and Docker disabled.
     */
    public static AgentConfig of(String name, String type, List<String> command, Map<String, Object> flags) {
        return new AgentConfig(name, type, command, flags, true, DockerConfig.disabled());
    }

    /**
     * Creates a disabled AgentConfig (for testing scenarios where agent is configured but disabled).
     */
    public static AgentConfig disabled(String name, List<String> command) {
        return new AgentConfig(name, null, command, Map.of(), false, DockerConfig.disabled());
    }
}
