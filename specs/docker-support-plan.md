# Implementation Plan: Docker Container Support for Agent Execution

**Issue:** #83
**Status:** Draft
**Created:** 2026-01-04

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
      image: "codex-universal:latest"  # Optional, has default
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

| Agent | Default Image | Source |
|-------|---------------|--------|
| Claude | `ghcr.io/zeeno-atl/claude-code:latest` | Always installs latest CLI |
| Codex | `codex-universal:latest` | Official OpenAI reference |
| Gemini | `naoyoshinori/gemini-cli:node` | Well-maintained community image |

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

```java
public record AgentConfig(
    String name,
    List<String> command,
    Map<String, Object> flags,
    boolean enabled,
    DockerConfig docker  // New field
) {
    // ... existing validation ...

    public AgentConfig {
        // Make docker non-null with disabled default
        docker = docker != null ? docker : DockerConfig.disabled();
    }
}
```

### DockerCommandBuilder

```java
package dev.reviewarena.agent;

/**
 * Builds Docker run commands for containerized agent execution.
 */
public class DockerCommandBuilder {

    private static final Map<String, String> DEFAULT_IMAGES = Map.of(
        "claude", "ghcr.io/zeeno-atl/claude-code:latest",
        "codex", "codex-universal:latest",
        "gemini", "naoyoshinori/gemini-cli:node"
    );

    private static final List<String> API_KEY_ENV_VARS = List.of(
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY"
    );

    /**
     * Wraps an agent command with docker run.
     *
     * @param agentName   Name of the agent (for default image lookup)
     * @param docker      Docker configuration
     * @param command     Original agent command
     * @param workingDir  Host working directory to mount
     * @param promptFile  Host path to prompt file (will be mounted)
     * @return Complete docker run command
     */
    public List<String> build(
            String agentName,
            DockerConfig docker,
            List<String> command,
            Path workingDir,
            Path promptFile
    ) {
        var result = new ArrayList<String>();

        result.add("docker");
        result.add("run");
        result.add("--rm");                    // Ephemeral container
        result.add("--network");
        result.add("host");                    // Host networking

        // Mount project directory
        result.add("-v");
        result.add(workingDir.toAbsolutePath() + ":/workspace");
        result.add("-w");
        result.add("/workspace");

        // Pass API keys from host environment
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
                "No Docker image specified for agent '%s' and no default available".formatted(agentName));
        }
        result.add(image);

        // Agent command
        result.addAll(command);

        return List.copyOf(result);
    }
}
```

### DockerAvailabilityChecker

```java
package dev.reviewarena.agent;

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

Update `ConfigLoader.java` to parse the new `docker` section:

```java
// In parseAgentConfig method
private AgentConfig parseAgentConfig(String name, Map<String, Object> agentMap) {
    // ... existing parsing ...

    DockerConfig docker = parseDockerConfig(agentMap.get("docker"));

    return new AgentConfig(name, command, flags, enabled, docker);
}

