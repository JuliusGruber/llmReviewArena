package dev.reviewarena.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Registry for tracking active Docker containers to ensure cleanup on JVM shutdown.
 *
 * <p>When the JVM receives a shutdown signal (Ctrl+C, SIGTERM, terminal close),
 * the registered shutdown hook stops all tracked containers using {@code docker stop}.
 * This prevents orphaned containers when the application terminates abruptly.
 *
 * <p>This class is thread-safe and uses a singleton pattern because JVM shutdown
 * hooks are global by nature.
 *
 * <p><b>Note:</b> Only containers started with {@code docker run} (not sandbox mode)
 * need to be tracked. Sandbox mode handles cleanup automatically.
 *
 * <p><b>Blocking behavior:</b> The {@link #stopContainer(String)} method may block
 * for up to {@code STOP_TIMEOUT_SECONDS + COMMAND_TIMEOUT_SECONDS} (8 seconds by default)
 * per container. When called from {@code AgentProcess.close()}, this ensures reliable
 * container cleanup but adds latency to the close operation. This is acceptable because:
 * <ul>
 *   <li>Reliable cleanup is more important than fast close() in Docker mode</li>
 *   <li>The timeout is bounded and predictable</li>
 *   <li>Normal (non-Docker) execution is unaffected</li>
 * </ul>
 */
public final class DockerContainerRegistry {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerRegistry.class);

    /**
     * Timeout in seconds for graceful container stop before SIGKILL.
     * This is passed to {@code docker stop -t <timeout>}.
     */
    private static final int STOP_TIMEOUT_SECONDS = 5;

    /**
     * Maximum time to wait for a single {@code docker stop} command to complete.
     * This should be slightly longer than STOP_TIMEOUT_SECONDS to account for
     * Docker CLI overhead.
     */
    private static final int COMMAND_TIMEOUT_SECONDS = STOP_TIMEOUT_SECONDS + 3;

    private static final Set<String> activeContainers = ConcurrentHashMap.newKeySet();
    private static volatile boolean shutdownHookRegistered = false;
    private static final Object lock = new Object();

    // Prevent instantiation
    private DockerContainerRegistry() {}

    /**
     * Registers a Docker container for cleanup on JVM shutdown.
     *
     * <p>Call this method after successfully starting a Docker container
     * (after {@code ProcessBuilder.start()} returns). The container name
     * should match the {@code --name} argument passed to {@code docker run}.
     *
     * <p>The first call to this method lazily registers the JVM shutdown hook.
     *
     * @param containerName the Docker container name (e.g., "claude", "gemini-synthesis")
     */
    public static void register(String containerName) {
        if (containerName == null || containerName.isBlank()) {
            log.warn("Attempted to register null or blank container name");
            return;
        }

        ensureShutdownHookRegistered();
        activeContainers.add(containerName);
        log.debug("Registered Docker container for cleanup: {}", containerName);
    }

    /**
     * Unregisters a Docker container from cleanup tracking.
     *
     * <p>Call this method when a container has been stopped normally
     * (e.g., in {@code AgentProcess.close()}). This prevents unnecessary
     * stop attempts during shutdown.
     *
     * @param containerName the Docker container name to unregister
     */
    public static void unregister(String containerName) {
        if (containerName == null || containerName.isBlank()) {
            return;
        }

        boolean removed = activeContainers.remove(containerName);
        if (removed) {
            log.debug("Unregistered Docker container from cleanup: {}", containerName);
        }
    }

    /**
     * Returns the number of currently registered containers.
     * Primarily for testing.
     */
    static int getActiveCount() {
        return activeContainers.size();
    }

    /**
     * Clears all registered containers without stopping them.
     * Primarily for testing to reset state between tests.
     */
    static void clearForTesting() {
        activeContainers.clear();
    }

    /**
     * Ensures the shutdown hook is registered exactly once.
     * Uses double-checked locking for thread-safe lazy initialization.
     */
    private static void ensureShutdownHookRegistered() {
        if (shutdownHookRegistered) {
            return;
        }

        synchronized (lock) {
            if (!shutdownHookRegistered) {
                Thread shutdownHook = new Thread(
                    DockerContainerRegistry::stopAllContainers,
                    "docker-container-cleanup"
                );
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                shutdownHookRegistered = true;
                log.debug("Registered Docker container cleanup shutdown hook");
            }
        }
    }

    /**
     * Stops all registered Docker containers.
     *
     * <p>Called by the JVM shutdown hook. Stops containers in parallel using
     * virtual threads to minimize total shutdown time. Errors are logged but
     * do not prevent stopping other containers.
     *
     * <p>This method is also package-private for testing purposes.
     */
    static void stopAllContainers() {
        // Take a snapshot to avoid concurrent modification
        Set<String> containersToStop = Set.copyOf(activeContainers);

        if (containersToStop.isEmpty()) {
            log.debug("No Docker containers to clean up");
            return;
        }

        log.info("Stopping {} Docker container(s) on shutdown: {}",
            containersToStop.size(), containersToStop);

        // Stop containers in parallel using virtual threads
        var threads = containersToStop.stream()
            .map(name -> Thread.ofVirtual()
                .name("docker-stop-" + name)
                .start(() -> stopContainer(name)))
            .toList();

        // Wait for all stop commands to complete
        for (Thread thread : threads) {
            try {
                thread.join(COMMAND_TIMEOUT_SECONDS * 1000L);
                if (thread.isAlive()) {
                    log.warn("Timeout waiting for docker stop command to complete");
                    thread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for Docker container cleanup");
                break;
            }
        }

        // Clear the registry after shutdown attempt
        activeContainers.clear();
    }

    /**
     * Stops a single Docker container by name.
     *
     * <p>This method is public to allow {@code AgentProcess.close()} to stop containers
     * during normal cleanup, not just during JVM shutdown. The method is idempotent -
     * stopping an already-stopped container logs at debug level and does not throw.
     *
     * @param containerName the container name to stop
     */
    public static void stopContainer(String containerName) {
        if (containerName == null || containerName.isBlank()) {
            return;
        }

        try {
            log.debug("Stopping Docker container: {}", containerName);

            ProcessBuilder pb = new ProcessBuilder(
                "docker", "stop", "-t", String.valueOf(STOP_TIMEOUT_SECONDS), containerName
            ).redirectErrorStream(true);

            Process process = pb.start();
            boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                log.warn("Timeout stopping container '{}', force killing", containerName);
                process.destroyForcibly();
            } else if (process.exitValue() == 0) {
                log.info("Successfully stopped Docker container: {}", containerName);
            } else {
                // Exit code != 0 could mean container already stopped or doesn't exist
                String output = new String(process.getInputStream().readAllBytes()).trim();
                log.debug("docker stop '{}' exited with code {}: {}",
                    containerName, process.exitValue(), output);
            }
        } catch (IOException e) {
            log.warn("Failed to stop Docker container '{}': {}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping Docker container '{}'", containerName);
        }
    }
}
