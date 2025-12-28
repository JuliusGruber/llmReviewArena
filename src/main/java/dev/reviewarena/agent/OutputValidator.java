package dev.reviewarena.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates agent output files after execution.
 */
public class OutputValidator {

    private static final Logger log = LoggerFactory.getLogger(OutputValidator.class);

    private final int maxOutputSizeKb;

    public OutputValidator(int maxOutputSizeKb) {
        if (maxOutputSizeKb <= 0) {
            throw new IllegalArgumentException("maxOutputSizeKb must be positive");
        }
        this.maxOutputSizeKb = maxOutputSizeKb;
    }

    /**
     * Validates that the output file exists and is non-empty.
     *
     * @param outputFile path to the expected output file (review.md)
     * @return validation result
     */
    public ValidationResult validate(Path outputFile) {
        if (!Files.exists(outputFile)) {
            return ValidationResult.invalid("Output file does not exist: " + outputFile);
        }

        if (!Files.isRegularFile(outputFile)) {
            return ValidationResult.invalid("Output path is not a file: " + outputFile);
        }

        try {
            long sizeBytes = Files.size(outputFile);

            if (sizeBytes == 0) {
                return ValidationResult.invalid("Output file is empty: " + outputFile);
            }

            long sizeKb = sizeBytes / 1024;
            if (sizeKb > maxOutputSizeKb) {
                log.warn("Output exceeds size limit ({} KB > {} KB limit): {}",
                    sizeKb, maxOutputSizeKb, outputFile);
                // Warn but still valid per spec - no truncation
            }

            return ValidationResult.valid(outputFile);

        } catch (IOException e) {
            return ValidationResult.invalid("Failed to read output file: " + e.getMessage());
        }
    }

    /**
     * Result of output validation.
     */
    public record ValidationResult(boolean valid, String errorMessage, Path outputFile) {

        public static ValidationResult valid(Path file) {
            return new ValidationResult(true, null, file);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message, null);
        }
    }
}