private DockerConfig parseDockerConfig(Object dockerObj) {
    if (dockerObj == null) {
        return DockerConfig.disabled();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> dockerMap = (Map<String, Object>) dockerObj;

    boolean enabled = Boolean.TRUE.equals(dockerMap.get("enabled"));
    String image = (String) dockerMap.get("image");
    String memory = (String) dockerMap.get("memory");
    String cpus = (String) dockerMap.get("cpus");

    return new DockerConfig(enabled, image, memory, cpus);
}
```

### 2. AgentProcess Changes

Modify `AgentProcess.java` to use Docker when configured:

```java
// In execute() method, before ProcessBuilder creation:
private List<String> resolveCommand(List<String> command) {
    // Check if Docker is enabled for this agent
    if (dockerConfig != null && dockerConfig.enabled()) {
        return dockerCommandBuilder.build(
            agentName,
            dockerConfig,
            command,
            workingDir,
            promptFile
        );
    }

    // Existing Windows cmd /c wrapping
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

### 3. Orchestrator Changes

Add Docker availability check at startup:

```java
// In ArenaOrchestrator initialization
private void validateDockerIfNeeded(ArenaConfig config) {
    boolean anyAgentUsesDocker = config.agents().values().stream()
        .anyMatch(a -> a.enabled() && a.docker().enabled());

    if (anyAgentUsesDocker) {
        dockerAvailabilityChecker.requireDocker();
        log.info("Docker mode enabled for container-based agent execution");
    }
}
```

## Path Translation

When running in Docker, paths must be translated from host paths to container paths:

| Host Path | Container Path |
|-----------|----------------|
| `{workingDir}` | `/workspace` |
| `{workingDir}/.arena/...` | `/workspace/.arena/...` |
| `{promptFile}` | `/workspace/.arena/rounds/.../prompt.md` |

The `@prompt.md` placeholder resolution in `CommandBuilder` needs to produce container-relative paths when Docker is enabled.

```java
// In CommandBuilder
private String resolvePromptPath(Path promptFile, boolean dockerEnabled, Path workingDir) {
    if (dockerEnabled) {
        // Convert to container path
        Path relativePath = workingDir.relativize(promptFile);
        return "/workspace/" + relativePath.toString().replace('\\', '/');
    }
    return promptFile.toAbsolutePath().toString();
}
```

## Implementation Steps

### Phase 1: Core Infrastructure

1. **Create `DockerConfig` record**
   - File: `src/main/java/dev/reviewarena/config/DockerConfig.java`
   - Simple record with enabled, image, memory, cpus fields

2. **Update `AgentConfig` record**
   - Add `DockerConfig docker` field
   - Update compact constructor for null handling

3. **Update `ConfigLoader`**
   - Parse `docker` section from YAML
   - Handle missing section gracefully (defaults to disabled)

4. **Add unit tests for config changes**
   - Test DockerConfig parsing
   - Test default values
   - Test AgentConfig with docker field

### Phase 2: Docker Command Building

5. **Create `DockerCommandBuilder`**
   - Build `docker run` commands
   - Handle default images per agent
   - Add resource limits
   - Pass environment variables

6. **Create `DockerAvailabilityChecker`**
   - Check Docker daemon status
   - Provide clear error messages
   - Optional: check image availability

7. **Add unit tests for command building**
   - Test command generation
   - Test path translation
   - Test environment variable passing

### Phase 3: Integration

8. **Update `AgentProcess`**
   - Accept DockerConfig in builder
   - Use DockerCommandBuilder when enabled
   - Handle container-specific cleanup

9. **Update `CommandBuilder`**
   - Path translation for Docker mode
   - Container-relative paths for placeholders

10. **Update Orchestrator**
    - Docker validation at startup
    - Clear error message if Docker unavailable

11. **Add integration tests**
    - Test agent execution in Docker (requires Docker in CI)
    - Test fallback behavior
    - Test with missing Docker

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
| `DockerConfigTest` | Parsing, defaults, validation |
| `DockerCommandBuilderTest` | Command generation, path translation |
| `DockerAvailabilityCheckerTest` | Mock Docker availability |
| `ConfigLoaderTest` | Extended for docker section |
| `AgentConfigTest` | Extended for docker field |

### Integration Tests

| Test | Requirement |
|------|-------------|
| `DockerAgentExecutionIT` | Docker daemon running |
| `DockerUnavailableIT` | Tests error handling when Docker missing |

Integration tests should be skipped if Docker is not available (use JUnit assumptions).

```java
@BeforeEach
void assumeDockerAvailable() {
    Assumptions.assumeTrue(isDockerAvailable(), "Docker not available");
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

| File | Change Type |
|------|-------------|
| `src/main/java/dev/reviewarena/config/DockerConfig.java` | New |
| `src/main/java/dev/reviewarena/config/AgentConfig.java` | Modify |
| `src/main/java/dev/reviewarena/config/ConfigLoader.java` | Modify |
| `src/main/java/dev/reviewarena/agent/DockerCommandBuilder.java` | New |
| `src/main/java/dev/reviewarena/agent/DockerAvailabilityChecker.java` | New |
| `src/main/java/dev/reviewarena/agent/AgentProcess.java` | Modify |
| `src/main/java/dev/reviewarena/agent/CommandBuilder.java` | Modify |
| `src/main/java/dev/reviewarena/ArenaOrchestrator.java` | Modify |
| `src/test/java/dev/reviewarena/config/DockerConfigTest.java` | New |
| `src/test/java/dev/reviewarena/agent/DockerCommandBuilderTest.java` | New |
| `src/test/java/dev/reviewarena/agent/DockerAvailabilityCheckerTest.java` | New |
| `README.md` | Modify |
| `arena.yaml` | Update examples |

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
