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
