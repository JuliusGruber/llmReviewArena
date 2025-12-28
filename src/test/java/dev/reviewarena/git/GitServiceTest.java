package dev.reviewarena.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GitService}.
 * Uses the actual project repository for realistic testing.
 */
class GitServiceTest {

    private GitService gitService;

    @BeforeEach
    void setUp() throws GitValidationException {
        // Use the actual project repository
        gitService = new GitService();
    }

    @AfterEach
    void tearDown() {
        if (gitService != null) {
            gitService.close();
        }
    }

    // =========================================================================
    // Repository detection tests
    // =========================================================================

    @Test
    void constructor_inGitRepo_succeeds() {
        assertNotNull(gitService.getRepository());
    }

    @Test
    void constructor_notInGitRepo_throwsWithExitCode3(@TempDir Path tempDir) {
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> new GitService(tempDir.toFile())
        );

        assertEquals(3, ex.getExitCode());
        assertTrue(ex.getMessage().contains("Not a git repository"));
    }

    @Test
    void constructor_withWorkDir_findsRepository() throws GitValidationException {
        // The src directory should still find the parent .git
        File srcDir = new File(System.getProperty("user.dir"), "src");
        try (GitService service = new GitService(srcDir)) {
            assertNotNull(service.getRepository());
        }
    }

    // =========================================================================
    // Commit existence tests
    // =========================================================================

    @Test
    void validateCommitExists_validFullHash_succeeds() throws IOException {
        // Get a real commit from HEAD
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        assertNotNull(head, "HEAD should resolve to a commit");

        String fullHash = head.getName();
        assertDoesNotThrow(() -> gitService.validateCommitExists(fullHash));
    }

    @Test
    void validateCommitExists_validAbbreviatedHash_succeeds() throws IOException {
        // Get a real commit from HEAD
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        assertNotNull(head, "HEAD should resolve to a commit");

        // Use first 7 characters (abbreviated hash)
        String abbreviatedHash = head.getName().substring(0, 7);
        assertDoesNotThrow(() -> gitService.validateCommitExists(abbreviatedHash));
    }

    @Test
    void validateCommitExists_nonExistentHash_throwsWithExitCode3() {
        // A valid format hash that doesn't exist
        String nonExistentHash = "0000000000000000000000000000000000000000";

        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> gitService.validateCommitExists(nonExistentHash)
        );

        assertEquals(3, ex.getExitCode());
        assertTrue(ex.getMessage().contains(nonExistentHash));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void validateCommitExists_nonExistentAbbreviatedHash_throwsWithExitCode3() {
        // A valid format abbreviated hash that doesn't exist
        String nonExistentHash = "0000000";

        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> gitService.validateCommitExists(nonExistentHash)
        );

        assertEquals(3, ex.getExitCode());
        assertTrue(ex.getMessage().contains("not found"));
    }

    // =========================================================================
    // Resolve commit tests
    // =========================================================================

    @Test
    void resolveCommit_validHash_returnsObjectId() throws IOException {
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        String hash = head.getName();

        ObjectId resolved = gitService.resolveCommit(hash);
        assertNotNull(resolved);
        assertEquals(head, resolved);
    }

    @Test
    void resolveCommit_abbreviatedHash_returnsObjectId() throws IOException {
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        String abbreviated = head.getName().substring(0, 7);

        ObjectId resolved = gitService.resolveCommit(abbreviated);
        assertNotNull(resolved);
        assertEquals(head, resolved);
    }

    @Test
    void resolveCommit_nonExistentHash_returnsNull() {
        ObjectId resolved = gitService.resolveCommit("0000000000000000000000000000000000000000");
        assertNull(resolved);
    }

    // =========================================================================
    // Ancestry validation tests
    // =========================================================================

    @Test
    void validateAncestry_validAncestor_succeeds() throws IOException {
        // Get HEAD and its parent
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        ObjectId parent = repo.resolve("HEAD~1");

        assertNotNull(head, "HEAD should exist");
        assertNotNull(parent, "HEAD~1 should exist");

        String headHash = head.getName().substring(0, 7);
        String parentHash = parent.getName().substring(0, 7);

        // Parent is ancestor of HEAD
        assertDoesNotThrow(() -> gitService.validateAncestry(parentHash, headHash));
    }

    @Test
    void validateAncestry_notAnAncestor_throwsWithExitCode3() throws IOException {
        // HEAD is not an ancestor of its parent
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        ObjectId parent = repo.resolve("HEAD~1");

        assertNotNull(head, "HEAD should exist");
        assertNotNull(parent, "HEAD~1 should exist");

        String headHash = head.getName().substring(0, 7);
        String parentHash = parent.getName().substring(0, 7);

        // HEAD is NOT an ancestor of parent (reversed order)
        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> gitService.validateAncestry(headHash, parentHash)
        );

        assertEquals(3, ex.getExitCode());
        assertTrue(ex.getMessage().contains("is not an ancestor of"));
    }

    @Test
    void validateAncestry_sameCommit_succeeds() throws IOException {
        // A commit is considered an ancestor of itself
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        String headHash = head.getName().substring(0, 7);

        assertDoesNotThrow(() -> gitService.validateAncestry(headHash, headHash));
    }

    @Test
    void validateAncestry_nonExistentAncestor_throwsWithExitCode3() throws IOException {
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        String headHash = head.getName().substring(0, 7);
        String nonExistentHash = "0000000";

        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> gitService.validateAncestry(nonExistentHash, headHash)
        );

        assertEquals(3, ex.getExitCode());
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void validateAncestry_nonExistentDescendant_throwsWithExitCode3() throws IOException {
        Repository repo = gitService.getRepository();
        ObjectId head = repo.resolve("HEAD");
        String headHash = head.getName().substring(0, 7);
        String nonExistentHash = "0000000";

        GitValidationException ex = assertThrows(
                GitValidationException.class,
                () -> gitService.validateAncestry(headHash, nonExistentHash)
        );

        assertEquals(3, ex.getExitCode());
        assertTrue(ex.getMessage().contains("not found"));
    }

    // =========================================================================
    // Close/resource management tests
    // =========================================================================

    @Test
    void close_canBeCalledMultipleTimes() {
        gitService.close();
        assertDoesNotThrow(() -> gitService.close());
    }
}
