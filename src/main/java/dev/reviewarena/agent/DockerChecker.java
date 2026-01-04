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
}
