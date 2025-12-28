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
 * @param allReviewsPath Path to the combined reviews from the previous round (null for round 0)
 */
public record TemplateContext(
        int roundNumber,
        String allReviewsPath
) {

    /**
     * Creates a context for task.md generation (workspace setup).
     *
     * @return a template context for task.md rendering
     */
    public static TemplateContext forTask() {
        return new TemplateContext(-1, null);
    }

    /**
     * Creates a context for round prompt generation.
     *
     * @param roundNumber    current round number (0-indexed)
     * @param allReviewsPath path to combined reviews from previous round (null for round 0)
     * @return a template context for round prompt rendering
     */
    public static TemplateContext forRound(int roundNumber, String allReviewsPath) {
        return new TemplateContext(roundNumber, allReviewsPath);
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
        if (allReviewsPath != null) {
            model.put("allReviewsPath", allReviewsPath);
        }

        return model;
    }
}
