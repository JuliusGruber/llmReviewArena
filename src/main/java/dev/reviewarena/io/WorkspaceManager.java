package dev.reviewarena.io;

import dev.reviewarena.config.ArenaConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Manages the .arena/ workspace directory structure.
 *
 * <p>Responsible for:
 * <ul>
 *   <li>Clearing existing .arena/ directory (fresh start each run)</li>
 *   <li>Creating the round directory structure with agent subdirectories</li>
 *   <li>Generating task.md from template with placeholder substitution</li>
 * </ul>
 *
 * <p>Directory structure created:
 * <pre>
 * .arena/
 * ├── task.md
 * └── rounds/
 *     ├── round-0/
 *     │   ├── claude/
 *     │   ├── codex/
 *     │   └── gemini/
 *     ├── round-1/
 *     │   └── ...
 *     └── final/
 * </pre>
 */
public class WorkspaceManager {

    private static final String TASK_TEMPLATE_PATH = "prompts/task.md";

    private final Path projectRoot;
    private final ArenaConfig config;

    /**
     * Creates a WorkspaceManager for the given project root.
     *
     * @param projectRoot the project root directory (where .arena/ will be created)
     * @param config the arena configuration
     */
    public WorkspaceManager(Path projectRoot, ArenaConfig config) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
    }

    /**
     * Initializes the workspace by clearing any existing .arena/ directory
     * and creating the fresh directory structure.
     *
     * @param reviewTarget description of what is being reviewed (e.g., "abc1234", "abc1234..def5678", "--staged")
     * @return the path to the created .arena/ directory
     * @throws WorkspaceException if directory creation fails
     */
    public Path initialize(String reviewTarget) {
        Path arenaDir = getArenaDir();

        try {
            // Clear existing directory if it exists
            if (Files.exists(arenaDir)) {
                deleteRecursively(arenaDir);
            }

            // Create base directory structure
            Files.createDirectories(arenaDir);
            Path roundsDir = arenaDir.resolve("rounds");
            Files.createDirectories(roundsDir);

            // Create round directories (round-0 through round-N)
            Set<String> agentNames = getEnabledAgentNames();
            for (int round = 0; round <= config.maxRounds(); round++) {
                Path roundDir = roundsDir.resolve("round-" + round);
                createRoundDirectory(roundDir, agentNames);
            }

            // Create final directory
            Path finalDir = roundsDir.resolve("final");
            Files.createDirectories(finalDir);

            // Generate task.md
            generateTaskMd(arenaDir, reviewTarget);

            return arenaDir;
        } catch (IOException e) {
            throw new WorkspaceException("Failed to initialize workspace at " + arenaDir, e);
        }
    }

    /**
     * Gets the path to the .arena/ directory.
     *
     * @return the arena directory path
     */
    public Path getArenaDir() {
        return projectRoot.resolve(config.outputDir());
    }

    /**
     * Gets the path to the rounds directory.
     *
     * @return the rounds directory path
     */
    public Path getRoundsDir() {
        return getArenaDir().resolve("rounds");
    }

    /**
     * Gets the path to a specific round directory.
     *
     * @param round the round number (0-indexed)
     * @return the round directory path
     */
    public Path getRoundDir(int round) {
        return getRoundsDir().resolve("round-" + round);
    }

    /**
     * Gets the path to an agent's directory for a specific round.
     *
     * @param round the round number (0-indexed)
     * @param agentName the agent name
     * @return the agent directory path
     */
    public Path getAgentDir(int round, String agentName) {
        return getRoundDir(round).resolve(agentName);
    }

    /**
     * Gets the path to the final directory.
     *
     * @return the final directory path
     */
    public Path getFinalDir() {
        return getRoundsDir().resolve("final");
    }

    /**
     * Gets the path to the task.md file.
     *
     * @return the task.md path
     */
    public Path getTaskMdPath() {
        return getArenaDir().resolve("task.md");
    }

    private void createRoundDirectory(Path roundDir, Set<String> agentNames) throws IOException {
        Files.createDirectories(roundDir);
        for (String agentName : agentNames) {
            Files.createDirectories(roundDir.resolve(agentName));
        }
    }

    private Set<String> getEnabledAgentNames() {
        return config.agents().entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void generateTaskMd(Path arenaDir, String reviewTarget) throws IOException {
        String template = loadTemplate();
        String content = resolveTemplatePlaceholders(template, reviewTarget);
        Files.writeString(arenaDir.resolve("task.md"), content, StandardCharsets.UTF_8);
    }

    private String loadTemplate() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TASK_TEMPLATE_PATH)) {
            if (is == null) {
                throw new WorkspaceException("Template not found: " + TASK_TEMPLATE_PATH);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load template: " + TASK_TEMPLATE_PATH, e);
        }
    }

    private String resolveTemplatePlaceholders(String template, String reviewTarget) {
        // For now, we just copy the template as-is since the current task.md
        // doesn't have placeholders. Future enhancement: add placeholder resolution.
        // Placeholders like {{review_target}}, {{file_count}} would be resolved here.
        return template;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
