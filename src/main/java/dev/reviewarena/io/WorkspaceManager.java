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
    private static final String SYNTHESIS_TEMPLATE = "final-synth.md";

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
     * Converts an absolute path to a relative path from the project root.
     *
     * @param absolutePath the absolute path to convert
     * @return the relative path string
     */
    public String relativize(Path absolutePath) {
        return projectRoot.relativize(absolutePath).toString().replace('\\', '/');
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
     * Gets the path to the champion_review.md output file.
     *
     * @return the champion review path
     */
    public Path getChampionReviewPath() {
        return getFinalDir().resolve("champion_review.md");
    }

    /**
     * Generates the synthesis prompt at runtime (after tournament completes).
     *
     * @param finalRound           the last completed round number
     * @param participatingAgents  set of agents that succeeded in the final round
     * @return the path to the generated synthesis prompt
     * @throws WorkspaceException if prompt generation fails
     */
    public Path generateSynthesisPrompt(int finalRound, java.util.Set<String> participatingAgents) {
        try {
            Path finalDir = getFinalDir();
            Path promptPath = finalDir.resolve("prompt.md");
            Path allReviewsFile = getRoundDir(finalRound).resolve("all_reviews.md");

            // Validate all_reviews.md exists before synthesis
            if (!Files.exists(allReviewsFile)) {
                throw new WorkspaceException(
                    "Final round reviews not found: " + allReviewsFile +
                    ". Ensure cross-pollination completed successfully.");
            }

            // Build relative paths using configured output dir (not hardcoded .arena)
            String outputDir = config.outputDir().toString().replace('\\', '/');
            String allReviewsRelative = outputDir + "/rounds/round-" + finalRound + "/all_reviews.md";
            String outputRelative = outputDir + "/rounds/final/champion_review.md";

            // Calculate round counts
            int roundCount = finalRound + 1;  // Round 0 + cross-pollination rounds
            int crossPollinationRounds = finalRound;  // Rounds 1 through N

            // Format participating agents (only those who succeeded in final round)
            String agentsList = participatingAgents.stream()
                .sorted()
                .collect(Collectors.joining(", "));

            // Render synthesis template using SynthesisContext
            // Note: Synthesis uses a standalone prompt (not prepended with task.md)
            // to clearly establish the synthesizer role upfront
            SynthesisContext ctx = new SynthesisContext(
                outputRelative,
                allReviewsRelative,
                roundCount,
                crossPollinationRounds,
                agentsList
            );
            String fullPrompt = templateLoader.render(SYNTHESIS_TEMPLATE, ctx);

            Files.writeString(promptPath, fullPrompt, StandardCharsets.UTF_8);

            return promptPath;
        } catch (IOException e) {
            throw new WorkspaceException("Failed to generate synthesis prompt", e);
        }
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

    /**
     * Regenerates the prompts for a specific round with embedded previous reviews content.
     *
     * <p>This method should be called before executing rounds > 0 to embed the actual
     * content of the previous round's all_reviews.md directly into the prompt. This is
     * necessary because some AI agents (e.g., Gemini CLI) cannot read files in .gitignored
     * directories.
     *
     * @param round      the round number (must be > 0)
     * @param commit1    first commit reference (empty string if using --staged)
     * @param commit2    second commit reference for ranges (empty string for single commit or --staged)
     * @param stagedFlag the staged flag value ("--staged" if reviewing staged, empty string otherwise)
     * @throws WorkspaceException if prompt regeneration fails
     */
    public void regenerateRoundPrompts(int round, String commit1, String commit2, String stagedFlag) {
        if (round <= 0) {
            throw new IllegalArgumentException("Round must be > 0 for prompt regeneration");
        }

        try {
            // Read the previous round's all_reviews.md content
            Path previousReviewsFile = getRoundDir(round - 1).resolve("all_reviews.md");
            String previousReviewsContent = Files.readString(previousReviewsFile, StandardCharsets.UTF_8);

            // Get task.md content to prepend
            String taskContent = templateLoader.render(TASK_TEMPLATE, TemplateContext.forTask());

            Set<String> agentNames = getEnabledAgentNames();

            // Regenerate prompt for each agent
            String outputDir = config.outputDir().toString().replace('\\', '/');
            for (String agentName : agentNames) {
                String outputPath = outputDir + "/rounds/round-" + round + "/" + agentName + "/review.md";
                String allReviewsPath = outputDir + "/rounds/round-" + (round - 1) + "/all_reviews.md";

                TemplateContext ctx = TemplateContext.forRound(round, outputPath, allReviewsPath,
                        previousReviewsContent, commit1, commit2, stagedFlag);

                int templateRound = Math.min(round, 5);
                String roundContent = templateLoader.render("round-" + templateRound + ".md", ctx);

                String fullPrompt = taskContent + "\n\n---\n\n" + roundContent;

                Files.writeString(getPromptsDir().resolve("round-" + round + "-" + agentName + ".md"),
                        fullPrompt, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new WorkspaceException("Failed to regenerate round " + round + " prompts", e);
        }
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
        TemplateContext context = TemplateContext.forTask();
        String content = templateLoader.render(TASK_TEMPLATE, context);
        Files.writeString(promptsDir.resolve("task.md"), content, StandardCharsets.UTF_8);
    }

    private void generateAllRoundPrompts(Path arenaDir, String commit1, String commit2,
                                         String stagedFlag) throws IOException {
        Path promptsDir = arenaDir.resolve("prompts");
        Files.createDirectories(promptsDir);

        // Get task.md content to prepend to each round prompt
        String taskContent = templateLoader.render(TASK_TEMPLATE, TemplateContext.forTask());

        Set<String> agentNames = getEnabledAgentNames();

        // Generate prompt for each round and each agent
        String outputDir = config.outputDir().toString().replace('\\', '/');
        for (int round = 0; round <= config.maxRounds(); round++) {
            for (String agentName : agentNames) {
                String outputPath = outputDir + "/rounds/round-" + round + "/" + agentName + "/review.md";
                String allReviewsPath = (round == 0) ? null
                        : outputDir + "/rounds/round-" + (round - 1) + "/all_reviews.md";

                // At initialization time, previousReviewsContent is null (content doesn't exist yet)
                // It will be populated later by regenerateRoundPrompts() before each round > 0
                TemplateContext ctx = TemplateContext.forRound(round, outputPath, allReviewsPath,
                        null, commit1, commit2, stagedFlag);
                // Use round-5.md template for rounds > 5 (final convergence template is reusable)
                int templateRound = Math.min(round, 5);
                String roundContent = templateLoader.render("round-" + templateRound + ".md", ctx);

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
