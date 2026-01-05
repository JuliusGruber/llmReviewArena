# Implementation Plan: Docker Container Support for Agent Execution

**Issue:** #83
**Status:** Ready for Review
**Created:** 2026-01-04
**Reviewed:** 2026-01-04 (v4 - fixed test counts, path validation fail-fast, DockerChecker interface, factory methods)

## Summary

Add the ability to run agents (Claude, Codex, Gemini) inside Docker containers for improved isolation, security, and reproducibility.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           AgentExecutor                                  │
│  - Validates Docker availability at startup                             │
│  - Passes HOST paths to CommandBuilder                                  │
│  - Passes dockerConfig to AgentProcess                                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
    ┌───────────────────────────┐   ┌───────────────────────────┐
    │      CommandBuilder       │   │       AgentProcess        │
    │  - Builds command with    │   │  - resolveCommand()       │
    │    HOST paths             │   │    detects Docker mode    │
    │  - NO Docker awareness    │   │  - stdin uses HOST path   │
    └───────────────────────────┘   └───────────────────────────┘
                    │                               │
                    │              Docker mode?     │
                    │               ┌───────────────┴───────────────┐
                    │               │ YES                           │ NO
                    │               ▼                               ▼
                    │   ┌───────────────────────────┐   ┌───────────────────┐
                    │   │   DockerCommandBuilder    │   │  Windows cmd /c   │
                    │   │  - Wraps with docker run  │   │  wrapping         │
                    │   │  - Translates HOST paths  │   └───────────────────┘
                    │   │    to /workspace/...      │
                    │   │  - Adds -i for stdin      │
                    │   └───────────────────────────┘
                    │
                    ▼
    ┌───────────────────────────────────────────────────────────────────┐
    │  Final command example (Docker mode):                             │
    │  docker run --rm -i --network host                                │
    │    -v /host/project:/workspace -w /workspace                      │
    │    -e ANTHROPIC_API_KEY                                           │
    │    ghcr.io/zeeno-atl/claude-code:latest                          │
    │    claude -p /workspace/.arena/rounds/0/claude/prompt.md          │
    │            ▲                                                      │
    │            └── Translated from C:\host\project\.arena\...         │
    └───────────────────────────────────────────────────────────────────┘
```

**Key Design Principles:**
1. **CommandBuilder unchanged** - produces HOST paths, Docker-agnostic
2. **Path translation centralized** - happens in DockerCommandBuilder AFTER CommandBuilder
3. **Stdin works in both modes** - ProcessBuilder reads HOST file, Docker's `-i` forwards to container
4. **Output validation uses HOST paths** - OutputValidator reads from host filesystem after agent completes

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scope | All agents at once | Complete feature, consistent behavior |
| Credentials | API keys via `-e` env vars | Simple, stateless, no OAuth complexity |
| Runtime | Docker only | Most common, simpler implementation |
| Networking | Host network (`--network host`) | Simpler API access, fewer connectivity issues (see Security Notes below) |
| Fallback | Fail fast with clear error | Explicit behavior, no silent degradation |
| Defaults | Sensible defaults per agent | Better out-of-box experience |
| Prompt passing | Host stdin + file mount | ProcessBuilder pipes file to Docker stdin via `-i` flag |
| Path translation | In DockerCommandBuilder | All path translation centralized in one place, after CommandBuilder runs |

### Isolation Notes

**What Docker provides:**
- **Filesystem isolation** - Containers can only access the mounted `/workspace` directory
- **Process isolation** - Container processes are isolated from host processes
- **Clean environment** - No access to host user files, SSH keys, or credentials outside env vars

**What `--network host` does NOT provide:**
- **Network isolation** - Containers share the host network namespace and can access localhost services
- Containers could theoretically access local services running on the host (databases, APIs, etc.)
- This is acceptable for our use case (outbound LLM API calls only) but is NOT a security sandbox

**Important:** Docker with `--network host` provides **convenience isolation** (clean filesystem, reproducible environment) rather than **security isolation**. Do not run untrusted code in this configuration.

**Future enhancement:** Add `--network none` or custom bridge network option for stricter isolation when API keys are passed via environment variables (no network access needed for credential retrieval).

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
      image: "tgagor/gemini-cli:latest"  # Optional, has default
    command: ["gemini", "-p", "@prompt.md"]
    flags:
      auto-approve: true
    enabled: true
```

### Default Images

