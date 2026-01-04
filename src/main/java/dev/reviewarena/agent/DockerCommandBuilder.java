package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;
import dev.reviewarena.config.DockerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    List<String> translatePathsInCommand(List<String> command, Path workingDir) {
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
                    ("Command argument '%s' is an absolute path outside the project directory '%s'. "
                    + "Docker containers can only access files mounted at /workspace. "
                    + "Move the file under the project directory or use a relative path.")
                    .formatted(arg, workingDir));
            }

            translated.add(translatedArg);
        }
        return translated;
    }

    /**
     * Heuristic check for absolute paths (Windows or Unix style).
     */
    boolean looksLikeAbsolutePath(String arg) {
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
