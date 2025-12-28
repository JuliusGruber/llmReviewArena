package dev.reviewarena.cli;

import dev.reviewarena.git.GitService;
import dev.reviewarena.git.GitValidationException;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Mock GitService for testing CLI without requiring a real git repository.
 * All validation methods pass without actually checking git.
 */
class MockGitService extends GitService {

    MockGitService() {
        super((org.eclipse.jgit.lib.Repository) null);
    }

    @Override
    public void validateCommitExists(String commitHash) throws GitValidationException {
        // Accept all commits in tests
    }

    @Override
    public void validateAncestry(String ref1, String ref2) throws GitValidationException {
        // Accept all ancestry checks in tests
    }

    @Override
    public ObjectId resolveCommit(String commitHash) {
        // Return a dummy ObjectId
        return ObjectId.zeroId();
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
