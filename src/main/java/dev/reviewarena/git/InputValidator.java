package dev.reviewarena.git;

import java.util.regex.Pattern;

/**
 * Validates CLI input arguments.
 * Provides static methods for argument-level validation before git operations.
 *
 * Note: When using picocli, some of these validations are handled at parse time:
 * - Mutual exclusivity: handled by @ArgGroup(exclusive = true)
 * - Hash format: handled by CommitHashConverter
 *
 * This class is useful for programmatic validation and testing.
 */
public final class InputValidator {

    private static final int EXIT_USAGE_ERROR = 2;
    private static final Pattern HASH_PATTERN = Pattern.compile("^[a-fA-F0-9]{7,40}$");

    private InputValidator() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates mutual exclusivity between --staged and commit refs.
     *
     * @param staged whether --staged flag is set
     * @param ref1 first commit reference (may be null)
     * @param ref2 second commit reference (may be null)
     * @throws GitValidationException if both --staged and commits are provided
     */
    public static void validateMutualExclusivity(boolean staged, String ref1, String ref2)
            throws GitValidationException {
        boolean hasCommits = ref1 != null || ref2 != null;
        if (staged && hasCommits) {
            throw new GitValidationException(
                    "Error: --staged and commit refs are mutually exclusive",
                    EXIT_USAGE_ERROR);
        }
    }

    /**
     * Validates that at least one input source is provided.
     *
     * @param staged whether --staged flag is set
     * @param ref1 first commit reference (may be null)
     * @throws GitValidationException if neither --staged nor commits provided
     */
    public static void validateInputPresence(boolean staged, String ref1)
            throws GitValidationException {
        if (!staged && ref1 == null) {
            throw new GitValidationException(
                    "Error: Provide commit ref(s) or --staged",
                    EXIT_USAGE_ERROR);
        }
    }

    /**
     * Validates that a commit hash has valid format (7-40 hexadecimal characters).
     *
     * @param ref the commit reference to validate (may be null)
     * @throws GitValidationException if the hash format is invalid
     */
    public static void validateHashFormat(String ref) throws GitValidationException {
        if (ref == null) {
            return;
        }
        if (!isValidHashFormat(ref)) {
            throw new GitValidationException(
                    "Error: Invalid commit hash format: " + ref,
                    EXIT_USAGE_ERROR);
        }
    }

    /**
     * Checks if a string is a valid commit hash format.
     *
     * @param ref the string to check
     * @return true if valid hash format (7-40 hex chars), false otherwise
     */
    public static boolean isValidHashFormat(String ref) {
        if (ref == null) {
            return false;
        }
        return HASH_PATTERN.matcher(ref).matches();
    }
}
