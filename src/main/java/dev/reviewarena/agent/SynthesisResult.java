package dev.reviewarena.agent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of synthesis execution.
 *
 * <p>Separate from {@link AgentResult} because synthesis is not a tournament round.
 * AgentResult requires a non-negative round number, which doesn't apply to synthesis.
 *
 * @param agentName     the synthesizer agent name (always "claude")
 * @param success       whether synthesis completed successfully
 * @param durationMs    execution duration in milliseconds
 * @param outputFile    path to champion_review.md (null if failed)
 * @param failureReason description of failure (null if success)
 */
public record SynthesisResult(
        String agentName,
        boolean success,
        long durationMs,
        Path outputFile,
        String failureReason
) {
    public SynthesisResult {
        Objects.requireNonNull(agentName, "agentName must not be null");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
        if (success && outputFile == null) {
            throw new IllegalArgumentException("outputFile required for successful synthesis");
        }
        if (!success && failureReason == null) {
            throw new IllegalArgumentException("failureReason required for failed synthesis");
        }
    }

    public static SynthesisResult success(String agentName, long durationMs, Path outputFile) {
        return new SynthesisResult(agentName, true, durationMs, outputFile, null);
    }

    public static SynthesisResult failed(String agentName, long durationMs, String reason) {
        return new SynthesisResult(agentName, false, durationMs, null, reason);
    }

    public static SynthesisResult timeout(String agentName, long durationMs) {
        return new SynthesisResult(agentName, false, durationMs, null, "Synthesis timed out");
    }
}
