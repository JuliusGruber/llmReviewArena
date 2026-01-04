package dev.reviewarena.config;

/**
 * Docker configuration for an agent.
 *
 * @param enabled     Whether to run this agent in a Docker container
 * @param image       Docker image to use (null = use default for agent)
 * @param memory      Optional memory limit (e.g., "4g")
 * @param cpus        Optional CPU limit (e.g., "2")
 * @param networkMode Optional network mode (null = bridge default, "host" = Linux-only)
 */
public record DockerConfig(
    boolean enabled,
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
        return new DockerConfig(false, null, null, null, null);
    }
}
