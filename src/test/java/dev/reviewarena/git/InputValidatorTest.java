package dev.reviewarena.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InputValidator}.
 * Pure logic tests - no git operations required.
 */
class InputValidatorTest {

    // =========================================================================
    // Mutual exclusivity tests
    // =========================================================================

    @Test
    void validateMutualExclusivity_stagedAndRef1_throwsWithExitCode2() {
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> InputValidator.validateMutualExclusivity(true, "abc1234", null)
        );

        assertEquals(2, ex.getExitCode());
        assertTrue(ex.getMessage().contains("mutually exclusive"));
    }

    @Test
    void validateMutualExclusivity_stagedAndBothRefs_throwsWithExitCode2() {
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> InputValidator.validateMutualExclusivity(true, "abc1234", "def5678")
        );

        assertEquals(2, ex.getExitCode());
        assertTrue(ex.getMessage().contains("mutually exclusive"));
    }

    @Test
    void validateMutualExclusivity_stagedOnly_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateMutualExclusivity(true, null, null)
        );
    }

    @Test
    void validateMutualExclusivity_commitOnly_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateMutualExclusivity(false, "abc1234", null)
        );
    }

    @Test
    void validateMutualExclusivity_twoCommits_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateMutualExclusivity(false, "abc1234", "def5678")
        );
    }

    @Test
    void validateMutualExclusivity_neither_succeeds() {
        // This is allowed here - presence check is separate
        assertDoesNotThrow(() ->
                InputValidator.validateMutualExclusivity(false, null, null)
        );
    }

    // =========================================================================
    // Input presence tests
    // =========================================================================

    @Test
    void validateInputPresence_neither_throwsWithExitCode2() {
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> InputValidator.validateInputPresence(false, null)
        );

        assertEquals(2, ex.getExitCode());
        assertTrue(ex.getMessage().contains("Provide commit ref(s) or --staged"));
    }

    @Test
    void validateInputPresence_stagedOnly_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateInputPresence(true, null)
        );
    }

    @Test
    void validateInputPresence_commitOnly_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateInputPresence(false, "abc1234")
        );
    }

    @Test
    void validateInputPresence_stagedAndCommit_succeeds() {
        // Mutual exclusivity is checked separately
        assertDoesNotThrow(() ->
                InputValidator.validateInputPresence(true, "abc1234")
        );
    }

    // =========================================================================
    // Hash format validation tests
    // =========================================================================

    @Test
    void validateHashFormat_null_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateHashFormat(null)
        );
    }

    @Test
    void validateHashFormat_valid7Char_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateHashFormat("abc1234")
        );
    }

    @Test
    void validateHashFormat_valid40Char_succeeds() {
        assertDoesNotThrow(() ->
                InputValidator.validateHashFormat("1234567890abcdef1234567890abcdef12345678")
        );
    }

    @Test
    void validateHashFormat_tooShort_throwsWithExitCode2() {
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> InputValidator.validateHashFormat("abc123")
        );

        assertEquals(2, ex.getExitCode());
        assertTrue(ex.getMessage().contains("Invalid commit hash format"));
        assertTrue(ex.getMessage().contains("abc123"));
    }

    @Test
    void validateHashFormat_tooLong_throwsWithExitCode2() {
        String tooLong = "1234567890abcdef1234567890abcdef123456789"; // 41 chars
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> InputValidator.validateHashFormat(tooLong)
        );

        assertEquals(2, ex.getExitCode());
        assertTrue(ex.getMessage().contains("Invalid commit hash format"));
    }

    @Test
    void validateHashFormat_nonHexChars_throwsWithExitCode2() {
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> InputValidator.validateHashFormat("xyz1234")
        );

        assertEquals(2, ex.getExitCode());
        assertTrue(ex.getMessage().contains("Invalid commit hash format"));
        assertTrue(ex.getMessage().contains("xyz1234"));
    }

    // =========================================================================
    // isValidHashFormat tests
    // =========================================================================

    @Test
    void isValidHashFormat_null_returnsFalse() {
        assertFalse(InputValidator.isValidHashFormat(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdef0",       // 7 chars
            "abcdef01",      // 8 chars
            "0123456789",    // 10 chars - all digits
            "abcdefabcdef",  // 12 chars - all letters
            "1234567890123456789012345678901234567890"  // 40 chars
    })
    void isValidHashFormat_validHashes_returnsTrue(String hash) {
        assertTrue(InputValidator.isValidHashFormat(hash));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",              // empty
            "a",             // too short
            "abcdef",        // 6 chars - too short
            "ghijklm",       // non-hex letters
            "abc-123",       // contains hyphen
            "abc 123",       // contains space
            "1234567890123456789012345678901234567890a" // 41 chars - too long
    })
    void isValidHashFormat_invalidHashes_returnsFalse(String hash) {
        assertFalse(InputValidator.isValidHashFormat(hash));
    }

    @Test
    void isValidHashFormat_mixedCase_returnsTrue() {
        assertTrue(InputValidator.isValidHashFormat("AbCdEf1"));
    }
}
