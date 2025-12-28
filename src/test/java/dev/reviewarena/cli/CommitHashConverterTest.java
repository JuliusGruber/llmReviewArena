package dev.reviewarena.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.TypeConversionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CommitHashConverter}.
 */
class CommitHashConverterTest {

    private CommitHashConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CommitHashConverter();
    }

    // =========================================================================
    // Valid hash tests
    // =========================================================================

    @Test
    void convert_valid7CharHash_returnsLowercase() throws TypeConversionException {
        String result = converter.convert("abc1234");
        assertEquals("abc1234", result);
    }

    @Test
    void convert_valid7CharHashUppercase_returnsLowercase() throws TypeConversionException {
        String result = converter.convert("ABC1234");
        assertEquals("abc1234", result);
    }

    @Test
    void convert_valid40CharHash_returnsLowercase() throws TypeConversionException {
        // Exactly 40 hex characters (full SHA-1 hash)
        String input = "1234567890ABCDEF1234567890ABCDEF12345678";
        String expected = "1234567890abcdef1234567890abcdef12345678";
        assertEquals(40, input.length());
        String result = converter.convert(input);
        assertEquals(expected, result);
    }

    @Test
    void convert_valid40CharHashLowercase_returnsSame() throws TypeConversionException {
        // Exactly 40 hex characters (full SHA-1 hash)
        String input = "1234567890abcdef1234567890abcdef12345678";
        assertEquals(40, input.length());
        String result = converter.convert(input);
        assertEquals(input, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "abcdef0",       // 7 chars
        "abcdef01",      // 8 chars
        "0123456789",    // 10 chars - all digits
        "abcdefabcdef",  // 12 chars - all letters
        "1234567890123456789012345678901234567890"  // 40 chars
    })
    void convert_validHashLengths_succeeds(String hash) throws TypeConversionException {
        assertDoesNotThrow(() -> converter.convert(hash));
    }

    @Test
    void convert_mixedCaseHash_normalizesToLowercase() throws TypeConversionException {
        String result = converter.convert("AbCdEf1");
        assertEquals("abcdef1", result);
    }

    // =========================================================================
    // Invalid hash tests - too short
    // =========================================================================

    @Test
    void convert_6CharHash_throwsWithClearMessage() {
        TypeConversionException ex = assertThrows(
            TypeConversionException.class,
            () -> converter.convert("abc123")
        );
        assertTrue(ex.getMessage().contains("abc123"));
        assertTrue(ex.getMessage().contains("7-40 hex characters"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ab", "abc", "abcd", "abcde", "abcdef"})
    void convert_tooShortHash_throws(String hash) {
        assertThrows(TypeConversionException.class, () -> converter.convert(hash));
    }

    // =========================================================================
    // Invalid hash tests - too long
    // =========================================================================

    @Test
    void convert_41CharHash_throws() {
        // Exactly 41 hex characters (one more than valid SHA-1)
        String tooLong = "1234567890abcdef1234567890abcdef123456789";
        assertEquals(41, tooLong.length());

        TypeConversionException ex = assertThrows(
            TypeConversionException.class,
            () -> converter.convert(tooLong)
        );
        assertTrue(ex.getMessage().contains("7-40 hex characters"));
    }

    // =========================================================================
    // Invalid hash tests - non-hex characters
    // =========================================================================

    @Test
    void convert_nonHexChars_throwsWithClearMessage() {
        TypeConversionException ex = assertThrows(
            TypeConversionException.class,
            () -> converter.convert("xyz1234")
        );
        assertTrue(ex.getMessage().contains("xyz1234"));
        assertTrue(ex.getMessage().contains("7-40 hex characters"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ghijklm",       // letters beyond f
        "abc123g",       // contains g
        "abc-123",       // contains hyphen
        "abc 123",       // contains space
        "abc_123",       // contains underscore
        "abc.123",       // contains period
        "HEAD~~~",       // git ref syntax, not a hash
        "main^^^"        // git ref syntax, not a hash
    })
    void convert_invalidCharacters_throws(String hash) {
        assertThrows(TypeConversionException.class, () -> converter.convert(hash));
    }

    // =========================================================================
    // Error message verification
    // =========================================================================

    @Test
    void convert_invalidHash_errorMessageContainsInvalidValue() {
        String invalidHash = "notahash";
        TypeConversionException ex = assertThrows(
            TypeConversionException.class,
            () -> converter.convert(invalidHash)
        );
        assertTrue(ex.getMessage().contains(invalidHash),
            "Error message should contain the invalid value");
    }

    @Test
    void convert_invalidHash_errorMessageContainsExpectedFormat() {
        TypeConversionException ex = assertThrows(
            TypeConversionException.class,
            () -> converter.convert("bad")
        );
        assertTrue(ex.getMessage().contains("7-40 hex characters"),
            "Error message should describe expected format");
    }
}
