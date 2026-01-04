package dev.reviewarena.config;

/**
 * Docker configuration for an agent.
 *
 * @param enabled     Whether to run this agent in a Docker container
 * @param sandbox     Whether to use Docker Desktop Sandbox ('docker sandbox run') instead of 'docker run'.
 *                    Sandbox mode is recommended for claude/gemini as it handles credentials and setup automatically.
 *                    Requires Docker Desktop with sandbox support.
 * @param image       Docker image to use (null = use default for agent). Ignored when sandbox=true.
 * @param memory      Optional memory limit (e.g., "4g"). Ignored when sandbox=true.
 * @param cpus        Optional CPU limit (e.g., "2"). Ignored when sandbox=true.
 * @param networkMode Optional network mode (null = bridge default, "host" = Linux-only). Ignored when sandbox=true.
 */
public record DockerConfig(
    boolean enabled,
    boolean sandbox,
    String image,
    String memory,
    String cpus,
    String networkMode
) {
    /**
     * Creates a disabled DockerConfig.
     * Use this factory method when Docker is not needed for an agent.
     */
    public static DockerConfig disabled() {
        return new DockerConfig(false, false, null, null, null, null);
    }
}