| Agent | Default Image | Source | Notes |
|-------|---------------|--------|-------|
| Claude | `ghcr.io/zeeno-atl/claude-code:latest` | [GitHub](https://github.com/Zeeno-atl/claude-code) | Always installs latest CLI |
| Codex | *None - must be configured* | [GitHub](https://github.com/openai/codex-universal) | No published image; requires local build or custom image |
| Gemini | `tgagor/gemini-cli:latest` | [Docker Hub](https://hub.docker.com/r/tgagor/gemini-cli) | Actively maintained, tracks latest CLI |

> **Codex Docker Setup:** OpenAI does not publish pre-built Docker images for Codex CLI. Users must either:
> 1. Build locally: `git clone https://github.com/openai/codex-universal && docker build -t codex-universal:latest .`
> 2. Use a community image and specify it in `arena.yaml`: `docker.image: "your-image:tag"`
>
> If `docker.enabled: true` is set for Codex without specifying an image, the arena will fail fast with a clear error message listing available options.

## Architecture

### New Classes

```
src/main/java/dev/reviewarena/
├── config/
│   └── DockerConfig.java          # New: Docker configuration record
├── agent/
│   ├── DockerChecker.java         # New: Interface for Docker availability (enables testing)
│   ├── DockerAvailabilityChecker.java  # New: Implements DockerChecker, validates Docker
│   └── DockerCommandBuilder.java  # New: Builds docker run commands
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

    /**
     * Creates a disabled AgentConfig (for testing scenarios where agent is configured but disabled).
     */
    public static AgentConfig disabled(String name, List<String> command) {
        return new AgentConfig(name, command, Map.of(), false, DockerConfig.disabled());
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds Docker run commands for containerized agent execution.
 *
 * <p>Path translation: This class handles ALL path translation from host paths to
 * container paths. The workingDir is mounted as /workspace, so:
 * <ul>
 *   <li>{@code C:\project\.arena\prompt.md} → {@code /workspace/.arena/prompt.md}</li>
 *   <li>{@code /home/user/project/.arena/review.md} → {@code /workspace/.arena/review.md}</li>
 * </ul>
 *
 * <p>The command passed to {@link #build} should contain HOST paths (as produced by
 * CommandBuilder). This class translates them to container paths automatically.
 */
public class DockerCommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(DockerCommandBuilder.class);

    // Note: Codex has no default - OpenAI doesn't publish pre-built images
    private static final Map<String, String> DEFAULT_IMAGES = Map.of(
        "claude", "ghcr.io/zeeno-atl/claude-code:latest",
        "gemini", "tgagor/gemini-cli:latest"
    );

    private static final List<String> API_KEY_ENV_VARS = List.of(
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY"  // Gemini CLI may use either GEMINI_API_KEY or GOOGLE_API_KEY
    );

    /**
     * Wraps an agent command with docker run, translating host paths to container paths.
     *
     * <p>The command arguments should contain HOST paths (absolute paths on the host
     * filesystem). This method scans the command for paths under workingDir and
     * translates them to container paths (/workspace/...).
     *
     * @param agentName   Name of the agent (for default image lookup)
     * @param docker      Docker configuration
     * @param command     Agent command with HOST paths (as produced by CommandBuilder)
     * @param workingDir  Host working directory to mount as /workspace
     * @return Complete docker run command with translated paths
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
        result.add("-i");                      // Keep stdin open for prompt redirection
        result.add("--network");
        result.add("host");                    // Host networking (see Platform Notes)

        // Mount project directory as /workspace
        result.add("-v");
        result.add(workingDir.toAbsolutePath() + ":/workspace");
        result.add("-w");
        result.add("/workspace");

        // Pass API keys from host environment (only if set)
        // Note: Env vars must be set in the shell that launches the arena
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
                "No Docker image specified for agent '%s' and no default available. ".formatted(agentName) +
                "For Codex, you must build or specify an image:\n" +
                "  Option 1: Build locally: git clone https://github.com/openai/codex-universal && " +
                "docker build -t codex-universal:latest .\n" +
                "  Option 2: Specify in arena.yaml: agents.codex.docker.image: \"your-image:tag\"\n" +
                "Available default images: " + DEFAULT_IMAGES.keySet());
        }
        result.add(image);

        // Translate host paths in command to container paths
        List<String> translatedCommand = translatePathsInCommand(command, workingDir);
        result.addAll(translatedCommand);

        log.debug("Built Docker command for {}: {}", agentName, result);
        return List.copyOf(result);
    }

    /**
     * Translates host paths in command arguments to container paths.
     *
     * <p>Scans each argument for paths that are under workingDir and replaces
     * them with container-relative paths (/workspace/...).
     *
     * <p><b>Fail-fast behavior:</b> Paths outside workingDir cannot be translated and will
     * cause this method to throw ConfigException immediately, rather than allowing the
     * container to fail later with a cryptic "file not found" error.
     *
     * @param command    Command with host paths
     * @param workingDir Host working directory (mounted as /workspace)
     * @return Command with translated container paths
     * @throws ConfigException if command contains absolute paths outside workingDir
     */
    private List<String> translatePathsInCommand(List<String> command, Path workingDir) {
        Path absWorkingDir = workingDir.toAbsolutePath().normalize();
        String workingDirStr = absWorkingDir.toString();

        // Also handle forward-slash version for cross-platform compatibility
        String workingDirForward = workingDirStr.replace('\\', '/');

        List<String> translated = new ArrayList<>();
        for (String arg : command) {
            String translatedArg = arg;

            // Check if argument contains the working directory path
            if (arg.contains(workingDirStr)) {
                // Replace host path prefix with /workspace
                translatedArg = arg.replace(workingDirStr, "/workspace");
                // Normalize to forward slashes for Linux container
                translatedArg = translatedArg.replace('\\', '/');
            } else if (arg.contains(workingDirForward)) {
                translatedArg = arg.replace(workingDirForward, "/workspace");
            } else if (looksLikeAbsolutePath(arg)) {
                // Fail fast for absolute paths outside workingDir - these won't work in container
                throw new ConfigException(
                    "Command argument '%s' is an absolute path outside the project directory '%s'. "
                    + "Docker containers can only access files mounted at /workspace. "
                    + "Move the file under the project directory or use a relative path."
                    .formatted(arg, workingDir));
            }

            translated.add(translatedArg);
        }
        return translated;
    }

    /**
     * Heuristic check for absolute paths (Windows or Unix style).
     */
    private boolean looksLikeAbsolutePath(String arg) {
        // Windows: C:\... or D:\...
        if (arg.length() >= 3 && Character.isLetter(arg.charAt(0))
                && arg.charAt(1) == ':' && (arg.charAt(2) == '\\' || arg.charAt(2) == '/')) {
            return true;
        }
        // Unix: /home/... /tmp/... etc (but not flags like --foo)
        if (arg.startsWith("/") && !arg.startsWith("--") && arg.length() > 1
                && Character.isLetterOrDigit(arg.charAt(1))) {
            return true;
        }
        return false;
    }

    /**
     * Translates a host path to a container path.
     *
     * @param hostPath    The absolute path on the host
     * @param workingDir  The host working directory (mounted as /workspace)
     * @return The container path (e.g., /workspace/.arena/prompt.md)
     * @throws IllegalArgumentException if hostPath is not under workingDir
     */
    public static String toContainerPath(Path hostPath, Path workingDir) {
        Path absHost = hostPath.toAbsolutePath().normalize();
        Path absWorkingDir = workingDir.toAbsolutePath().normalize();

        // Validate that hostPath is under workingDir
        if (!absHost.startsWith(absWorkingDir)) {
            throw new IllegalArgumentException(
                "Path '%s' is not under working directory '%s'"
                    .formatted(hostPath, workingDir));
        }

        Path relativePath = absWorkingDir.relativize(absHost);
        // Use forward slashes for container paths (Linux)
        return "/workspace/" + relativePath.toString().replace('\\', '/');
    }
}
```

### DockerChecker Interface

```java
package dev.reviewarena.agent;

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

**Error Flow:** ConfigLoader does NOT validate Docker availability—it only parses configuration.
Docker validation happens at runtime in `AgentExecutor.validateDockerIfNeeded()`:

```
arena.yaml parsed → DockerConfig created → AgentExecutor constructed
                                                    │
                                    validateDockerIfNeeded() called
                                                    │
                         ┌──────────────────────────┴──────────────────────────┐
                         │ Any agent has docker.enabled: true?                 │
                         └──────────────────────────────────────────────────────┘
                                    │ YES                          │ NO
                                    ▼                              ▼
                    DockerAvailabilityChecker.requireDocker()   (continue)
                                    │
                    ┌───────────────┴───────────────┐
                    │ Docker available?              │
                    └────────────────────────────────┘
                         │ YES              │ NO
                         ▼                  ▼
                    (continue)     ConfigException thrown with message:
                                   "Docker is not available: ..."
```

This design allows arena.yaml files with `docker.enabled: true` to be loaded even when Docker
isn't installed (useful for CI/CD environments that may skip Docker-enabled agents).

### 2. AgentProcess Changes

Add `DockerConfig` field to `AgentProcess` and its Builder:

```java
// New fields in AgentProcess
private final DockerConfig dockerConfig;  // Never null (AgentConfig guarantees non-null)
private final DockerCommandBuilder dockerCommandBuilder = new DockerCommandBuilder();

// In private constructor - add:
this.dockerConfig = builder.dockerConfig;  // Already non-null from AgentConfig

// In Builder class - add field:
private DockerConfig dockerConfig;

// Add builder method:
public Builder dockerConfig(DockerConfig dockerConfig) {
    this.dockerConfig = dockerConfig;
    return this;
}

// Validation in build() - dockerConfig should come from AgentConfig (always non-null)
if (dockerConfig == null) {
    throw new IllegalStateException("dockerConfig is required");
}
```

Modify `resolveCommand()` in `AgentProcess.java`:

```java
/**
 * Resolves command for execution, wrapping with Docker or Windows shell as needed.
 *
 * <p><b>Order matters:</b> Docker is checked FIRST because:
 * <ol>
 *   <li>docker.exe is a native Windows executable, not a .cmd/.bat script</li>
 *   <li>Docker commands must NOT be wrapped with cmd /c (would break argument parsing)</li>
 *   <li>The container handles all platform differences internally</li>
 * </ol>
 *
 * <p>Path translation: When Docker is enabled, this method passes the command
 * (with HOST paths from CommandBuilder) to DockerCommandBuilder, which translates
 * paths to container paths (/workspace/...).
 */
private List<String> resolveCommand(List<String> command) {
    // IMPORTANT: Check Docker FIRST - docker.exe is native and must NOT be wrapped with cmd /c
    if (dockerConfig.enabled()) {
        // DockerCommandBuilder handles path translation from host paths to container paths
        return dockerCommandBuilder.build(
            agentName,
            dockerConfig,
            command,  // Contains HOST paths from CommandBuilder
            workingDir
        );
    }

    // Windows cmd /c wrapping (only for non-Docker execution)
    // This is needed because CLI tools like 'claude', 'codex' are actually .cmd scripts
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

Modify `execute()` to handle stdin redirection for Docker mode:

```java
public AgentResult execute() {
    // ... existing setup ...

    List<String> effectiveCommand = resolveCommand(command);
    log.debug("Effective command: {}", effectiveCommand);

    ProcessBuilder pb = new ProcessBuilder(effectiveCommand)
        .directory(workingDir.toFile());

    // Stdin redirection: Works for both native and Docker modes
    // - Native: ProcessBuilder reads file and pipes to process stdin
    // - Docker: ProcessBuilder reads file, Docker's -i flag keeps stdin open,
    //           and the file contents flow: file -> ProcessBuilder -> docker stdin -> container stdin
    // Both modes use the HOST path because ProcessBuilder reads the file on the host
    if (promptFile != null) {
        log.debug("Redirecting stdin from: {}", promptFile);
        pb.redirectInput(promptFile.toFile());  // Always use HOST path
    }

    process = pb.start();
    // ... rest of execution ...
}
```

**How stdin redirection works:**

| Mode | Flow |
|------|------|
| Native | `promptFile` → ProcessBuilder → agent process stdin |
| Docker | `promptFile` → ProcessBuilder → `docker run -i` stdin → container stdin |

**Key insight:** ProcessBuilder's `redirectInput(file)` reads the file and writes its contents to the spawned process's stdin. When that process is `docker run -i`, Docker forwards its stdin to the container. This is why:

1. We use the **HOST path** for `promptFile` (ProcessBuilder reads it on the host)
2. We use the **`-i` flag** on `docker run` (keeps stdin open for forwarding)
3. The prompt file is ALSO mounted at `/workspace/...` for agents that read from file path arguments (not stdin)

**Note:** Docker's `-i` flag means "keep stdin open", not "interactive mode" (that's `-it`). Without `-i`, Docker closes stdin immediately and the prompt content would be lost.

### 3. AgentExecutor Changes

Update `AgentExecutor.java` to validate Docker and pass config to AgentProcess:

```java
// Add field - use interface for testability
private final DockerChecker dockerChecker;

// Production constructor
public AgentExecutor(ArenaConfig config, WorkspaceManager workspace) {
    this(config, workspace, new DockerAvailabilityChecker());
}

// Package-private constructor for testing (allows mock injection)
AgentExecutor(ArenaConfig config, WorkspaceManager workspace, DockerChecker dockerChecker) {
    this.config = config;
    this.workspace = workspace;
    this.commandBuilder = new CommandBuilder();
    this.outputValidator = new OutputValidator(config.maxOutputSizeKb());
    this.dockerChecker = dockerChecker;

    // Validate Docker availability if any agent uses it
    validateDockerIfNeeded();
}

private void validateDockerIfNeeded() {
    boolean anyAgentUsesDocker = config.agents().values().stream()
        .filter(AgentConfig::enabled)
        .anyMatch(a -> a.docker().enabled());

    if (anyAgentUsesDocker) {
        dockerChecker.requireDocker();
        log.info("Docker mode enabled for container-based agent execution");
    }
}

// In executeAgent() method - SIMPLIFIED (no path translation here):
private AgentResult executeAgent(AgentConfig agentConfig, int round) {
    Path promptFile = workspace.getRoundPromptPath(round, agentConfig.name());
    Path agentDir = workspace.getAgentDir(round, agentConfig.name());
    Path outputFile = agentDir.resolve("review.md");
    Path projectRoot = workspace.getArenaDir().getParent();

    // CommandBuilder uses HOST paths - path translation happens in AgentProcess.resolveCommand()
    List<String> command = commandBuilder.build(agentConfig, promptFile, outputFile);

    AgentProcess process = AgentProcess.builder()
        .agentName(agentConfig.name())
        .round(round)
        .command(command)           // HOST paths from CommandBuilder
        .workingDir(projectRoot)
        .outputFile(outputFile)     // HOST path for output validation
        .promptFile(promptFile)     // HOST path for stdin redirection
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
```

**Key design:** AgentExecutor does NOT do path translation. It passes HOST paths everywhere:
- `command` contains HOST paths (from CommandBuilder)
- `promptFile` and `outputFile` are HOST paths for stdin/validation

Path translation happens inside `AgentProcess.resolveCommand()` → `DockerCommandBuilder.build()` which translates HOST paths in the command to container paths. The `promptFile` for stdin redirection stays as a HOST path (see AgentProcess Changes above).

### 4. ReviewArenaCli Changes

No changes required. Docker validation happens automatically in `AgentExecutor` constructor.

### 5. executeSynthesis Changes

Update `AgentExecutor.executeSynthesis()` for Docker support:

```java
/**
 * Executes the synthesis step using the specified agent.
 *
 * <p>Uses AgentProcess for consistent command resolution across platforms,
 * particularly for Windows where CLI tools need cmd.exe wrapping, and for
 * Docker where paths need translation to container paths.
 *
 * @param agentName  the name of the agent to use for synthesis (must be "claude")
 * @param promptPath the path to the synthesis prompt (HOST path)
 * @param outputPath the path where champion_review.md should be written (HOST path)
 * @return the synthesis result
 * @throws AgentException if agent is not found or disabled
 */
public SynthesisResult executeSynthesis(String agentName, Path promptPath, Path outputPath) {
    AgentConfig agentConfig = config.agents().get(agentName);
    if (agentConfig == null || !agentConfig.enabled()) {
        throw new AgentException("Synthesizer agent '" + agentName + "' not found or disabled");
    }

    log.info("[SYNTHESIS] Starting synthesis with agent: {}", agentName);

    Path finalDir = workspace.getFinalDir();
    Path projectRoot = workspace.getArenaDir().getParent();

    // CommandBuilder uses HOST paths - same as executeAgent()
    List<String> command = commandBuilder.build(agentConfig, promptPath, outputPath);

    // Use AgentProcess for consistent command resolution
    // Docker path translation happens in resolveCommand() if Docker is enabled
    AgentProcess process = AgentProcess.builder()
        .agentName(agentName + "-synthesis")
        .round(SYNTHESIS_ROUND)
        .command(command)             // HOST paths from CommandBuilder
        .workingDir(projectRoot)
        .outputFile(outputPath)       // HOST path for output validation
        .promptFile(promptPath)       // HOST path for stdin redirection
        .stdoutLog(finalDir.resolve("synthesis-stdout.log"))
        .stderrLog(finalDir.resolve("synthesis-stderr.log"))
        .timeoutMs(config.agentTimeoutMs())
        .gracePeriodMs(config.gracePeriodMs())
        .outputValidator(outputValidator)
        .showOutput(config.showAgentOutput())
        .dockerConfig(agentConfig.docker())  // NEW: pass Docker config
        .build();

    AgentResult result = process.execute();

    // Convert AgentResult to SynthesisResult (unchanged)
    return switch (result.status()) {
        case SUCCESS -> {
            log.info("[SYNTHESIS] Synthesis completed successfully in {}ms", result.durationMs());
            yield SynthesisResult.success(agentName, result.durationMs(), outputPath);
        }
        case TIMEOUT -> {
            log.error("[SYNTHESIS] Synthesis timed out after {}ms", result.durationMs());
            yield SynthesisResult.timeout(agentName, result.durationMs());
        }
        case FAILED, INVALID_OUTPUT -> {
            log.error("[SYNTHESIS] Synthesis failed: {}", result.failureReason());
            yield SynthesisResult.failed(agentName, result.durationMs(), result.failureReason());
        }
    };
}
```

**Note:** Synthesis uses the same pattern as `executeAgent()`:
- All paths passed are HOST paths
- `dockerConfig` is passed to AgentProcess
- Path translation happens in `AgentProcess.resolveCommand()` if Docker is enabled

## Path Translation

When running in Docker, paths must be translated from host paths to container paths:

| Host Path | Container Path |
|-----------|----------------|
| `{workingDir}` | `/workspace` |
| `{workingDir}/.arena/...` | `/workspace/.arena/...` |
| `C:\project\.arena\prompt.md` | `/workspace/.arena/prompt.md` |
| `/home/user/project/.arena/review.md` | `/workspace/.arena/review.md` |

**Path translation responsibility:**

| Component | Responsibility |
|-----------|---------------|
| `CommandBuilder` | **Unchanged** - produces commands with HOST paths |
| `AgentProcess.resolveCommand()` | Detects Docker mode and delegates to DockerCommandBuilder |
| `DockerCommandBuilder.build()` | Wraps command with `docker run` AND translates HOST paths to container paths |
| `DockerCommandBuilder.translatePathsInCommand()` | Scans command args for host paths, replaces with `/workspace/...` |
| `DockerCommandBuilder.toContainerPath()` | Static utility for explicit single-path translation |

**Design rationale:**
- `CommandBuilder` remains Docker-agnostic (no changes needed)
- Path translation is centralized in `DockerCommandBuilder`
- Translation happens AFTER CommandBuilder runs, so `toAbsolutePath()` works correctly
- stdin/stdout paths remain as HOST paths (for ProcessBuilder and OutputValidator)

## Implementation Steps

### ~~Phase 0: Test Migration (MUST DO FIRST)~~ ✅ COMPLETED

> **Commit:** `35625e4` - Migrate test usages to AgentConfig factory methods

~~**Why:** Adding a 5th parameter to `AgentConfig` record breaks 70 existing constructor calls (67 in tests + 3 in main).
This phase updates all existing code to be compatible before making the breaking change.~~

~~1. **Audit existing AgentConfig usages**~~

~~2. **Update all test files to use factory methods**~~
   ~~Convert `new AgentConfig(name, command, flags, enabled)` to `AgentConfig.of(...)` or `AgentConfig.disabled(...)`:~~

   | File | Status |
   |------|--------|
   | `AgentExecutorTest.java` | ✅ |
   | `AgentExecutorIT.java` | ✅ |
   | `WorkspaceManagerTest.java` | ✅ |
   | `CommandBuilderTest.java` | ✅ |
   | `AgentConfigTest.java` | ✅ (validation tests kept as-is) |
   | `ReviewArenaCliTest.java` | ✅ |
   | `ReviewAggregatorTest.java` | ✅ |

~~3. **Update ConfigLoader.loadAgentConfig()**~~ (deferred to Phase 1b)

~~4. **Run all tests to verify no regressions**~~ ✅ All 145 tests pass

### ~~Phase 1: Core Infrastructure~~ ✅ COMPLETED

> **Commit:** `1d7673b` - Implement Phase 1: DockerConfig record and AgentConfig integration

~~1. **Create `DockerConfig` record**~~
   ~~- File: `src/main/java/dev/reviewarena/config/DockerConfig.java`~~
   ~~- Simple record with enabled, image, memory, cpus fields~~
   ~~- Include `disabled()` factory method~~

~~2. **Update `AgentConfig` record**~~
   ~~- File: `src/main/java/dev/reviewarena/config/AgentConfig.java`~~
   ~~- Add `DockerConfig docker` field as 5th parameter~~
   ~~- Update compact constructor: `docker = docker != null ? docker : DockerConfig.disabled();`~~
   ~~- Update static factory methods to pass `DockerConfig.disabled()`~~
   ~~- **Note:** Tests using factory methods will continue to work; only tests using
     constructor with `enabled=false` need the 5th parameter added~~

~~3. **Update remaining constructor calls**~~
   ~~- Search for `new AgentConfig(` and add 5th parameter where needed~~
   ~~- These are tests that need `enabled=false` (can't use factory methods)~~

~~4. **Update `ConfigLoader`**~~
   ~~- File: `src/main/java/dev/reviewarena/config/ConfigLoader.java`~~
   ~~- Add `loadDockerConfig(SmallRyeConfig config, String prefix)` method~~
   ~~- Update `loadAgentConfig()` to call `loadDockerConfig()` and pass to AgentConfig~~

~~5. **Add unit tests for config changes**~~
   ~~- File: `src/test/java/dev/reviewarena/config/DockerConfigTest.java`~~
   ~~- Test DockerConfig parsing with SmallRyeConfig~~
   ~~- Test default values (disabled when section missing)~~
   ~~- Test AgentConfig with docker field~~

### ~~Phase 2: Docker Command Building~~ ✅ COMPLETED

> **Commit:** `0760446` - Implement Phase 2: Docker command building infrastructure

~~6. **Create `DockerCommandBuilder`**~~
   ~~- File: `src/main/java/dev/reviewarena/agent/DockerCommandBuilder.java`~~
   ~~- Build `docker run` commands with `-i` flag for stdin forwarding~~
   ~~- Handle default images per agent (with detailed error for Codex)~~
   ~~- Add resource limits (memory, cpus)~~
   ~~- Pass environment variables (ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY, GOOGLE_API_KEY)~~
   ~~- Include `translatePathsInCommand()` for HOST→container path translation~~
   ~~- **Fail-fast:** Throw `ConfigException` for paths outside workingDir (not just warn)~~
   ~~- Include `looksLikeAbsolutePath()` heuristic for path detection~~
   ~~- Include static `toContainerPath()` utility method with bounds validation~~

~~7. **Create `DockerChecker` interface**~~
   ~~- File: `src/main/java/dev/reviewarena/agent/DockerChecker.java`~~
   ~~- Define `requireDocker()` and `imageExists()` methods~~
   ~~- Enables dependency injection for testing~~

~~8. **Create `DockerAvailabilityChecker`**~~
   ~~- File: `src/main/java/dev/reviewarena/agent/DockerAvailabilityChecker.java`~~
   ~~- Implements `DockerChecker` interface~~
   ~~- Check Docker daemon status via `docker info`~~
   ~~- Provide clear error messages for common failures~~
   ~~- Include `imageExists()` method for optional image pre-check~~

~~9. **Add unit tests for command building**~~
   ~~- File: `src/test/java/dev/reviewarena/agent/DockerCommandBuilderTest.java`~~
   ~~- Test command generation with various configs~~
   ~~- Test `translatePathsInCommand()` with Windows and Linux host paths~~
   ~~- Test that `-i` flag is included for stdin forwarding~~
   ~~- Test environment variable passing (only when env vars are set)~~
   ~~- Test default image selection and error when no default (Codex)~~
   ~~- Test `toContainerPath()` validation (throws for paths outside workingDir)~~
   ~~- Test `translatePathsInCommand()` throws ConfigException for external paths~~
   ~~- Test `looksLikeAbsolutePath()` edge cases~~

### Phase 3: Integration ✅ COMPLETED (steps 10-12)

> **Commit:** `617d406` - Implement Phase 3: AgentProcess and AgentExecutor Docker integration

~~10. **Update `AgentProcess`**~~
    ~~- File: `src/main/java/dev/reviewarena/agent/AgentProcess.java`~~
    ~~- Add `DockerConfig dockerConfig` field (never null, from AgentConfig)~~
    ~~- Add `DockerCommandBuilder dockerCommandBuilder` instance field~~
    ~~- Add `dockerConfig(DockerConfig)` to Builder with validation~~
    ~~- Update `resolveCommand()` to call DockerCommandBuilder when Docker enabled (FIRST, before Windows check)~~
    ~~- Document that Docker commands don't need cmd /c wrapping~~
    ~~- Document stdin redirection works for both native and Docker modes~~

~~11. **Update `AgentExecutor`**~~
    ~~- File: `src/main/java/dev/reviewarena/agent/AgentExecutor.java`~~
    ~~- Add `DockerChecker` field (interface for testability)~~
    ~~- Add package-private constructor for test injection~~
    ~~- Add `validateDockerIfNeeded()` method (call from constructor)~~
    ~~- Update `executeAgent()` to pass `dockerConfig` to AgentProcess builder~~
    ~~- Update `executeSynthesis()` to pass `dockerConfig` to AgentProcess builder~~
    ~~- **No path translation needed** - HOST paths flow through, translation in AgentProcess~~

~~12. **CommandBuilder - NO CHANGES NEEDED**~~
    ~~- `CommandBuilder.java` remains unchanged~~
    ~~- Produces commands with HOST paths (as it always has)~~
    ~~- Path translation happens in `DockerCommandBuilder` (called from `AgentProcess`)~~

13. **Add integration tests** (deferred - requires Docker in CI)
    - File: `src/test/java/dev/reviewarena/agent/DockerAgentExecutionIT.java`
    - Test agent execution in Docker (requires Docker in CI)
    - Use JUnit Assumptions to skip when Docker unavailable
    - Test with missing Docker produces clear error

### Phase 4: Documentation ✅ COMPLETED

> **Commit:** `703aa7b` - Implement Phase 4: Documentation for Docker support

~~14. **Update README**~~
    ~~- Docker setup instructions~~
    ~~- Example configurations~~
    ~~- Troubleshooting guide~~

~~15. **Update arena.yaml examples**~~
    ~~- Show Docker configuration~~
    ~~- Document default images (note Codex requires manual setup)~~

## Testing Strategy

### Unit Tests (No Docker Required)

| Test Class | Coverage |
|------------|----------|
| `DockerConfigTest` | Record creation, `disabled()` factory, null handling |
| `DockerCommandBuilderTest` | Command generation, path translation, `-i` flag, env vars, default images, **fail-fast for external paths** |
| `DockerAvailabilityCheckerTest` | **Mocked** - see below |
| `ConfigLoaderTest` | Extended: parsing docker section from YAML, defaults when missing |
| `AgentConfigTest` | Extended: docker field null-safety, factory methods, `disabled()` factory |
| `AgentProcessTest` | Extended: `resolveCommand()` with Docker enabled vs disabled |
| `AgentExecutorTest` | Extended: mock DockerChecker injection, validation skipped when Docker disabled |

### Mocking Strategy for DockerAvailabilityChecker

`DockerAvailabilityChecker` runs actual Docker commands, which makes it hard to unit test.
**Solution:** The `DockerChecker` interface (created in Phase 2) enables constructor injection for mocking.

```java
// DockerChecker interface already defined in Phase 2
// DockerAvailabilityChecker implements DockerChecker

// AgentExecutor uses interface for testability (see Phase 3)
public class AgentExecutor {
    private final DockerChecker dockerChecker;

    // Production constructor
    public AgentExecutor(ArenaConfig config, WorkspaceManager workspace) {
        this(config, workspace, new DockerAvailabilityChecker());
    }

    // Package-private constructor for testing
    AgentExecutor(ArenaConfig config, WorkspaceManager workspace, DockerChecker dockerChecker) {
        this.dockerChecker = dockerChecker;
        // ...
    }
}

// Unit test with mock
@Test
void throwsWhenDockerUnavailable() {
    DockerChecker mockChecker = mock(DockerChecker.class);
    doThrow(new ConfigException("Docker not available"))
        .when(mockChecker).requireDocker();

    var config = createConfigWithDockerEnabled();
    assertThrows(ConfigException.class,
        () -> new AgentExecutor(config, workspace, mockChecker));
}

@Test
void skipsDockerValidationWhenNoAgentUsesDocker() {
    DockerChecker mockChecker = mock(DockerChecker.class);

    var config = createConfigWithDockerDisabled();
    // Should NOT throw - Docker not needed
    new AgentExecutor(config, workspace, mockChecker);

    // Verify requireDocker() was never called
    verify(mockChecker, never()).requireDocker();
}
```

### Unit Test for Path Translation (No Docker Required)

```java
@Test
void translatePathsInCommand_windowsPath() {
    var builder = new DockerCommandBuilder();
    Path workingDir = Path.of("C:/project");

    List<String> command = List.of(
        "claude", "-p", "C:/project/.arena/rounds/0/claude/prompt.md"
    );

    // Use reflection or make method package-private for testing
    List<String> translated = builder.translatePathsInCommand(command, workingDir);

    assertThat(translated).containsExactly(
        "claude", "-p", "/workspace/.arena/rounds/0/claude/prompt.md"
    );
}

@Test
void translatePathsInCommand_throwsForExternalPath() {
    var builder = new DockerCommandBuilder();
    Path workingDir = Path.of("/home/user/project");

    List<String> command = List.of(
        "claude", "-p", "/etc/passwd"  // External path!
    );

    // Fail-fast: throws ConfigException for paths outside workingDir
    ConfigException ex = assertThrows(ConfigException.class,
        () -> builder.translatePathsInCommand(command, workingDir));

    assertThat(ex.getMessage()).contains("/etc/passwd");
    assertThat(ex.getMessage()).contains("outside the project directory");
}
```

### Integration Tests (Docker Required)

| Test | Requirement | Notes |
|------|-------------|-------|
| `DockerAgentExecutionIT` | Docker daemon running | Full agent execution in container |
| `DockerAvailabilityCheckerIT` | Any environment | Tests real `docker info` call |

**JUnit 5 Assumptions for Docker tests:**

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

    @Test
    void dockerModeTranslatesPathsCorrectly() {
        // Verify that command arguments with host paths
        // are translated to /workspace/... paths
    }

    @Test
    void stdinRedirectionWorksInDocker() {
        // Verify that prompt file content reaches the container via stdin
    }
}
```

### CI/CD Configuration

**GitHub Actions example:**

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Run unit tests (no Docker)
        run: mvn test

      - name: Run integration tests (with Docker)
        run: mvn verify -Dsurefire.includes="**/*IT*"
        # Docker is available by default on ubuntu-latest
```

**For environments without Docker:**
Integration tests will be automatically skipped via JUnit Assumptions.
Unit tests will pass because they use mocks or test pure logic.

## Error Messages

| Scenario | Error Message |
|----------|---------------|
| Docker not installed | `Docker is not installed or not in PATH. Install Docker Desktop or ensure 'docker' command is available.` |
| Docker daemon not running | `Docker daemon is not running. Start Docker Desktop or run 'sudo systemctl start docker'.` |
| Image not found | `Docker image '{image}' not found. Run 'docker pull {image}' or check the image name.` |
| No default image (Codex) | `No Docker image specified for agent 'codex' and no default available. For Codex, you must build or specify an image: Option 1: Build locally... Option 2: Specify in arena.yaml...` |
| Path outside project | `Command argument '{path}' is an absolute path outside the project directory '{workingDir}'. Docker containers can only access files mounted at /workspace.` |

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

## File Changes Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `src/main/java/dev/reviewarena/config/DockerConfig.java` | New | Docker configuration record |
| `src/main/java/dev/reviewarena/config/AgentConfig.java` | Modify | Add `docker` field, add `disabled()` factory method |
| `src/main/java/dev/reviewarena/config/ConfigLoader.java` | Modify | Add `loadDockerConfig()` method |
| `src/main/java/dev/reviewarena/agent/DockerChecker.java` | New | Interface for Docker availability (enables testing) |
| `src/main/java/dev/reviewarena/agent/DockerAvailabilityChecker.java` | New | Implements DockerChecker, validates Docker availability |
| `src/main/java/dev/reviewarena/agent/DockerCommandBuilder.java` | New | Builds `docker run` commands with path translation |
| `src/main/java/dev/reviewarena/agent/AgentProcess.java` | Modify | Add `dockerConfig` field, Docker command wrapping in `resolveCommand()` |
| `src/main/java/dev/reviewarena/agent/AgentExecutor.java` | Modify | Add Docker validation with interface injection, pass `dockerConfig` to AgentProcess |
| `src/main/java/dev/reviewarena/agent/CommandBuilder.java` | **No change** | Produces HOST paths; translation in DockerCommandBuilder |
| `src/test/java/dev/reviewarena/config/DockerConfigTest.java` | New | Unit tests for DockerConfig |
| `src/test/java/dev/reviewarena/agent/DockerCommandBuilderTest.java` | New | Unit tests for command building |
| `src/test/java/dev/reviewarena/agent/DockerAvailabilityCheckerTest.java` | New | Unit tests for availability checker (mocked) |
| `src/test/java/dev/reviewarena/agent/DockerAgentExecutionIT.java` | New | Integration tests (requires Docker) |
| `src/test/java/dev/reviewarena/config/ConfigLoaderTest.java` | Modify | Add tests for docker config parsing |
| `src/test/java/dev/reviewarena/agent/AgentProcessTest.java` | Modify | Add tests for Docker command resolution |
| `src/test/java/dev/reviewarena/agent/AgentExecutorTest.java` | Modify | Add tests using mock DockerChecker |
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
