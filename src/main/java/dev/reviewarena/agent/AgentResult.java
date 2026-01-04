package dev.reviewarena.agent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable result of a single agent execution.
 */
public record AgentResult(
    String agentName,
    int round,
    Status status,
    int exitCode,
    long durationMs,
    Path outputFile,
    String failureReason
) {
    public enum Status {
        SUCCESS,
        FAILED,
        TIMEOUT,
        INVALID_OUTPUT
    }

    public AgentResult {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (round < 0) {
            throw new IllegalArgumentException("round must be non-negative");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
        if (status == Status.SUCCESS && outputFile == null) {
            throw new IllegalArgumentException("outputFile required for SUCCESS status");
        }
        if (status != Status.SUCCESS && failureReason == null) {
            throw new IllegalArgumentException("failureReason required for non-SUCCESS status");
        }
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    // Factory methods
    public static AgentResult success(String agentName, int round, int exitCode,
                                       long durationMs, Path outputFile) {
        return new AgentResult(agentName, round, Status.SUCCESS, exitCode,
                               durationMs, outputFile, null);
    }

    public static AgentResult failed(String agentName, int round, int exitCode,
                                      long durationMs, String reason) {
        return new AgentResult(agentName, round, Status.FAILED, exitCode,
                               durationMs, null, reason);
    }

    public static AgentResult timeout(String agentName, int round, long durationMs) {
        return new AgentResult(agentName, round, Status.TIMEOUT, -1,
                               durationMs, null, "Process timed out");
    }

    public static AgentResult invalidOutput(String agentName, int round, int exitCode,
                                             long durationMs, String reason) {
        return new AgentResult(agentName, round, Status.INVALID_OUTPUT, exitCode,
                               durationMs, null, reason);
    }
}
