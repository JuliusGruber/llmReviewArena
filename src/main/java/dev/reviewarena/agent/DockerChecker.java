package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;

/**
 * Interface for Docker availability checking.
 * Enables dependency injection for testing.
 */
public interface DockerChecker {

    /**
     * Verifies Docker is installed and the daemon is running.
     *
     * @throws ConfigException if Docker is not available
     */
    void requireDocker();

    /**
     * Checks if a Docker image exists locally.
     *
     * @param image the image name (e.g., "ghcr.io/zeeno-atl/claude-code:latest")
     * @return true if the image exists locally, false otherwise
     */
    boolean imageExists(String image);
}
