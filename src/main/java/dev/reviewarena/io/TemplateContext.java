package dev.reviewarena.io;

import java.util.HashMap;
import java.util.Map;

/**
 * Context containing all placeholder values for template rendering.
 *
 * <p>Use the static factory methods to create contexts for specific use cases:
 * <ul>
 *   <li>{@link #forTask} - for task.md generation during workspace setup</li>
 *   <li>{@link #forRound} - for round prompt generation (one prompt per round, shared by all agents)</li>
 * </ul>
 *
 * @param roundNumber    Current round number (0-indexed), -1 if not applicable
 * @param outputPath     Path where the agent should write its review output
 * @param allReviewsPath Path to the combined reviews from the previous round (null for round 0)
 * @param reviewTarget   Human-readable description of what is being reviewed (for task.md)
 * @param commit1        First commit reference (empty string if using --staged)
 * @param commit2        Second commit reference for ranges (empty string for single commit or --staged)
 * @param stagedFlag     The staged flag value ("--staged" if reviewing staged, empty string otherwise)
 */
public record TemplateContext(
        int roundNumber,
        String outputPath,
        String allReviewsPath,
        String reviewTarget,
        String commit1,
        String commit2,
        String stagedFlag
) {

    /**
     * Creates a context for task.md generation (workspace setup).
     *
     * @param reviewTarget human-readable description of what is being reviewed
     * @return a template context for task.md rendering
     */
    public static TemplateContext forTask(String reviewTarget) {
        return new TemplateContext(-1, null, null, reviewTarget, "", "", "");
    }

    /**
     * Creates a context for round prompt generation.
     *
     * @param roundNumber    current round number (0-indexed)
     * @param outputPath     path where agent should write its review
     * @param allReviewsPath path to combined reviews from previous round (null for round 0)
     * @param commit1        first commit reference (empty string if using --staged)
     * @param commit2        second commit reference for ranges (empty string for single commit or --staged)
     * @param stagedFlag     the staged flag value ("--staged" if reviewing staged, empty string otherwise)
     * @return a template context for round prompt rendering
     */
    public static TemplateContext forRound(int roundNumber, String outputPath, String allReviewsPath,
                                           String commit1, String commit2, String stagedFlag) {
        return new TemplateContext(roundNumber, outputPath, allReviewsPath, null, commit1, commit2, stagedFlag);
    }

    /**
     * Converts this context to a map suitable for Freemarker template processing.
     *
     * <p>Only non-null values are included in the map to allow templates
     * to use Freemarker's missing value handling.
     *
     * @return a map of placeholder names to values
     */
    public Map<String, Object> toDataModel() {
        Map<String, Object> model = new HashMap<>();

        if (roundNumber >= 0) {
            model.put("roundNumber", roundNumber);
        }
        if (outputPath != null) {
            model.put("outputPath", outputPath);
        }
        if (allReviewsPath != null) {
            model.put("allReviewsPath", allReviewsPath);
        }
        if (reviewTarget != null) {
            model.put("reviewTarget", reviewTarget);
        }
        if (commit1 != null) {
            model.put("commit1", commit1);
        }
        if (commit2 != null) {
            model.put("commit2", commit2);
        }
        if (stagedFlag != null) {
            model.put("stagedFlag", stagedFlag);
        }

        return model;
    }
}
