package dev.reviewarena.agent;

import dev.reviewarena.io.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Aggregates individual agent reviews into a combined all_reviews.md file.
 *
 * <p>After each round completes, creates a combined markdown file containing
 * all agent reviews with clear H1 headings separating each contribution.
 *
 * <p>Format:
 * <pre>
 * # Claude
 *
 * [Full content of claude/review.md]
 *
 * # Codex
 *
 * [Full content of codex/review.md]
 * </pre>
 */
public class ReviewAggregator {

    private static final Logger log = LoggerFactory.getLogger(ReviewAggregator.class);
    private static final String ALL_REVIEWS_FILENAME = "all_reviews.md";

    private final WorkspaceManager workspace;

    public ReviewAggregator(WorkspaceManager workspace) {
        this.workspace = workspace;
    }

    /**
     * Aggregates all successful agent reviews from a round into all_reviews.md.
     *
     * @param round the round number (0-indexed)
     * @param results the agent execution results (only successful agents are included)
     * @return path to the generated all_reviews.md file
     * @throws AgentException if aggregation fails
     */
    public Path aggregateRound(int round, Map<String, AgentResult> results) {
        Path roundDir = workspace.getRoundDir(round);
        Path outputFile = roundDir.resolve(ALL_REVIEWS_FILENAME);

        log.info("Aggregating reviews for round {} to {}", round, outputFile);

        // Filter to successful results and sort alphabetically for determinism
        var successfulResults = results.entrySet().stream()
            .filter(e -> e.getValue().isSuccess())
            .sorted(Map.Entry.comparingByKey())
            .toList();

        if (successfulResults.isEmpty()) {
            log.warn("No successful reviews to aggregate for round {}", round);
            throw new AgentException("No successful reviews to aggregate for round " + round);
        }

        try {
            StringBuilder combined = new StringBuilder();

            for (var entry : successfulResults) {
                String agentName = entry.getKey();
                AgentResult result = entry.getValue();

                // Read the agent's review content
                String reviewContent = Files.readString(result.outputFile(), StandardCharsets.UTF_8);

                // Add with H1 heading (capitalize first letter)
                String displayName = capitalize(agentName);
                combined.append("# ").append(displayName).append("\n\n");
                combined.append(reviewContent.strip());
                combined.append("\n\n");
            }

            Files.writeString(outputFile, combined.toString().strip() + "\n", StandardCharsets.UTF_8);

            log.info("Aggregated {} reviews into {}", successfulResults.size(), outputFile);
            return outputFile;

        } catch (IOException e) {
            throw new AgentException("Failed to aggregate reviews for round " + round + ": " + e.getMessage(), e);
        }
    }

    /**
     * Gets the path to all_reviews.md for a specific round.
     *
     * @param round the round number
     * @return path to all_reviews.md
     */
    public Path getAllReviewsPath(int round) {
        return workspace.getRoundDir(round).resolve(ALL_REVIEWS_FILENAME);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
