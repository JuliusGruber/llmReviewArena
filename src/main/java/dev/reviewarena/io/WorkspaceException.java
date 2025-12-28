package dev.reviewarena.io;

/**
 * Exception thrown when workspace operations fail.
 *
 * <p>This exception indicates failures in:
 * <ul>
 *   <li>Creating or clearing the .arena/ directory</li>
 *   <li>Loading or generating template files</li>
 *   <li>File I/O operations during workspace setup</li>
 * </ul>
 */
public class WorkspaceException extends RuntimeException {

    /**
     * Creates a WorkspaceException with the given message.
     *
     * @param message the error message
     */
    public WorkspaceException(String message) {
        super(message);
    }

    /**
     * Creates a WorkspaceException with the given message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
