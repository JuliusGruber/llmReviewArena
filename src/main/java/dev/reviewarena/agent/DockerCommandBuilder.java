package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;
import dev.reviewarena.config.DockerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Builds Docker run commands for containerized agent execution.
 *
 * <p>Path translation: This class handles ALL path translation from host paths to
 * container paths. The workingDir is mounted as /workspace, so:
 * <ul>
 *   <li>{@code C:\project\.arena\prompt.md} becomes {@code /workspace/.arena/prompt.md}</li>
 *   <li>{@code /home/user/project/.arena/review.md} becomes {@code /workspace/.arena/review.md}</li>
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
        "gemini", "naoyoshinori/gemini-cli:node"
    );

    // Agents supported by Docker Desktop Sandbox ('docker sandbox run')
    private static final Set<String> SANDBOX_AGENTS = Set.of("claude", "gemini");

    private static final List<String> API_KEY_ENV_VARS = List.of(
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY"  // Gemini CLI may use either GEMINI_API_KEY or GOOGLE_API_KEY
    );

    // Validation patterns for Docker resource limits (case-insensitive)
    private static final Pattern MEMORY_PATTERN = Pattern.compile("^\\d+[bkmgBKMG]?$");
    private static final Pattern CPUS_PATTERN = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final Set<String> VALID_NETWORK_MODES = Set.of("bridge", "host", "none");

    /**
     * Wraps an agent command with docker run or docker sandbox run.
     *
     * <p>The command arguments should contain HOST paths (absolute paths on the host
     * filesystem). This method scans the command for paths under workingDir and
     * translates them to container paths (/workspace/...).
     *
     * @param agentName   Name of the agent (for default image lookup or sandbox agent name)
     * @param docker      Docker configuration
     * @param command     Agent command with HOST paths (as produced by CommandBuilder)
     * @param workingDir  Host working directory to mount as /workspace
     * @return Complete docker command with translated paths
     */
    public List<String> build(
            String agentName,
            DockerConfig docker,
            List<String> command,
            Path workingDir
    ) {
        if (docker.sandbox()) {
            return buildSandboxCommand(agentName, docker, command, workingDir);
        }
        return buildDockerRunCommand(agentName, docker, command, workingDir);
    }

    /**
     * Builds a Docker Desktop Sandbox command ('docker sandbox run').
     *
     * <p>Sandbox mode is simpler than docker run - it handles:
     * <ul>
     *   <li>Credential management automatically</li>
     *   <li>Container cleanup</li>
     *   <li>Network configuration</li>
     *   <li>User permissions</li>
     * </ul>
     *
     * <p>Supported agents: claude, gemini
     */
    private List<String> buildSandboxCommand(
            String agentName,
            DockerConfig docker,
            List<String> command,
            Path workingDir
    ) {
        var result = new ArrayList<String>();

        result.add("docker");
        result.add("sandbox");
        result.add("run");

        // Mount project directory as /workspace
        result.add("-v");
        result.add(workingDir.toAbsolutePath() + ":/workspace");

        // Pass API keys from host environment (only if set)
        for (String envVar : API_KEY_ENV_VARS) {
            if (System.getenv(envVar) != null) {
                result.add("-e");
                result.add(envVar + "=" + System.getenv(envVar));
            }
        }

        // Agent name (claude or gemini)
        String sandboxAgent = agentName.toLowerCase();
        if (!SANDBOX_AGENTS.contains(sandboxAgent)) {
            throw new ConfigException(
                "Docker sandbox mode is only supported for agents: " + SANDBOX_AGENTS + ". " +
                "Agent '%s' is not supported. Use regular Docker mode (sandbox: false) instead."
                .formatted(agentName));
        }
        result.add(sandboxAgent);

        // Add agent-specific flags (skip the agent command name, keep flags)
        // command = ["claude", "-p"] -> we only need ["-p"]
        List<String> agentFlags = extractAgentFlags(command, agentName);
        List<String> translatedFlags = translatePathsInCommand(agentFlags, workingDir);
        result.addAll(translatedFlags);

        log.debug("Built Docker sandbox command for {}: {}", agentName, result);
        return List.copyOf(result);
    }

    /**
     * Extracts agent flags from the command, removing the agent command name.
     * E.g., ["claude", "-p", "/path/file"] -> ["-p", "/path/file"]
     */
    private List<String> extractAgentFlags(List<String> command, String agentName) {
        if (command.isEmpty()) {
            return List.of();
        }
        // Skip the first element if it matches the agent name
        String first = command.getFirst().toLowerCase();
        if (first.equals(agentName.toLowerCase()) || first.endsWith("/" + agentName.toLowerCase())) {
            return command.subList(1, command.size());
        }
        return command;
    }

    /**
     * Builds a standard Docker run command.
     */
    private List<String> buildDockerRunCommand(
            String agentName,
            DockerConfig docker,
            List<String> command,
            Path workingDir
    ) {
        // Validate resource limits before building command
        validateResourceLimits(docker);

        var result = new ArrayList<String>();

        result.add("docker");
        result.add("run");
        result.add("--rm");                    // Ephemeral container
        result.add("-i");                      // Keep stdin open for prompt redirection

        // Network configuration (defaults to bridge if not specified)
        addNetworkConfig(result, docker);

        // Mount project directory as /workspace
        result.add("-v");
        result.add(workingDir.toAbsolutePath() + ":/workspace");
        result.add("-w");
        result.add("/workspace");

        // Add user mapping for file ownership (Linux/macOS only)
        String userMapping = getHostUserMapping();
        if (userMapping != null) {
            result.add("--user");
            result.add(userMapping);
        }

        // Pass API keys from host environment (only if set)
        // Note: Env vars must be set in the shell that launches the arena
        for (String envVar : API_KEY_ENV_VARS) {
            if (System.getenv(envVar) != null) {
                result.add("-e");
                result.add(envVar);
            }
        }

        // Optional resource limits (already validated)
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
     * Validates Docker resource limits to prevent command injection.
     *
     * @param docker Docker configuration to validate
     * @throws ConfigException if memory or cpus format is invalid
     */
    private void validateResourceLimits(DockerConfig docker) {
        if (docker.memory() != null && !MEMORY_PATTERN.matcher(docker.memory()).matches()) {
            throw new ConfigException(
                "Invalid Docker memory format: '%s'. Expected format: <number>[b|k|m|g] (e.g., '4g', '512m', '1024M')"
                .formatted(docker.memory()));
        }
        if (docker.cpus() != null && !CPUS_PATTERN.matcher(docker.cpus()).matches()) {
            throw new ConfigException(
                "Invalid Docker cpus format: '%s'. Expected format: <number> or <number.decimal> (e.g., '2', '1.5')"
                .formatted(docker.cpus()));
        }
        if (docker.networkMode() != null && !docker.networkMode().isBlank()
                && !VALID_NETWORK_MODES.contains(docker.networkMode().toLowerCase())) {
            throw new ConfigException(
                "Invalid Docker networkMode: '%s'. Valid values: bridge, host, none"
                .formatted(docker.networkMode()));
        }
    }

    /**
     * Adds network configuration to the Docker command.
     * Defaults to bridge networking if not specified.
     */
    private void addNetworkConfig(List<String> result, DockerConfig docker) {
        if (docker.networkMode() != null && !docker.networkMode().isBlank()) {
            String osName = System.getProperty("os.name").toLowerCase();
            if ("host".equalsIgnoreCase(docker.networkMode()) && !osName.contains("linux")) {
                log.warn("Host networking is not supported on {}. Using default bridge networking.", osName);
                // Don't add --network flag, let Docker use its default (bridge)
            } else {
                result.add("--network");
                result.add(docker.networkMode().toLowerCase());
            }
        }
        // else: omit --network flag, Docker defaults to bridge
    }

    /**
     * Gets the current user's UID:GID for container user mapping.
     * This ensures files created in the container are owned by the host user.
     *
     * @return UID:GID string (e.g., "1000:1000"), or null on Windows or if unavailable
     */
    private String getHostUserMapping() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return null; // Windows doesn't use Unix-style uid/gid
        }

        try {
            ProcessBuilder uidPb = new ProcessBuilder("id", "-u");
            ProcessBuilder gidPb = new ProcessBuilder("id", "-g");

            Process uidProcess = uidPb.start();
            Process gidProcess = gidPb.start();

            String uid = new String(uidProcess.getInputStream().readAllBytes()).trim();
            String gid = new String(gidProcess.getInputStream().readAllBytes()).trim();

            boolean uidCompleted = uidProcess.waitFor(5, TimeUnit.SECONDS);
            boolean gidCompleted = gidProcess.waitFor(5, TimeUnit.SECONDS);

            if (uidCompleted && gidCompleted
                    && !uid.isEmpty() && !gid.isEmpty()
                    && uid.matches("\\d+") && gid.matches("\\d+")) {
                return uid + ":" + gid;
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Could not determine host UID/GID. Container files may be owned by root.", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    /**
     * Translates host paths in command arguments to container paths.
     *
     * <p>Uses proper path-prefix checking via {@link Path#startsWith} to avoid
     * substring matching bugs (e.g., /tmp/proj matching /tmp/project2).
     *
     * <p><b>Fail-fast behavior:</b> Absolute paths outside workingDir cannot be
     * translated and will cause this method to throw ConfigException immediately,
     * rather than allowing the container to fail later with a cryptic "file not found" error.
     *
     * @param command    Command with host paths
     * @param workingDir Host working directory (mounted as /workspace)
     * @return Command with translated container paths
     * @throws ConfigException if command contains absolute paths outside workingDir
     */
    List<String> translatePathsInCommand(List<String> command, Path workingDir) {
        Path absWorkingDir = workingDir.toAbsolutePath().normalize();
        List<String> translated = new ArrayList<>();

        for (String arg : command) {
            try {
                Path argPath = Path.of(arg);
                if (argPath.isAbsolute()) {
                    Path absArg = argPath.toAbsolutePath().normalize();
                    if (absArg.startsWith(absWorkingDir)) {
                        // Path is under working directory - translate to container path
                        Path relative = absWorkingDir.relativize(absArg);
                        String containerPath = "/workspace/" + relative.toString().replace('\\', '/');
                        translated.add(containerPath);
                        continue;
                    } else {
                        // Absolute path outside working directory - fail fast
                        throw new ConfigException(
                            ("Command argument '%s' is an absolute path outside the project directory '%s'. "
                            + "Docker containers can only access files mounted at /workspace. "
                            + "Move the file under the project directory or use a relative path.")
                            .formatted(arg, workingDir));
                    }
                }
            } catch (InvalidPathException e) {
                // Not a valid path syntax (e.g., contains invalid characters) - pass through
            }
            translated.add(arg);
        }
        return translated;
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
