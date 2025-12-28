package dev.reviewarena.io;

/**
 * Exception thrown when template loading or processing fails.
 */
public class TemplateException extends RuntimeException {

    public TemplateException(String message) {
        super(message);
    }

    public TemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
