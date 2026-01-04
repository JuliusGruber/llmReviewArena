package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Checks if Docker is available and running.
 * Implements DockerChecker for production use.
 */
public class DockerAvailabilityChecker implements DockerChecker {

    private static final Logger log = LoggerFactory.getLogger(DockerAvailabilityChecker.class);

    /**
     * Verifies Docker is installed and the daemon is running.
     *
     * @throws ConfigException if Docker is not available
     */
    @Override
    public void requireDocker() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info")
                .redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new ConfigException(
                    "Docker check timed out. Is Docker daemon running?");
            }

            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new ConfigException(
                    "Docker is not available: " + output.trim());
            }

            log.debug("Docker is available");

        } catch (IOException e) {
            throw new ConfigException(
                "Docker is not installed or not in PATH: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfigException("Docker check was interrupted");
        }
    }

    /**
     * Checks if a Docker image exists locally.
     */
    @Override
    public boolean imageExists(String image) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", image)
                .redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
