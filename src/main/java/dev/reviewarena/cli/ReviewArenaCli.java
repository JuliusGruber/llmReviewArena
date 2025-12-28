package dev.reviewarena.cli;

import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

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
        // Stub implementation - returns 0
        // Full implementation will be added in subsequent issues
        return 0;
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
