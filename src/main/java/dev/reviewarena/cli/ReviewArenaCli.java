package dev.reviewarena.cli;

import dev.reviewarena.agent.AgentException;
import dev.reviewarena.agent.AgentExecutor;
import dev.reviewarena.agent.AgentResult;
import dev.reviewarena.agent.ReviewAggregator;
import dev.reviewarena.agent.SynthesisResult;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.config.ConfigLoader;
import dev.reviewarena.config.EnvLoader;
import dev.reviewarena.config.ConfigLoader.CliOverrides;
import dev.reviewarena.git.GitService;
import dev.reviewarena.io.WorkspaceException;
import dev.reviewarena.io.WorkspaceManager;
import lombok.AccessLevel;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Main CLI entry point for the Review Arena.
 * Uses picocli for argument parsing with environment variable fallbacks.
 */
@Command(
    name = "review-arena",
    mixinStandardHelpOptions = true,
    version = "review-arena 1.0",
    description = "Multi-round code review tournament with AI agents",
    sortOptions = false,
    usageHelpAutoWidth = true
)
public class ReviewArenaCli implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ReviewArenaCli.class);

    //==========================================================================
    // Mutually Exclusive: --staged vs positional refs
    //==========================================================================

    @ArgGroup(exclusive = true, multiplicity = "1")
    @Getter(AccessLevel.PACKAGE)
    private ReviewTarget reviewTarget;

    static class ReviewTarget {
        @ArgGroup(exclusive = false)
        CommitRefs commitRefs;

        @Option(names = "--staged",
                description = "Review staged changes instead of commits")
        boolean staged;
    }

    static class CommitRefs {
        @Parameters(index = "0",
                    paramLabel = "<ref1>",
                    converter = CommitHashConverter.class,
                    description = "Git commit hash to review")
        String ref1;

        @Parameters(index = "1",
                    paramLabel = "[ref2]",
                    arity = "0..1",
                    converter = CommitHashConverter.class,
                    description = "End commit hash for range comparison")
        String ref2;
    }

    //==========================================================================
    // Configuration Options
    //==========================================================================

    @Option(names = {"-c", "--config"},
            paramLabel = "<file>",
            description = "Path to config file (default: ${DEFAULT-VALUE})",
            defaultValue = "${REVIEW_ARENA_CONFIG:-arena.yaml}")
    @Getter(AccessLevel.PACKAGE)
    private Path configFile;

    @Option(names = {"-r", "--rounds"},
            paramLabel = "<n>",
            description = "Maximum cross-pollination rounds (default: ${DEFAULT-VALUE})",
            defaultValue = "${REVIEW_ARENA_MAX_ROUNDS:-5}")
    @Getter(AccessLevel.PACKAGE)
    private int maxRounds;

    @Option(names = {"-o", "--output"},
            paramLabel = "<dir>",
            description = "Output directory (default: ${DEFAULT-VALUE})",
            defaultValue = "${REVIEW_ARENA_OUTPUT_DIR:-.arena}")
    @Getter(AccessLevel.PACKAGE)
    private Path outputDir;

    //==========================================================================
    // Execution Mode (mutually exclusive)
    //==========================================================================

    @ArgGroup(exclusive = true)
    @Getter(AccessLevel.PACKAGE)
    private ExecutionMode executionMode;

    static class ExecutionMode {
        @Option(names = "--parallel",
                description = "Force parallel agent execution")
        boolean parallel;

        @Option(names = "--sequential",
                description = "Force sequential agent execution")
        boolean sequential;
    }

    @Option(names = "--max-concurrent",
            paramLabel = "<n>",
            description = "Limit concurrent agents (0=unlimited, 1=sequential)",
            defaultValue = "${REVIEW_ARENA_MAX_CONCURRENT:-0}")
    @Getter(AccessLevel.PACKAGE)
    private int maxConcurrent;

    //==========================================================================
    // Other Options
    //==========================================================================

    @Option(names = "--dry-run",
            description = "Show what would happen without running agents")
    @Getter(AccessLevel.PACKAGE)
    private boolean dryRun;

    @Option(names = {"-q", "--quiet"},
            description = "Suppress agent output (don't stream to console)")
    @Getter(AccessLevel.PACKAGE)
    private boolean quiet;

    //==========================================================================
    // Service factories (package-private for testing)
    //==========================================================================

    private Supplier<GitService> gitServiceFactory = GitService::new;
    private ConfigLoader configLoader = new ConfigLoader();
    private java.util.function.BiFunction<Path, ArenaConfig, WorkspaceManager> workspaceManagerFactory =
            WorkspaceManager::new;

    /**
     * Sets a custom GitService factory. Package-private for testing.
     */
    void setGitServiceFactory(Supplier<GitService> factory) {
        this.gitServiceFactory = factory;
    }

    /**
     * Sets a custom ConfigLoader. Package-private for testing.
     */
    void setConfigLoader(ConfigLoader loader) {
        this.configLoader = loader;
    }

    /**
     * Sets a custom WorkspaceManager factory. Package-private for testing.
     */
    void setWorkspaceManagerFactory(java.util.function.BiFunction<Path, ArenaConfig, WorkspaceManager> factory) {
        this.workspaceManagerFactory = factory;
    }

    //==========================================================================
    // Main Entry Point
    //==========================================================================

    public static void main(String[] args) {
        // Load .env file before anything else (API keys, etc.)
        EnvLoader.initialize();

        int exitCode = new CommandLine(new ReviewArenaCli())
            .setExecutionExceptionHandler(new ExceptionHandler())
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // Extract values from ArgGroups for easier access
        boolean staged = reviewTarget != null && reviewTarget.staged;
        String ref1 = (reviewTarget != null && reviewTarget.commitRefs != null)
                      ? reviewTarget.commitRefs.ref1 : null;
        String ref2 = (reviewTarget != null && reviewTarget.commitRefs != null)
                      ? reviewTarget.commitRefs.ref2 : null;

        // Resolve execution mode
        int effectiveConcurrency = resolveExecutionMode();

        // Load configuration with CLI overrides
        CliOverrides overrides = buildCliOverrides(effectiveConcurrency);
        ArenaConfig config = configLoader.load(configFile, overrides);

        // Handle dry-run mode (skip git validation)
        if (dryRun) {
            printDryRunSummary(staged, ref1, ref2, config);
            return 0;
        }

        // Validate git repository and commits
        try (GitService gitService = gitServiceFactory.get()) {
            if (ref1 != null) {
                gitService.validateCommitExists(ref1);
            }
            if (ref2 != null) {
                gitService.validateCommitExists(ref2);
            }
            if (ref1 != null && ref2 != null) {
                gitService.validateAncestry(ref1, ref2);
            }
        }

        // Build review target string for display
        String reviewTargetStr = buildReviewTargetString(staged, ref1, ref2);

        // Initialize workspace with commit information for templates
        String commit1 = staged ? "" : (ref1 != null ? ref1 : "");
        String commit2 = staged ? "" : (ref2 != null ? ref2 : "");
        String stagedFlagValue = staged ? "--staged" : "";

        Path projectRoot = Path.of("").toAbsolutePath();
        WorkspaceManager workspaceManager = workspaceManagerFactory.apply(projectRoot, config);
        Path arenaDir = workspaceManager.initialize(commit1, commit2, stagedFlagValue);

        log.info("Workspace initialized: {}", arenaDir);
        log.info("Review target: {}", reviewTargetStr);

        // Create executor and aggregator
        AgentExecutor executor = new AgentExecutor(config, workspaceManager);
        ReviewAggregator aggregator = new ReviewAggregator(workspaceManager);

        // === ROUND 0 ===
        Map<String, AgentResult> round0Results = executor.executeRound(0);

        // Check minimum agents threshold for Round 0
        long successCount = round0Results.values().stream()
            .filter(AgentResult::isSuccess)
            .count();

        if (successCount < config.minAgents()) {
            log.error("[THRESHOLD] Only {} agents succeeded in Round 0, minimum {} required. Aborting.",
                successCount, config.minAgents());
            return 4;
        }

        // Aggregate Round 0 reviews
        Path allReviews = aggregator.aggregateRound(0, round0Results);
        log.info("Round 0 complete: {} agents produced reviews, aggregated to {}",
            successCount, projectRoot.relativize(allReviews));

        // Track all enabled agents - agents participate in every round even if they failed previously
        Set<String> allAgents = new HashSet<>(round0Results.keySet());
        int lastCompletedRound = 0;
        Map<String, AgentResult> lastRoundResults = round0Results;

        // === CROSS-POLLINATION ROUNDS (1 through maxRounds) ===
        for (int round = 1; round <= config.maxRounds(); round++) {
            // Regenerate prompts with embedded previous reviews content
            // This is necessary because some AI agents (e.g., Gemini CLI) cannot read files
            // in .gitignored directories, so we embed the content directly in the prompt
            workspaceManager.regenerateRoundPrompts(round, commit1, commit2, stagedFlagValue);

            // Execute round with all agents (agents participate even if they failed in previous rounds)
            Map<String, AgentResult> roundResults = executor.executeRound(round, allAgents);

            // Handle round failure (all agents timed out, crashed, or were filtered)
            if (roundResults.isEmpty()) {
                log.error("[ROUND] Round {} produced no results. " +
                    "This may indicate all agents timed out or crashed.", round);
                return 4;
            }

            // Count successful agents this round
            long successfulThisRound = roundResults.values().stream()
                .filter(AgentResult::isSuccess)
                .count();

            // Check minimum threshold based on successful agents in this round
            if (successfulThisRound < config.minAgents()) {
                log.error("[THRESHOLD] Only {} agents succeeded in round {}, minimum {} required. " +
                    "Aborting tournament.", successfulThisRound, round, config.minAgents());
                return 4;
            }

            // Aggregate this round's reviews (only successful ones are included)
            Path roundAllReviews = aggregator.aggregateRound(round, roundResults);
            log.info("Round {} complete: {} agents succeeded, aggregated to {}",
                round, successfulThisRound, workspaceManager.relativize(roundAllReviews));

            lastCompletedRound = round;
            lastRoundResults = roundResults;
        }

        // === TOURNAMENT COMPLETE - START SYNTHESIS ===
        Path finalAllReviews = workspaceManager.getRoundDir(lastCompletedRound).resolve("all_reviews.md");
        log.info("Cross-pollination complete! Final reviews: {}", workspaceManager.relativize(finalAllReviews));

        // Get agents that succeeded in the final round (their reviews are in all_reviews.md)
        Set<String> finalRoundSuccesses = AgentExecutor.getSuccessfulAgents(lastRoundResults);

        // Validate and get synthesizer agent (Claude required per spec)
        String synthesizerAgent;
        try {
            synthesizerAgent = executor.getSynthesizerAgent();
            log.info("[SYNTHESIS] Using synthesizer: {}", synthesizerAgent);
        } catch (AgentException e) {
            log.error("[SYNTHESIS] {}", e.getMessage());
            return 4;
        }

        // Generate synthesis prompt (participatingAgents = final round successes)
        Path promptPath;
        try {
            promptPath = workspaceManager.generateSynthesisPrompt(lastCompletedRound, finalRoundSuccesses);
            log.info("[SYNTHESIS] Prompt generated: {}", workspaceManager.relativize(promptPath));
        } catch (WorkspaceException e) {
            log.error("[SYNTHESIS] Failed to generate synthesis prompt: {}", e.getMessage());
            return 4;
        }

        // Execute synthesis (returns SynthesisResult, not AgentResult)
        Path championReviewPath = workspaceManager.getChampionReviewPath();
        SynthesisResult synthesisResult = executor.executeSynthesis(synthesizerAgent, promptPath, championReviewPath);

        if (!synthesisResult.success()) {
            log.error("[SYNTHESIS] Synthesis failed: {}", synthesisResult.failureReason());
            return 4;
        }

        log.info("Tournament complete! Champion review: {}", workspaceManager.relativize(championReviewPath));

        return 0;
    }

    private String buildReviewTargetString(boolean staged, String ref1, String ref2) {
        if (staged) {
            return "--staged";
        } else if (ref2 != null) {
            return ref1 + ".." + ref2;
        } else {
            return ref1;
        }
    }

    private CliOverrides buildCliOverrides(int effectiveConcurrency) {
        // Only override if CLI value differs from picocli default
        // These values must match the defaultValue in @Option annotations above
        Integer roundsOverride = maxRounds != 5 ? maxRounds : null;
        Integer concurrentOverride = effectiveConcurrency != 0 ? effectiveConcurrency : null;
        // --quiet flag overrides show-agent-output to false
        Boolean showAgentOutputOverride = quiet ? Boolean.FALSE : null;
        Path outputOverride = !outputDir.equals(Path.of(".arena")) ? outputDir : null;

        return new CliOverrides(roundsOverride, concurrentOverride, showAgentOutputOverride, outputOverride);
    }

    private int resolveExecutionMode() {
        if (executionMode != null) {
            if (executionMode.sequential) return 1;
            if (executionMode.parallel) return 0;
        }
        return maxConcurrent;
    }

    private void printDryRunSummary(boolean staged, String ref1, String ref2, ArenaConfig config) {
        log.info("Dry run - would execute:");
        log.info("  Review target: {}", staged ? "--staged" : ref1 + (ref2 != null ? ".." + ref2 : ""));
        log.info("  Config file: {}", configFile);
        log.info("Effective configuration:");
        log.info("  Output directory: {}", config.outputDir());
        log.info("  Cross-pollination rounds: {}", config.maxRounds());
        log.info("  Total rounds: {} (Round 0 + {} cross-pollination)",
            config.maxRounds() + 1, config.maxRounds());
        log.info("  Concurrency: {}", config.maxConcurrent() == 0 ? "unlimited" : config.maxConcurrent());
        log.info("  Show agent output: {}", config.showAgentOutput());
        log.info("  Agent timeout: {}ms", config.agentTimeoutMs());
        log.info("  Round timeout: {}ms", config.roundTimeoutMs());
        log.info("  Grace period: {}ms", config.gracePeriodMs());
        log.info("  Minimum agents: {}", config.minAgents());
        log.info("Agents ({} configured):", config.agents().size());
        config.agents().forEach((name, agent) -> {
            String status = agent.enabled() ? "enabled" : "disabled";
            log.info("  - {} ({}): {}", name, status, String.join(" ", agent.command()));
        });
        log.info("Tournament flow:");
        log.info("  1. Round 0: Independent reviews (all agents)");
        for (int i = 1; i <= config.maxRounds(); i++) {
            log.info("  {}. Round {}: Cross-pollination (all agents)", i + 1, i);
        }
        log.info("  {}. Final synthesis: Champion review (synthesis)", config.maxRounds() + 2);
        log.info("Synthesis agent: synthesis (required)");
    }
}
