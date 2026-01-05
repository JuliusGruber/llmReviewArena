# Implementation Plan: Docker Container Cleanup on JVM Shutdown

**Issue:** #87
**Status:** Ready for Review
**Created:** 2026-01-05

## Summary

Implement JVM shutdown hook to clean up Docker containers when the application is terminated abruptly (Ctrl+C, terminal close, kill signal). Currently, Docker containers may be left running because the `--rm` flag only removes containers on natural exit, and `AutoCloseable.close()` methods are not guaranteed to be called during abrupt termination.

## Problem Analysis

### Current Behavior

1. **Container creation** (`DockerCommandBuilder.java:173-177`):
   ```java
   result.add("docker");
   result.add("run");
   result.add("--rm");                    // Ephemeral container
   result.add("--name");
   result.add(agentName);                 // Container named after agent
   ```

2. **Process cleanup** (`AgentProcess.java:337-363`):
   - Implements `AutoCloseable` for resource cleanup
   - `close()` destroys the Java `Process` object
   - `destroyProcessTree()` kills descendant processes

3. **The gap**: When the JVM terminates abruptly:
   - `AutoCloseable.close()` may not be called
   - Even if it is, `process.destroy()` only kills the `docker run` CLI process
   - On Windows, killing the parent process doesn't propagate signals to containers
   - Docker containers can continue running as orphans

### Container Naming

| Scenario | Container Name | Source |
|----------|----------------|--------|
| Regular round | `claude`, `gemini`, `codex` | `AgentProcess.agentName` |
| Synthesis | `claude-synthesis` | `agentName + "-synthesis"` |

Container names are deterministic and can be tracked.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DockerContainerRegistry                              │
│  - Thread-safe singleton tracking active container names                     │
│  - Registers JVM shutdown hook on first container registration              │
│  - On shutdown: stops all registered containers via 'docker stop'           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ▲
                                    │ register() / unregister()
                                    │
┌───────────────────────────────────┴─────────────────────────────────────────┐
│                              AgentProcess                                    │
│  - On pb.start() success with Docker enabled: register container name       │
│  - In close(): unregister container name (normal cleanup path)              │
│  - Container name = this.agentName (already stored)                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Shutdown Flow

```
JVM Shutdown Signal (Ctrl+C, SIGTERM, terminal close)
                │
                ▼
┌───────────────────────────────────────┐
│   JVM runs registered shutdown hooks   │
└───────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────┐
│  DockerContainerRegistry.shutdown()   │
│  - Get snapshot of activeContainers   │
│  - Stop containers in parallel        │
│  - Timeout per container: 5 seconds   │
│  - Log errors but don't throw         │
└───────────────────────────────────────┘
                │
                ▼ (for each container)
┌───────────────────────────────────────┐
│  docker stop -t 5 <containerName>     │
│  - Sends SIGTERM, waits 5s            │
│  - Then SIGKILL if still running      │
│  - --rm flag auto-removes container   │
└───────────────────────────────────────┘
```

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Registry pattern | Static singleton | Shutdown hooks are JVM-global; simplest lifecycle management |
| Registration timing | After `pb.start()` | Ensures Docker CLI process started; container may still fail but we handle that |
| Container stop timeout | 5 seconds | Balance between graceful shutdown and not blocking JVM exit |
| Stop strategy | Parallel via virtual threads | Minimize total shutdown time when multiple containers running |
| Error handling | Log and continue | One failed stop shouldn't prevent stopping others |
| Sandbox mode | No tracking needed | `docker sandbox run` handles cleanup automatically |
| `close()` calls `docker stop` | Yes, before destroying process tree | Ensures cleanup even if shutdown hook doesn't run; `docker stop` is idempotent so duplicate calls are safe |
| `stopContainer()` visibility | `public static` | Allows `AgentProcess.close()` to reuse the same logic as the shutdown hook |
| Blocking behavior in `close()` | Up to 8 seconds per container | `STOP_TIMEOUT_SECONDS` (5s) + `COMMAND_TIMEOUT_SECONDS` (3s) is acceptable for reliable cleanup |
| `close()` vs shutdown hook stopping | Serial in `close()`, parallel in shutdown hook | `close()` stops one container (the one it owns); shutdown hook stops all remaining containers in parallel via virtual threads. Serial `close()` is acceptable since each AgentProcess owns exactly one container. |

### Why Not Track by Docker Container ID?

