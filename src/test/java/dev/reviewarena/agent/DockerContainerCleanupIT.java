package dev.reviewarena.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Docker container cleanup.
 * Requires Docker to be available.
 */
class DockerContainerCleanupIT {

    private static boolean dockerAvailable;

    @BeforeAll
    static void checkDocker() {
        try {
            Process p = new ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start();
            dockerAvailable = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            dockerAvailable = false;
        }
    }

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        DockerContainerRegistry.clearForTesting();
    }

    @Test
    void stopAllContainers_stopsRunningContainer() throws Exception {
        Assumptions.assumeTrue(dockerAvailable, "Docker not available");

        // Start a simple container that runs indefinitely
        String containerName = "test-cleanup-" + System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "run", "-d", "--rm", "--name", containerName,
            "alpine:latest", "sleep", "3600"
        );
        Process startProcess = pb.start();
        assertThat(startProcess.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(startProcess.exitValue()).isEqualTo(0);

        try {
            // Register the container
            DockerContainerRegistry.register(containerName);

            // Verify container is running
            assertThat(isContainerRunning(containerName)).isTrue();

            // Stop all containers
            DockerContainerRegistry.stopAllContainers();

            // Verify container is stopped
            Thread.sleep(1000); // Give Docker time to clean up
            assertThat(isContainerRunning(containerName)).isFalse();
        } finally {
            // Cleanup in case test fails
            new ProcessBuilder("docker", "rm", "-f", containerName)
                .start().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopContainer_stopsRunningContainer() throws Exception {
        Assumptions.assumeTrue(dockerAvailable, "Docker not available");

        // Start a simple container that runs indefinitely
        String containerName = "test-stop-" + System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "run", "-d", "--rm", "--name", containerName,
            "alpine:latest", "sleep", "3600"
        );
        Process startProcess = pb.start();
        assertThat(startProcess.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(startProcess.exitValue()).isEqualTo(0);

        try {
            // Verify container is running
            assertThat(isContainerRunning(containerName)).isTrue();

            // Stop the container directly
            DockerContainerRegistry.stopContainer(containerName);

            // Verify container is stopped
            Thread.sleep(1000); // Give Docker time to clean up
            assertThat(isContainerRunning(containerName)).isFalse();
        } finally {
            // Cleanup in case test fails
            new ProcessBuilder("docker", "rm", "-f", containerName)
                .start().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopContainer_handlesNonExistentContainer() throws Exception {
        Assumptions.assumeTrue(dockerAvailable, "Docker not available");

        // Should not throw even when container doesn't exist
        DockerContainerRegistry.stopContainer("nonexistent-container-" + System.currentTimeMillis());
        // No exception = success
    }

    private boolean isContainerRunning(String containerName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "ps", "-q", "--filter", "name=^" + containerName + "$"
        );
        Process process = pb.start();
        process.waitFor(10, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes()).trim();
        return !output.isEmpty();
    }
}
