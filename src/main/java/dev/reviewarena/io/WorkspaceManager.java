package dev.reviewarena.io;

import dev.reviewarena.config.ArenaConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the .arena/ workspace directory structure.
 *
 * <p>Responsible for:
 * <ul>
 *   <li>Clearing existing .arena/ directory (fresh start each run)</li>
 *   <li>Creating the round directory structure with agent subdirectories</li>
 *   <li>Generating task.md from template with placeholder substitution</li>
 *   <li>Pre-generating all round prompts at initialization time</li>
 * </ul>
 *
 * <p>Directory structure created:
 * <pre>
 * .arena/
 * ├── prompts/
 * │   ├── task.md
 * │   ├── round-0-claude.md
 * │   ├── round-0-codex.md
 * │   ├── round-0-gemini.md
 * │   ├── round-1-claude.md
 * │   └── ...
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

    private static final String TASK_TEMPLATE = "task.md";

    private final Path projectRoot;
    private final ArenaConfig config;
    private final TemplateLoader templateLoader;

    /**
     * Creates a WorkspaceManager for the given project root.
     *
     * @param projectRoot the project root directory (where .arena/ will be created)
     * @param config the arena configuration
     */
    public WorkspaceManager(Path projectRoot, ArenaConfig config) {
        this(projectRoot, config, new TemplateLoader());
    }

    /**
     * Creates a WorkspaceManager with a custom TemplateLoader.
     * Package-private for testing.
     *
     * @param projectRoot the project root directory
     * @param config the arena configuration
     * @param templateLoader the template loader to use
     */
    WorkspaceManager(Path projectRoot, ArenaConfig config, TemplateLoader templateLoader) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.templateLoader = templateLoader;
    }

    /**
     * Initializes the workspace by clearing any existing .arena/ directory
     * and creating the fresh directory structure.
     *
     * @param commit1    first commit reference (empty string if using --staged)
     * @param commit2    second commit reference for ranges (empty string for single commit or --staged)
     * @param stagedFlag the staged flag value ("--staged" if reviewing staged, empty string otherwise)
     * @return the path to the created .arena/ directory
     * @throws WorkspaceException if directory creation fails
     */
    public Path initialize(String commit1, String commit2, String stagedFlag) {
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

            // Generate task.md using TemplateLoader
            generateTaskMd(arenaDir);

            // Pre-generate all round prompts
            generateAllRoundPrompts(arenaDir, commit1, commit2, stagedFlag);

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
        return getPromptsDir().resolve("task.md");
    }

    /**
     * Gets the path to the prompts directory.
     *
     * @return the prompts directory path
     */
    public Path getPromptsDir() {
        return getArenaDir().resolve("prompts");
    }

    /**
     * Gets the path to a specific round's pre-generated prompt for an agent.
     *
     * @param round the round number (0-indexed)
     * @param agentName the agent name
     * @return the round prompt path
     */
    public Path getRoundPromptPath(int round, String agentName) {
        return getPromptsDir().resolve("round-" + round + "-" + agentName + ".md");
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
                .collect(Collectors.toSet());
    }

    private void generateTaskMd(Path arenaDir) throws IOException {
        Path promptsDir = arenaDir.resolve("prompts");
        Files.createDirectories(promptsDir);
        // Review target will be set by CLI when it processes arguments
        TemplateContext context = TemplateContext.forTask("{{review_target}}");
        String content = templateLoader.render(TASK_TEMPLATE, context);
        Files.writeString(promptsDir.resolve("task.md"), content, StandardCharsets.UTF_8);
    }

    private void generateAllRoundPrompts(Path arenaDir, String commit1, String commit2,
                                         String stagedFlag) throws IOException {
        Path promptsDir = arenaDir.resolve("prompts");
        Files.createDirectories(promptsDir);

        // Get task.md content to prepend to each round prompt
        String taskContent = templateLoader.render(TASK_TEMPLATE, TemplateContext.forTask("{{review_target}}"));

        Set<String> agentNames = getEnabledAgentNames();

        // Generate prompt for each round and each agent
        for (int round = 0; round <= config.maxRounds(); round++) {
            for (String agentName : agentNames) {
                String outputPath = ".arena/rounds/round-" + round + "/" + agentName + "/review.md";
                String allReviewsPath = (round == 0) ? null
                        : ".arena/rounds/round-" + (round - 1) + "/all_reviews.md";

                TemplateContext ctx = TemplateContext.forRound(round, outputPath, allReviewsPath,
                        commit1, commit2, stagedFlag);
                String roundContent = templateLoader.render("round-" + round + ".md", ctx);

                // Combine task + round into a complete standalone prompt
                String fullPrompt = taskContent + "\n\n---\n\n" + roundContent;

                Files.writeString(promptsDir.resolve("round-" + round + "-" + agentName + ".md"),
                        fullPrompt, StandardCharsets.UTF_8);
            }
        }
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