- Container ID requires parsing `docker run` output or calling `docker ps`
- Container names are deterministic and known at process start time
- `docker stop <name>` works just as well as `docker stop <id>`
- Simpler implementation with same functionality

### Thread Safety Considerations

- `activeContainers` uses `ConcurrentHashMap.newKeySet()` for thread-safe operations
- Shutdown hook gets a snapshot to avoid concurrent modification during iteration
- Registration check uses double-checked locking for efficiency

## Implementation

### New Class: `DockerContainerRegistry`

```java
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
```

### Modified: `AgentProcess.java`

Add container registration logic:

```java
// Add new field to track container name for Docker mode
private final String dockerContainerName;  // null if not Docker mode

// In private constructor, add:
this.dockerContainerName = dockerConfig.enabled() && !dockerConfig.sandbox()
    ? agentName  // Container name matches agent name (from --name flag)
    : null;

// In execute() method, after pb.start():
process = pb.start();

// Register Docker container for shutdown cleanup (regular docker run only, not sandbox)
if (dockerContainerName != null) {
    DockerContainerRegistry.register(dockerContainerName);
}

// In close() method, add near the beginning (BEFORE destroying process tree):
// Stop and unregister Docker container
// This ensures cleanup even if shutdown hook doesn't run (e.g., normal exit path)
// docker stop is idempotent, so it's safe if shutdown hook also calls it
//
// NOTE: stopContainer() may block for up to 8 seconds (STOP_TIMEOUT + COMMAND_TIMEOUT).
// This is acceptable because reliable container cleanup is more important than fast close().
if (dockerContainerName != null) {
    DockerContainerRegistry.stopContainer(dockerContainerName);
    DockerContainerRegistry.unregister(dockerContainerName);
}
```

#### Full diff for AgentProcess.java:

```diff
 public class AgentProcess implements AutoCloseable {

     // ... existing fields ...

     private final DockerConfig dockerConfig;
     private final DockerCommandBuilder dockerCommandBuilder;
+    private final String dockerContainerName;  // null if not Docker mode or sandbox

     private Process process;
     private Instant startTime;
     private Thread stdinThread;
     private volatile boolean closed = false;

     private AgentProcess(Builder builder) {
         // ... existing initialization ...
         this.dockerConfig = Objects.requireNonNull(builder.dockerConfig);
         this.dockerCommandBuilder = new DockerCommandBuilder();
+        // Track container name for cleanup - only for regular Docker mode (not sandbox)
+        // Container name matches agentName because DockerCommandBuilder uses --name {agentName}
+        this.dockerContainerName = dockerConfig.enabled() && !dockerConfig.sandbox()
+            ? agentName
+            : null;
     }

     public AgentResult execute() {
         // ... existing setup code ...

             process = pb.start();

+            // Register Docker container for shutdown cleanup (non-sandbox mode only)
+            if (dockerContainerName != null) {
+                DockerContainerRegistry.register(dockerContainerName);
+            }
+
             // Pipe prompt content to stdin from memory (no file handle held)
             if (promptContent != null) {

         // ... rest of execute() ...
     }

     @Override
     public void close() {
         if (closed) {
             return;
         }
         closed = true;

+        // Stop and unregister Docker container BEFORE destroying process tree.
+        // This ensures graceful container shutdown via SIGTERM -> SIGKILL.
+        // docker stop is idempotent, so it's safe if shutdown hook also calls it.
+        //
+        // NOTE: stopContainer() may block for up to 8 seconds (STOP_TIMEOUT + COMMAND_TIMEOUT).
+        // This is acceptable because reliable container cleanup is more important than fast close().
+        if (dockerContainerName != null) {
+            DockerContainerRegistry.stopContainer(dockerContainerName);
+            DockerContainerRegistry.unregister(dockerContainerName);
+        }
+
         log.debug("Closing agent process resources for '{}'", agentName);

         // ... rest of close() ...
     }
```

## Testing Strategy

### Unit Tests

| Test Class | Coverage |
|------------|----------|
| `DockerContainerRegistryTest` | Registration, unregistration, concurrent access, stopAllContainers() |
| `AgentProcessTest` | Extended: Docker container registration/unregistration |

### DockerContainerRegistryTest

