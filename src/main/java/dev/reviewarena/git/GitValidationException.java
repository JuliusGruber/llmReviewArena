package dev.reviewarena.git;

import lombok.Getter;

/**
 * Exception thrown when git validation fails.
 * Contains an exit code that maps to CLI exit status.
 */
public class GitValidationException extends RuntimeException {

    @Getter
    private final int exitCode;

    public GitValidationException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public GitValidationException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }
}
