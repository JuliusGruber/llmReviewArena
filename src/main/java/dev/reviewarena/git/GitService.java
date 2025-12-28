package dev.reviewarena.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Git operations using JGit.
 * Provides validation for repository state, commit existence, and ancestry.
 */
public class GitService implements Closeable {

    private static final int EXIT_GIT_ERROR = 3;

    private final Repository repository;

    /**
     * Opens the git repository from the current working directory.
     * Searches upward for a .git directory.
     *
     * @throws GitValidationException if not inside a git repository
     */
    public GitService() throws GitValidationException {
        this(new File(System.getProperty("user.dir")));
    }

    /**
     * Opens the git repository containing the specified directory.
     *
     * @param workDir directory to start searching from
     * @throws GitValidationException if not inside a git repository
     */
    public GitService(File workDir) throws GitValidationException {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder()
                    .findGitDir(workDir)
                    .setMustExist(true);
            if (builder.getGitDir() == null) {
                throw new GitValidationException("Error: Not a git repository", EXIT_GIT_ERROR);
            }
            this.repository = builder.build();
        } catch (IOException e) {
            throw new GitValidationException("Error: Not a git repository", EXIT_GIT_ERROR, e);
        } catch (IllegalArgumentException e) {
            throw new GitValidationException("Error: Not a git repository", EXIT_GIT_ERROR, e);
        }
    }

    /**
     * Constructor for testing with an existing repository.
     * Protected to allow subclasses (e.g., mocks) in any package.
     *
     * @param repository JGit repository instance (may be null for mocks)
     */
    protected GitService(Repository repository) {
        this.repository = repository;
    }

    /**
     * Validates that a commit hash exists in the repository.
     * Accepts full (40 char) or abbreviated (7+ char) hashes.
     *
     * @param commitHash the commit hash to validate
     * @throws GitValidationException if the commit does not exist
     */
    public void validateCommitExists(String commitHash) throws GitValidationException {
        ObjectId objectId = resolveCommit(commitHash);
        if (objectId == null) {
            throw new GitValidationException(
                    "Error: Commit " + commitHash + " not found",
                    EXIT_GIT_ERROR);
        }
    }

    /**
     * Validates that ref1 is an ancestor of ref2.
     * Both commits must exist.
     *
     * @param ref1 the potential ancestor commit hash
     * @param ref2 the descendant commit hash
     * @throws GitValidationException if ref1 is not an ancestor of ref2, or if commits don't exist
     */
    public void validateAncestry(String ref1, String ref2) throws GitValidationException {
        ObjectId ancestorId = resolveCommit(ref1);
        ObjectId descendantId = resolveCommit(ref2);

        if (ancestorId == null) {
            throw new GitValidationException(
                    "Error: Commit " + ref1 + " not found",
                    EXIT_GIT_ERROR);
        }
        if (descendantId == null) {
            throw new GitValidationException(
                    "Error: Commit " + ref2 + " not found",
                    EXIT_GIT_ERROR);
        }

        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit ancestorCommit = revWalk.parseCommit(ancestorId);
            RevCommit descendantCommit = revWalk.parseCommit(descendantId);

            if (!revWalk.isMergedInto(ancestorCommit, descendantCommit)) {
                throw new GitValidationException(
                        "Error: " + ref1 + " is not an ancestor of " + ref2,
                        EXIT_GIT_ERROR);
            }
        } catch (IOException e) {
            throw new GitValidationException(
                    "Error: Failed to check ancestry: " + e.getMessage(),
                    EXIT_GIT_ERROR, e);
        }
    }

    /**
     * Resolves a commit hash (full or abbreviated) to an ObjectId.
     * Verifies that the resolved object actually exists in the repository.
     *
     * @param commitHash the commit hash to resolve
     * @return the ObjectId, or null if not found or doesn't exist
     */
    public ObjectId resolveCommit(String commitHash) {
        try {
            ObjectId objectId = repository.resolve(commitHash);
            if (objectId == null) {
                return null;
            }
            // Verify the object actually exists in the repository
            if (!repository.getObjectDatabase().has(objectId)) {
                return null;
            }
            return objectId;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the underlying JGit repository.
     * Package-private for testing.
     */
    Repository getRepository() {
        return repository;
    }

    @Override
    public void close() {
        if (repository != null) {
            repository.close();
        }
    }
}