```java
package dev.reviewarena.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerContainerRegistryTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        DockerContainerRegistry.clearForTesting();
    }

    @Test
    void register_addsContainerToActiveSet() {
        DockerContainerRegistry.register("claude");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void register_multipleContainers() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("gemini");
        DockerContainerRegistry.register("codex");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(3);
    }

    @Test
    void register_sameContainerTwice_noDuplicate() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("claude");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void register_nullOrBlank_ignored() {
        DockerContainerRegistry.register(null);
        DockerContainerRegistry.register("");
        DockerContainerRegistry.register("   ");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
    }

    @Test
    void unregister_removesContainer() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("gemini");
        DockerContainerRegistry.unregister("claude");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void unregister_nonExistent_noError() {
        DockerContainerRegistry.unregister("nonexistent");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
    }

    @Test
    void unregister_nullOrBlank_noError() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.unregister(null);
        DockerContainerRegistry.unregister("");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void stopAllContainers_clearsRegistry() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("gemini");

        // Note: This will attempt to run 'docker stop' which may fail in test env
        // but should still clear the registry
        DockerContainerRegistry.stopAllContainers();

        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
    }

    @Test
    void stopContainer_isIdempotent() {
        // Calling stopContainer on a non-existent container should not throw
        // This is important because close() and shutdown hook may both call it
        DockerContainerRegistry.stopContainer("nonexistent-container");
        // No exception = success
    }

    @Test
    void stopContainer_handlesNullGracefully() {
        // stopContainer should handle null without throwing
        DockerContainerRegistry.stopContainer(null);
        // No exception = success
    }

    @Test
    void concurrentRegistration_threadSafe() throws InterruptedException {
        int threadCount = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                DockerContainerRegistry.register("container-" + index);
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(threadCount);
    }
}
```

### Integration Tests (Docker Required)

```java
package dev.reviewarena.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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
```

### AgentProcessTest Extensions

**Note:** These tests verify Docker registration behavior via the `DockerContainerRegistry` API.
Testing actual Docker container lifecycle requires integration tests (see `DockerContainerCleanupIT`).
The approach follows existing `AgentProcessTest` patterns which run real processes rather than mocking.

```java
@Test
void execute_dockerDisabled_doesNotRegisterContainer() {
    // Given: Docker disabled
    DockerContainerRegistry.clearForTesting();
    DockerConfig dockerConfig = DockerConfig.disabled();

    // When: AgentProcess executes with a simple script (same pattern as existing tests)
    // ... build and execute agent with dockerConfig ...

    // Then: No container should be registered
    assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
}

@Test
void execute_sandboxMode_doesNotRegisterContainer() {
    // Given: Docker sandbox mode config
    DockerContainerRegistry.clearForTesting();
    DockerConfig dockerConfig = new DockerConfig(true, true, null, null, null, null);

    // When: AgentProcess is built
    // Then: dockerContainerName should be null (sandbox handles cleanup)
    // Note: Cannot easily test execute() without actual Docker sandbox available
    // The field initialization logic is tested via the disabled test above
}

@Test
void dockerContainerName_regularDockerMode_matchesAgentName() {
    // Given: Regular Docker mode (enabled=true, sandbox=false)
    DockerConfig dockerConfig = new DockerConfig(true, false, "test-image", null, null, null);

    // When: AgentProcess is built with agentName="claude"
    // Then: dockerContainerName field should equal "claude"
    // This is verified by inspecting registration behavior in integration tests
}

@Test
void close_isIdempotent_dockerMode() {
    // Given: An AgentProcess with Docker config (using disabled for unit test)
    DockerContainerRegistry.clearForTesting();
    // ... build agent ...

    // When: close() is called multiple times
    agent.close();
    agent.close();

    // Then: No errors (idempotent behavior preserved)
    assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
}
```

**Full Docker registration/unregistration tests** are in `DockerContainerCleanupIT.java` which requires Docker to be available.

## Implementation Steps

### Phase 1: Core Infrastructure

1. **Create `DockerContainerRegistry`**
   - File: `src/main/java/dev/reviewarena/agent/DockerContainerRegistry.java`
   - Thread-safe singleton with shutdown hook
   - Methods: `register()`, `unregister()`, `stopAllContainers()`
   - Package-private methods for testing: `getActiveCount()`, `clearForTesting()`

2. **Add unit tests**
   - File: `src/test/java/dev/reviewarena/agent/DockerContainerRegistryTest.java`
   - Test registration, unregistration, concurrent access
   - Test `stopAllContainers()` clears registry

### Phase 2: Integration

