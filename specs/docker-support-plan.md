# Implementation Plan: Docker Container Support for Agent Execution

**Issue:** #83
**Status:** Ready for Review
**Created:** 2026-01-04
**Reviewed:** 2026-01-04 (fixed inconsistencies with existing codebase)

## Summary

Add the ability to run agents (Claude, Codex, Gemini) inside Docker containers for improved isolation, security, and reproducibility.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scope | All agents at once | Complete feature, consistent behavior |
| Credentials | API keys via `-e` env vars | Simple, stateless, no OAuth complexity |
| Runtime | Docker only | Most common, simpler implementation |
| Networking | Host network (`--network host`) | Simpler API access, fewer connectivity issues |
| Fallback | Fail fast with clear error | Explicit behavior, no silent degradation |
| Defaults | Sensible defaults per agent | Better out-of-box experience |
| Prompt passing | File mount only | Reliable, simpler than stdin piping |

### Platform Notes

**`--network host` limitations:**
- **Linux**: Works as expected, container shares host network namespace
- **macOS/Windows Docker Desktop**: `--network host` has limited functionality due to VM isolation. The container can still reach external APIs, but localhost bindings behave differently. This is acceptable for our use case (outbound API calls only).

## Configuration Schema

### arena.yaml Changes

```yaml
agents:
  claude:
    docker:
      enabled: true
      image: "ghcr.io/zeeno-atl/claude-code:latest"  # Optional, has default
      memory: "4g"        # Optional: memory limit
      cpus: "2"           # Optional: CPU limit
    command: ["claude", "-p", "@prompt.md"]
    flags:
      auto-approve: true
    enabled: true

  codex:
    docker:
      enabled: true
      image: "ghcr.io/openai/codex-universal:latest"  # Optional, has default
    command: ["codex", "--sandbox", "danger-full-access"]
    flags:
      auto-approve: true
    enabled: true

  gemini:
    docker:
      enabled: true
      image: "naoyoshinori/gemini-cli:node"  # Optional, has default
    command: ["gemini", "-p", "@prompt.md"]
    flags:
      auto-approve: true
    enabled: true
```

### Default Images

