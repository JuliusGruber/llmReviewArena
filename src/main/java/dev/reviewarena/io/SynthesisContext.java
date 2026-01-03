package dev.reviewarena.io;

import java.util.HashMap;
import java.util.Map;

/**
 * Context for synthesis prompt template rendering.
 *
 * <p>Separate from {@link TemplateContext} because synthesis is not a tournament round.
 * Contains tournament metadata to provide context for the synthesizer agent.
 *
 * @param outputPath             path where synthesizer should write champion_review.md
 * @param allReviewsPath         path to final round's all_reviews.md
 * @param roundCount             total rounds completed (Round 0 + cross-pollination rounds)
 * @param crossPollinationRounds number of cross-pollination rounds (roundCount - 1)
 * @param participatingAgents    comma-separated list of agents that succeeded in final round
 */
public record SynthesisContext(
        String outputPath,
        String allReviewsPath,
        int roundCount,
        int crossPollinationRounds,
        String participatingAgents
) {
    /**
     * Compact constructor with validation.
     */
    public SynthesisContext {
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalArgumentException("outputPath must not be null or blank");
        }
        if (allReviewsPath == null || allReviewsPath.isBlank()) {
            throw new IllegalArgumentException("allReviewsPath must not be null or blank");
        }
        if (roundCount < 1) {
            throw new IllegalArgumentException("roundCount must be at least 1");
        }
        if (crossPollinationRounds < 0) {
            throw new IllegalArgumentException("crossPollinationRounds must be non-negative");
        }
        if (roundCount != crossPollinationRounds + 1) {
            throw new IllegalArgumentException(
                "roundCount must equal crossPollinationRounds + 1, got roundCount="
                + roundCount + ", crossPollinationRounds=" + crossPollinationRounds);
        }
        if (participatingAgents == null || participatingAgents.isBlank()) {
            throw new IllegalArgumentException("participatingAgents must not be null or blank");
        }
    }

    /**
     * Converts this context to a map suitable for FreeMarker template processing.
     *
     * @return a map of placeholder names to values
     */
    public Map<String, Object> toDataModel() {
        Map<String, Object> model = new HashMap<>();
        model.put("outputPath", outputPath);
        model.put("allReviewsPath", allReviewsPath);
        model.put("roundCount", roundCount);
        model.put("crossPollinationRounds", crossPollinationRounds);
        model.put("participatingAgents", participatingAgents);
        return model;
    }
}