3. **Modify `AgentProcess`**
   - File: `src/main/java/dev/reviewarena/agent/AgentProcess.java`
   - Add `dockerContainerName` field
   - Register container in `execute()` after `pb.start()`
   - Unregister container in `close()`
   - Skip registration for sandbox mode

4. **Add AgentProcess tests**
   - File: `src/test/java/dev/reviewarena/agent/AgentProcessTest.java`
   - Test Docker container registration/unregistration
   - Test sandbox mode doesn't register

### Phase 3: Integration Testing

5. **Add integration tests**
   - File: `src/test/java/dev/reviewarena/agent/DockerContainerCleanupIT.java`
   - Test actual container cleanup (requires Docker)
   - Use JUnit Assumptions to skip when Docker unavailable

## Edge Cases Handled

| Scenario | Handling |
|----------|----------|
| Container fails to start | Register is called, but `docker stop` will fail gracefully (container doesn't exist) |
| Container already stopped | `docker stop` returns exit code 1, logged at debug level, no error thrown |
| Same container name twice | Set semantics prevent duplicates |
| Null/blank container name | Ignored with warning log |
| Multiple JVM shutdowns | Shutdown hook only runs once |
| Sandbox mode | Not registered (sandbox handles cleanup automatically) |
| `docker stop` times out | Force kill the stop process after timeout |
| Docker not installed | `docker stop` command fails, logged as warning |

## File Changes Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `src/main/java/dev/reviewarena/agent/DockerContainerRegistry.java` | New | Singleton registry with shutdown hook |
| `src/main/java/dev/reviewarena/agent/AgentProcess.java` | Modify | Add container registration in execute() and unregistration in close() |
| `src/test/java/dev/reviewarena/agent/DockerContainerRegistryTest.java` | New | Unit tests for registry |
| `src/test/java/dev/reviewarena/agent/DockerContainerCleanupIT.java` | New | Integration tests (requires Docker) |
| `src/test/java/dev/reviewarena/agent/AgentProcessTest.java` | Modify | Add tests for container registration |

## Acceptance Criteria

- [ ] Shutdown hook is registered when first Docker container starts
- [ ] Running Docker containers are stopped on Ctrl+C
- [ ] Running Docker containers are stopped on terminal close
- [ ] Running Docker containers are stopped on SIGTERM
- [ ] Containers are stopped with 5-second graceful timeout
- [ ] Multiple containers are stopped in parallel
- [ ] Sandbox mode containers are NOT registered (handled by Docker)
- [ ] Failed container stops don't prevent other containers from being stopped
- [ ] Unit tests pass without Docker
- [ ] Integration tests pass with Docker
- [ ] Normal shutdown path still works (close() unregisters before shutdown hook runs)

## Rollback Strategy

The shutdown hook is additive and doesn't change normal operation:
- Containers still use `--rm` flag for natural cleanup
- `AgentProcess.close()` still terminates processes normally
- If the shutdown hook causes issues, remove the `register()` call in `AgentProcess`

The feature is contained within the new `DockerContainerRegistry` class and minimal changes to `AgentProcess`, making rollback straightforward.

## Known Limitations

JVM shutdown hooks do **not** run in all termination scenarios. This cleanup mechanism will NOT work when:

| Scenario | Why Hook Doesn't Run |
|----------|---------------------|
| `kill -9` / SIGKILL | JVM is terminated immediately without cleanup |
| JVM crash (native code) | JVM cannot execute managed code during crash |
| `Runtime.halt()` | Explicitly bypasses shutdown hooks |
| Power failure / system crash | No opportunity for graceful shutdown |
| Windows: Closing terminal window | Often sends SIGKILL equivalent, not SIGTERM |
| OutOfMemoryError (some cases) | JVM may be unable to allocate memory for hook execution |

**Mitigation**: Users experiencing orphaned containers can manually clean up with:
```bash
docker ps -a --filter "name=claude" --filter "name=gemini" --filter "name=codex"
docker stop <container_name>
```

For persistent issues, consider a cron job or scheduled task to clean up stale containers.

## Related Issues

- **#89**: Docker container name conflicts when running multiple app instances (out of scope for this implementation)

## Future Enhancements (Out of Scope)

1. **Container health monitoring** - Check if containers are still running periodically
2. **Configurable timeouts** - Allow users to configure stop timeout via config
3. **Podman support** - `podman stop` has same interface as `docker stop`
4. **Cleanup stale containers from previous runs** - Detect and stop containers from crashed sessions
