package dev.reviewarena.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OutputValidatorTest {

    @TempDir
    Path tempDir;

    private OutputValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OutputValidator(500); // 500 KB limit
    }

    @Test
    void validate_validFile_returnsValid() throws IOException {
        Path file = tempDir.resolve("review.md");
        Files.writeString(file, "# Review\nThis is a valid review.");

        var result = validator.validate(file);

        assertTrue(result.valid());
        assertNull(result.errorMessage());
        assertEquals(file, result.outputFile());
    }

    @Test
    void validate_missingFile_returnsInvalid() {
        Path file = tempDir.resolve("nonexistent.md");

        var result = validator.validate(file);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("does not exist"));
        assertNull(result.outputFile());
    }

    @Test
    void validate_emptyFile_returnsInvalid() throws IOException {
        Path file = tempDir.resolve("empty.md");
        Files.createFile(file);

        var result = validator.validate(file);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("empty"));
    }

    @Test
    void validate_directory_returnsInvalid() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectory(dir);

        var result = validator.validate(dir);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("not a file"));
    }

    @Test
    void validate_largeFile_warnsButReturnsValid() throws IOException {
        Path file = tempDir.resolve("large.md");
        // Create file larger than 500 KB
        byte[] content = new byte[600 * 1024];
        Files.write(file, content);

        var result = validator.validate(file);

        assertTrue(result.valid()); // Still valid, just warns
        assertEquals(file, result.outputFile());
    }

    @Test
    void constructor_rejectsZeroSize() {
        assertThrows(IllegalArgumentException.class, () -> new OutputValidator(0));
    }

    @Test
    void constructor_rejectsNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> new OutputValidator(-1));
    }
}
