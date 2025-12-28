package dev.reviewarena.git;

/**
 * Exception thrown when git validation fails.
 * Contains an exit code that maps to CLI exit status.
 */
public class GitValidationException extends RuntimeException {

    private final int exitCode;

    public GitValidationException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public GitValidationException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}