| Agent | Default Image | Source | Notes |
|-------|---------------|--------|-------|
| Claude | `ghcr.io/zeeno-atl/claude-code:latest` | [GitHub](https://github.com/Zeeno-atl/claude-code) | Always installs latest CLI |
| Codex | `ghcr.io/openai/codex-universal:latest` | [GitHub](https://github.com/openai/codex-universal) | Official OpenAI reference; users may need to build locally if not published |
| Gemini | `naoyoshinori/gemini-cli:node` | [Docker Hub](https://hub.docker.com/r/naoyoshinori/gemini-cli) | Well-maintained community image |

> **Note:** If `codex-universal` is not available on GHCR, users must build locally from the GitHub repo and tag as `codex-universal:latest`, or specify a custom image in their config.

## Architecture

### New Classes

```
src/main/java/dev/reviewarena/
├── config/
│   └── DockerConfig.java          # New: Docker configuration record
├── agent/
│   ├── DockerCommandBuilder.java  # New: Builds docker run commands
│   └── DockerAvailabilityChecker.java  # New: Validates Docker is available
```

### DockerConfig Record

```java
package dev.reviewarena.config;

/**
 * Docker configuration for an agent.
 *
 * @param enabled  Whether to run this agent in a Docker container
 * @param image    Docker image to use (null = use default for agent)
 * @param memory   Optional memory limit (e.g., "4g")
 * @param cpus     Optional CPU limit (e.g., "2")
 */
public record DockerConfig(
    boolean enabled,
    String image,
    String memory,
    String cpus
) {
    public DockerConfig {
        // enabled defaults to false if not specified
    }

    public static DockerConfig disabled() {
        return new DockerConfig(false, null, null, null);
    }
}
```

### AgentConfig Changes

Update the existing `AgentConfig.java` record:

```java
/**
 * Configuration for a single AI agent.
 *
 * @param name     Agent identifier (e.g., "claude", "codex", "gemini")
 * @param command  Command and arguments to spawn the agent
 * @param flags    Agent-specific flags (auto-approve, etc.)
 * @param enabled  Whether this agent participates in tournaments
 * @param docker   Docker configuration (null-safe, defaults to disabled)
 */
public record AgentConfig(
    String name,
    List<String> command,
    Map<String, Object> flags,
    boolean enabled,
    DockerConfig docker  // NEW: Docker configuration
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
     */
    public static AgentConfig of(String name, List<String> command) {
        return new AgentConfig(name, command, Map.of(), true, DockerConfig.disabled());
    }

    /**
     * Creates an AgentConfig with custom flags, enabled=true, and Docker disabled.
     */
    public static AgentConfig of(String name, List<String> command, Map<String, Object> flags) {
        return new AgentConfig(name, command, flags, true, DockerConfig.disabled());
    }
}
```

### DockerCommandBuilder

```java
package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;
import dev.reviewarena.config.DockerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Builds Docker run commands for containerized agent execution.
 *
 * <p>Path translation: When Docker is enabled, all paths in the command
 * (prompt file, output file) must be translated from host paths to
 * container paths. The workingDir is mounted as /workspace, so:
 * <ul>
 *   <li>{@code C:\project\.arena\prompt.md} → {@code /workspace/.arena/prompt.md}</li>
 *   <li>{@code /home/user/project/.arena/review.md} → {@code /workspace/.arena/review.md}</li>
 * </ul>
 */
public class DockerCommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(DockerCommandBuilder.class);

    private static final Map<String, String> DEFAULT_IMAGES = Map.of(
        "claude", "ghcr.io/zeeno-atl/claude-code:latest",
        "codex", "ghcr.io/openai/codex-universal:latest",
        "gemini", "naoyoshinori/gemini-cli:node"
    );

    private static final List<String> API_KEY_ENV_VARS = List.of(
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY"  // Gemini CLI may use either GEMINI_API_KEY or GOOGLE_API_KEY
    );

    /**
     * Wraps an agent command with docker run.
     *
     * <p>The command arguments are expected to already have paths translated
     * to container paths (e.g., /workspace/.arena/...) by the caller.
     *
     * @param agentName   Name of the agent (for default image lookup)
     * @param docker      Docker configuration
     * @param command     Agent command with container-relative paths
     * @param workingDir  Host working directory to mount as /workspace
     * @return Complete docker run command
     */
    public List<String> build(
            String agentName,
            DockerConfig docker,
            List<String> command,
            Path workingDir
    ) {
        var result = new ArrayList<String>();

        result.add("docker");
        result.add("run");
        result.add("--rm");                    // Ephemeral container
        result.add("--network");
        result.add("host");                    // Host networking (see Platform Notes)

        // Mount project directory as /workspace
        result.add("-v");
        result.add(workingDir.toAbsolutePath() + ":/workspace");
        result.add("-w");
        result.add("/workspace");

        // Pass API keys from host environment (only if set)
        for (String envVar : API_KEY_ENV_VARS) {
            if (System.getenv(envVar) != null) {
                result.add("-e");
                result.add(envVar);
            }
        }

        // Optional resource limits
        if (docker.memory() != null) {
            result.add("--memory");
            result.add(docker.memory());
        }
        if (docker.cpus() != null) {
            result.add("--cpus");
            result.add(docker.cpus());
        }

        // Image (use configured or default)
        String image = docker.image() != null
            ? docker.image()
            : DEFAULT_IMAGES.get(agentName.toLowerCase());
        if (image == null) {
            throw new ConfigException(
                "No Docker image specified for agent '%s' and no default available. " +
                "Available defaults: %s".formatted(agentName, DEFAULT_IMAGES.keySet()));
        }
        result.add(image);

        // Agent command (paths should already be container-relative)
        result.addAll(command);

        log.debug("Built Docker command for {}: {}", agentName, result);
        return List.copyOf(result);
    }

    /**
     * Translates a host path to a container path.
     *
     * @param hostPath    The absolute path on the host
     * @param workingDir  The host working directory (mounted as /workspace)
     * @return The container path (e.g., /workspace/.arena/prompt.md)
     */
    public static String toContainerPath(Path hostPath, Path workingDir) {
        Path relativePath = workingDir.toAbsolutePath().relativize(hostPath.toAbsolutePath());
        // Use forward slashes for container paths (Linux)
        return "/workspace/" + relativePath.toString().replace('\\', '/');
    }
}
```

### DockerAvailabilityChecker

```java
package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Checks if Docker is available and running.
 */
public class DockerAvailabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(DockerAvailabilityChecker.class);

    /**
     * Verifies Docker is installed and the daemon is running.
     *
     * @throws ConfigException if Docker is not available
     */
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
```

## Integration Points

### 1. ConfigLoader Changes

Update `ConfigLoader.java` to parse the new `docker` section using SmallRyeConfig API:

```java
// In loadAgentConfig method - add docker config loading
private AgentConfig loadAgentConfig(SmallRyeConfig config, String agentName) {
    String prefix = "agents." + agentName;

    // ... existing command, enabled, flags loading ...

    // Load docker config
    DockerConfig docker = loadDockerConfig(config, prefix + ".docker");

    return new AgentConfig(agentName, command, flags, enabled, docker);
}

/**
 * Loads Docker configuration for an agent using SmallRyeConfig.
 */
private DockerConfig loadDockerConfig(SmallRyeConfig config, String prefix) {
    // Check if docker.enabled exists and is true
    boolean enabled = config.getOptionalValue(prefix + ".enabled", Boolean.class)
        .orElse(false);

    if (!enabled) {
        return DockerConfig.disabled();
    }

    String image = config.getOptionalValue(prefix + ".image", String.class)
        .orElse(null);
    String memory = config.getOptionalValue(prefix + ".memory", String.class)
        .orElse(null);
    String cpus = config.getOptionalValue(prefix + ".cpus", String.class)
        .orElse(null);

    return new DockerConfig(true, image, memory, cpus);
}
```

### 2. AgentProcess Changes

Add `DockerConfig` field to `AgentProcess` and its Builder:

```java
// New field in AgentProcess
private final DockerConfig dockerConfig;  // May be null if Docker disabled

// In Builder class - add:
private DockerConfig dockerConfig;

public Builder dockerConfig(DockerConfig dockerConfig) {
    this.dockerConfig = dockerConfig;
    return this;
}

// Update build() to pass dockerConfig to constructor
```

Modify `resolveCommand()` in `AgentProcess.java`:

```java
private final DockerCommandBuilder dockerCommandBuilder = new DockerCommandBuilder();

/**
 * Resolves command for execution, wrapping with Docker or Windows shell as needed.
 *
 * <p>Note: Docker commands do NOT need Windows cmd /c wrapping because
 * docker.exe is a native executable, not a .cmd script.
 */
private List<String> resolveCommand(List<String> command) {
    // Check if Docker is enabled for this agent
    if (dockerConfig != null && dockerConfig.enabled()) {
        return dockerCommandBuilder.build(
            agentName,
            dockerConfig,
            command,
            workingDir
        );
    }

    // Windows cmd /c wrapping (only for non-Docker execution)
    if (isWindows()) {
        var result = new ArrayList<String>();
        result.add("cmd");
        result.add("/c");
        result.addAll(command);
        return result;
    }

    return command;
}
```

### 3. AgentExecutor Changes

Update `AgentExecutor.java` to validate Docker and pass config to AgentProcess:

```java
// Add field
private final DockerAvailabilityChecker dockerChecker = new DockerAvailabilityChecker();

// Add validation method (call from constructor or first executeRound)
private void validateDockerIfNeeded() {
    boolean anyAgentUsesDocker = config.agents().values().stream()
        .filter(AgentConfig::enabled)
        .anyMatch(a -> a.docker().enabled());

    if (anyAgentUsesDocker) {
        dockerChecker.requireDocker();
        log.info("Docker mode enabled for container-based agent execution");
    }
}

// In executeAgent() method, update AgentProcess building:
private AgentResult executeAgent(AgentConfig agentConfig, int round) {
    Path promptFile = workspace.getRoundPromptPath(round, agentConfig.name());
    Path agentDir = workspace.getAgentDir(round, agentConfig.name());
    Path outputFile = agentDir.resolve("review.md");
    Path projectRoot = workspace.getArenaDir().getParent();

    // Build command with path translation if Docker is enabled
    List<String> command = buildCommandWithPathTranslation(
        agentConfig, promptFile, outputFile, projectRoot);

    AgentProcess process = AgentProcess.builder()
        .agentName(agentConfig.name())
        .round(round)
        .command(command)
        .workingDir(projectRoot)
        .outputFile(outputFile)
        .promptFile(promptFile)
        .stdoutLog(agentDir.resolve("stdout.log"))
        .stderrLog(agentDir.resolve("stderr.log"))
        .timeoutMs(config.agentTimeoutMs())
        .gracePeriodMs(config.gracePeriodMs())
        .outputValidator(outputValidator)
        .showOutput(config.showAgentOutput())
        .dockerConfig(agentConfig.docker())  // NEW: pass Docker config
        .build();

    return process.execute();
}

/**
 * Builds command with proper path translation for Docker mode.
 */
private List<String> buildCommandWithPathTranslation(
        AgentConfig agentConfig,
        Path promptFile,
        Path outputFile,
        Path projectRoot
) {
    if (agentConfig.docker().enabled()) {
        // Translate paths to container paths before building command
        Path containerPrompt = Path.of(
            DockerCommandBuilder.toContainerPath(promptFile, projectRoot));
        Path containerOutput = Path.of(
            DockerCommandBuilder.toContainerPath(outputFile, projectRoot));
        return commandBuilder.build(agentConfig, containerPrompt, containerOutput);
    }
    // Non-Docker: use host paths as-is
    return commandBuilder.build(agentConfig, promptFile, outputFile);
}
```

### 4. ReviewArenaCli Changes

Add Docker validation at startup in `ReviewArenaCli.call()`:

```java
@Override
public Integer call() {
    // ... existing setup ...

    // Create executor (validates Docker availability if needed)
    AgentExecutor executor = new AgentExecutor(config, workspaceManager);

    // Validate Docker before starting tournament
    // (This happens inside AgentExecutor constructor or first executeRound call)

    // ... rest of tournament flow ...
}
```

## Path Translation

When running in Docker, paths must be translated from host paths to container paths:

| Host Path | Container Path |
|-----------|----------------|
| `{workingDir}` | `/workspace` |
| `{workingDir}/.arena/...` | `/workspace/.arena/...` |
| `{promptFile}` | `/workspace/.arena/rounds/.../prompt.md` |
| `{outputFile}` | `/workspace/.arena/rounds/.../review.md` |

**Path translation responsibility:**

| Component | Responsibility |
|-----------|---------------|
| `AgentExecutor.buildCommandWithPathTranslation()` | Translates paths BEFORE calling CommandBuilder when Docker is enabled |
| `DockerCommandBuilder.toContainerPath()` | Static utility method for host→container path conversion |
| `CommandBuilder` | Unchanged - receives already-translated paths |

This design keeps `CommandBuilder` Docker-agnostic and centralizes path translation in `AgentExecutor`.

## Implementation Steps

### Phase 1: Core Infrastructure

1. **Create `DockerConfig` record**
   - File: `src/main/java/dev/reviewarena/config/DockerConfig.java`
   - Simple record with enabled, image, memory, cpus fields
   - Include `disabled()` factory method

2. **Update `AgentConfig` record**
   - File: `src/main/java/dev/reviewarena/config/AgentConfig.java`
   - Add `DockerConfig docker` field as 5th parameter
   - Update compact constructor: `docker = docker != null ? docker : DockerConfig.disabled();`
   - Update static factory methods to include docker parameter

3. **Update `ConfigLoader`**
   - File: `src/main/java/dev/reviewarena/config/ConfigLoader.java`
   - Add `loadDockerConfig(SmallRyeConfig config, String prefix)` method
   - Update `loadAgentConfig()` to call `loadDockerConfig()` and pass to AgentConfig

4. **Add unit tests for config changes**
   - File: `src/test/java/dev/reviewarena/config/DockerConfigTest.java`
   - Test DockerConfig parsing with SmallRyeConfig
   - Test default values (disabled when section missing)
   - Test AgentConfig with docker field

### Phase 2: Docker Command Building

5. **Create `DockerCommandBuilder`**
   - File: `src/main/java/dev/reviewarena/agent/DockerCommandBuilder.java`
   - Build `docker run` commands
   - Handle default images per agent (with error listing available defaults)
   - Add resource limits (memory, cpus)
   - Pass environment variables (ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY, GOOGLE_API_KEY)
   - Include static `toContainerPath()` utility method

6. **Create `DockerAvailabilityChecker`**
   - File: `src/main/java/dev/reviewarena/agent/DockerAvailabilityChecker.java`
   - Check Docker daemon status via `docker info`
   - Provide clear error messages for common failures
   - Include `imageExists()` method for optional image pre-check

7. **Add unit tests for command building**
   - File: `src/test/java/dev/reviewarena/agent/DockerCommandBuilderTest.java`
   - Test command generation with various configs
   - Test path translation (Windows and Linux paths)
   - Test environment variable passing (only when env vars are set)
   - Test default image selection and error when no default

### Phase 3: Integration

8. **Update `AgentProcess`**
   - File: `src/main/java/dev/reviewarena/agent/AgentProcess.java`
   - Add `DockerConfig dockerConfig` field
   - Add `dockerConfig(DockerConfig)` to Builder
   - Add `DockerCommandBuilder` instance field
   - Update `resolveCommand()` to check Docker before Windows wrapping
   - Document that Docker commands don't need cmd /c wrapping

9. **Update `AgentExecutor`**
   - File: `src/main/java/dev/reviewarena/agent/AgentExecutor.java`
   - Add `DockerAvailabilityChecker` field
   - Add `validateDockerIfNeeded()` method (call from constructor)
   - Add `buildCommandWithPathTranslation()` method for Docker path handling
   - Update `executeAgent()` to use path translation and pass `dockerConfig` to builder
   - Update `executeSynthesis()` similarly

10. **CommandBuilder - NO CHANGES NEEDED**
    - `CommandBuilder.java` remains unchanged
    - Path translation happens in `AgentExecutor` before calling `CommandBuilder`

11. **Add integration tests**
    - File: `src/test/java/dev/reviewarena/agent/DockerAgentExecutionIT.java`
    - Test agent execution in Docker (requires Docker in CI)
    - Use JUnit Assumptions to skip when Docker unavailable
    - Test with missing Docker produces clear error

### Phase 4: Documentation

12. **Update README**
    - Docker setup instructions
    - Example configurations
    - Troubleshooting guide

13. **Update arena.yaml examples**
    - Show Docker configuration
    - Document default images

## Testing Strategy

### Unit Tests

| Test Class | Coverage |
|------------|----------|
| `DockerConfigTest` | Record creation, `disabled()` factory, null handling |
| `DockerCommandBuilderTest` | Command generation, path translation, env var handling, default images |
| `DockerAvailabilityCheckerTest` | Mock `docker info` responses, timeout handling |
| `ConfigLoaderTest` | Extended: parsing docker section from YAML, defaults when missing |
| `AgentConfigTest` | Extended: docker field null-safety, factory methods |
| `AgentProcessTest` | Extended: `resolveCommand()` with Docker enabled vs disabled |

### Integration Tests

| Test | Requirement | Notes |
|------|-------------|-------|
| `DockerAgentExecutionIT` | Docker daemon running | Full agent execution in container |
| `DockerAvailabilityCheckerIT` | Any environment | Tests real `docker info` call |

Integration tests should be skipped if Docker is not available (use JUnit assumptions).

```java
class DockerAgentExecutionIT {

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
    void assumeDockerAvailable() {
        Assumptions.assumeTrue(dockerAvailable, "Docker not available - skipping test");
    }

    @Test
    void agentExecutesInDocker() {
        // Test that agent runs successfully inside container
        // Verify output file is written correctly
        // Verify container is cleaned up after execution
    }
}
```

## Error Messages

| Scenario | Error Message |
|----------|---------------|
| Docker not installed | `Docker is not installed or not in PATH. Install Docker Desktop or ensure 'docker' command is available.` |
| Docker daemon not running | `Docker daemon is not running. Start Docker Desktop or run 'sudo systemctl start docker'.` |
| Image not found | `Docker image '{image}' not found. Run 'docker pull {image}' or check the image name.` |
| No default image | `No Docker image specified for agent '{name}' and no default available. Add 'image' to docker config.` |

## Rollback Strategy

Docker support is opt-in via `docker.enabled: true`. The default behavior (Docker disabled) remains unchanged, ensuring:

- Existing configurations continue to work
- No breaking changes to current users
- Easy rollback by removing/disabling docker config section

## Future Enhancements (Out of Scope)

These are explicitly **not** included in this implementation:

1. **Podman support** - Could be added later with configurable runtime
2. **OAuth credential mounting** - Would require platform-specific paths
3. **Custom Dockerfile building** - Users can build their own images
4. **GPU support** - Would need `--gpus` flag handling
5. **Container registry authentication** - Users handle via `docker login`
6. **Stdin piping** - File mount approach is sufficient

## File Changes Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `src/main/java/dev/reviewarena/config/DockerConfig.java` | New | Docker configuration record |
| `src/main/java/dev/reviewarena/config/AgentConfig.java` | Modify | Add `docker` field |
| `src/main/java/dev/reviewarena/config/ConfigLoader.java` | Modify | Add `loadDockerConfig()` method |
| `src/main/java/dev/reviewarena/agent/DockerCommandBuilder.java` | New | Builds `docker run` commands |
| `src/main/java/dev/reviewarena/agent/DockerAvailabilityChecker.java` | New | Validates Docker availability |
| `src/main/java/dev/reviewarena/agent/AgentProcess.java` | Modify | Add `dockerConfig` field and Docker command wrapping |
| `src/main/java/dev/reviewarena/agent/AgentExecutor.java` | Modify | Add Docker validation and path translation |
| `src/main/java/dev/reviewarena/agent/CommandBuilder.java` | **No change** | Path translation handled by AgentExecutor |
| `src/test/java/dev/reviewarena/config/DockerConfigTest.java` | New | Unit tests for DockerConfig |
| `src/test/java/dev/reviewarena/agent/DockerCommandBuilderTest.java` | New | Unit tests for command building |
| `src/test/java/dev/reviewarena/agent/DockerAvailabilityCheckerTest.java` | New | Unit tests for availability checker |
| `src/test/java/dev/reviewarena/agent/DockerAgentExecutionIT.java` | New | Integration tests (requires Docker) |
| `src/test/java/dev/reviewarena/config/ConfigLoaderTest.java` | Modify | Add tests for docker config parsing |
| `src/test/java/dev/reviewarena/agent/AgentProcessTest.java` | Modify | Add tests for Docker command resolution |
| `README.md` | Modify | Add Docker setup instructions |
| `arena.yaml` | Modify | Add Docker configuration examples |

## Acceptance Criteria

- [ ] `docker.enabled: true` in arena.yaml runs agent in Docker container
- [ ] Agents can read/write files in `.arena/` directory
- [ ] API keys are passed via environment variables
- [ ] Resource limits (memory, cpus) are enforced when configured
- [ ] Clear error message when Docker is unavailable
- [ ] Default images work out of the box for all three agents
- [ ] Existing non-Docker configurations continue to work unchanged
- [ ] Integration tests pass with Docker (when available)
- [ ] Documentation updated with setup instructions
