package dev.reviewarena.cli;

import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.config.ConfigLoader;
import dev.reviewarena.config.ConfigLoader.CliOverrides;
import dev.reviewarena.git.GitService;
import dev.reviewarena.io.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
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
    private Path configFile;

    @Option(names = {"-r", "--rounds"},
            paramLabel = "<n>",
            description = "Maximum cross-pollination rounds (default: ${DEFAULT-VALUE})",
            defaultValue = "${REVIEW_ARENA_MAX_ROUNDS:-5}")
    private int maxRounds;

    @Option(names = {"-o", "--output"},
            paramLabel = "<dir>",
            description = "Output directory (default: ${DEFAULT-VALUE})",
            defaultValue = "${REVIEW_ARENA_OUTPUT_DIR:-.arena}")
    private Path outputDir;

    //==========================================================================
    // Execution Mode (mutually exclusive)
    //==========================================================================

    @ArgGroup(exclusive = true)
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
    private int maxConcurrent;

    //==========================================================================
    // Other Options
    //==========================================================================

    @Option(names = "--dry-run",
            description = "Show what would happen without running agents")
    private boolean dryRun;

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

        // Build review target string for display/templates
        String reviewTarget = buildReviewTargetString(staged, ref1, ref2);

        // Initialize workspace
        Path projectRoot = Path.of("").toAbsolutePath();
        WorkspaceManager workspaceManager = workspaceManagerFactory.apply(projectRoot, config);
        Path arenaDir = workspaceManager.initialize(reviewTarget);

        log.info("Workspace initialized: {}", arenaDir);

        // TODO: Start tournament with config
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
        // Only override if CLI value differs from default
        Integer roundsOverride = maxRounds != ArenaConfig.DEFAULT_MAX_ROUNDS ? maxRounds : null;
        Integer concurrentOverride = effectiveConcurrency != ArenaConfig.DEFAULT_MAX_CONCURRENT
                                     ? effectiveConcurrency : null;
        Path outputOverride = !outputDir.equals(Path.of(ArenaConfig.DEFAULT_OUTPUT_DIR))
                              ? outputDir : null;

        return new CliOverrides(roundsOverride, concurrentOverride, outputOverride);
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
        log.info("  Max rounds: {}", config.maxRounds());
        log.info("  Concurrency: {}", config.maxConcurrent() == 0 ? "unlimited" : config.maxConcurrent());
        log.info("  Agent timeout: {}ms", config.agentTimeoutMs());
        log.info("  On timeout: {}", config.onTimeout());
        log.info("Agents ({} configured):", config.agents().size());
        config.agents().forEach((name, agent) -> {
            String status = agent.enabled() ? "enabled" : "disabled";
            log.info("  - {} ({}): {}", name, status, String.join(" ", agent.command()));
        });
    }

    //==========================================================================
    // Accessors for testing
    //==========================================================================

    ReviewTarget getReviewTarget() {
        return reviewTarget;
    }

    Path getConfigFile() {
        return configFile;
    }

    int getMaxRounds() {
        return maxRounds;
    }

    Path getOutputDir() {
        return outputDir;
    }

    ExecutionMode getExecutionMode() {
        return executionMode;
    }

    int getMaxConcurrent() {
        return maxConcurrent;
    }

    boolean isDryRun() {
        return dryRun;
    }
}
