# Startup Validation Implementation Plan

This document outlines the implementation plan for CLI startup validation — the first feature to be built.

## Overview

When the user starts the CLI tool, it must validate:
1. The command is run inside a git repository
2. If commit hash(es) are provided, they exist
3. If two commits are provided, ref1 is an ancestor of ref2
4. Either commit hash(es) OR `--staged` flag is provided (mutually exclusive)

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Git interaction | JGit library | Clean Java API, better error handling, no subprocess overhead |
| Accepted ref types | Commit hashes only | Full (40 char) or abbreviated (7+ char) SHAs. Strict and predictable |
| `--staged` + commits | Mutually exclusive error | Clear contract, exit code 2 |
| Validation order | Repo → Mutual excl → Args → Commits | Most granular, fail-fast |
| Error verbosity | Minimal | Short, direct messages |
| Code location | `GitService` in `dev.reviewarena.git` | Reusable for future git operations |
| Two-commit range | Validate ancestry | ref1 must be reachable from ref2 |

## Validation Flow

```
┌─────────────────────────────────────────────────────────────┐
│                      CLI Startup                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                 ┌────────────────────────┐
                 │  Is git repository?    │
                 └────────────────────────┘
                      │            │
                     YES          NO
                      │            └──────► Exit 3: "Error: Not a git repository"
                      ▼
                 ┌────────────────────────┐
                 │ --staged AND commits?  │
                 └────────────────────────┘
                      │            │
                     NO           YES
                      │            └──────► Exit 2: "Error: --staged and commit refs are mutually exclusive"
                      ▼
                 ┌────────────────────────┐
                 │ --staged OR commits?   │
                 └────────────────────────┘
                      │            │
                    YES           NO
                      │            └──────► Exit 2: "Error: Provide commit ref(s) or --staged"
                      ▼
                 ┌────────────────────────┐
                 │ If commits provided:   │
                 │ Do they exist?         │
                 └────────────────────────┘
                      │            │
                    YES           NO
                      │            └──────► Exit 3: "Error: Commit <hash> not found"
                      ▼
                 ┌────────────────────────┐
                 │ If two commits:        │
                 │ Is ref1 ancestor of    │
                 │ ref2?                  │
                 └────────────────────────┘
                      │            │
                    YES           NO
                      │            └──────► Exit 3: "Error: <ref1> is not an ancestor of <ref2>"
                      ▼
                 ┌────────────────────────┐
                 │    Validation passed   │
                 │    Continue startup    │
                 └────────────────────────┘
```

## Exit Codes

| Code | Meaning | When Used |
|------|---------|-----------|
| 2 | Usage error | Missing args, mutual exclusivity violation |
| 3 | Git error | Not a repo, commit not found, invalid range |

## Implementation Steps

### Step 1: Add JGit Dependency

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>6.8.0.202311291450-r</version>
</dependency>
```

### Step 2: Create GitService Class

**Location:** `src/main/java/dev/reviewarena/git/GitService.java`

```java
package dev.reviewarena.git;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevCommit;

public class GitService {

    private final Repository repository;

    // Constructor attempts to open repo, throws if not in git repo
    public GitService() throws GitValidationException { ... }

    // Validates a commit hash exists (full or abbreviated)
    public void validateCommitExists(String commitHash) throws GitValidationException { ... }

    // Validates ref1 is an ancestor of ref2
    public void validateAncestry(String ref1, String ref2) throws GitValidationException { ... }

    // Check if string looks like a valid commit hash format (7-40 hex chars)
    public static boolean isValidHashFormat(String ref) { ... }
}
```

### Step 3: Create GitValidationException

**Location:** `src/main/java/dev/reviewarena/git/GitValidationException.java`

```java
package dev.reviewarena.git;

public class GitValidationException extends Exception {

    private final int exitCode;

    public GitValidationException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}
```

### Step 4: Create InputValidator Class

**Location:** `src/main/java/dev/reviewarena/git/InputValidator.java`

Handles argument-level validation (mutual exclusivity, presence checks) before git operations:

```java
package dev.reviewarena.git;

public class InputValidator {

    // Throws if --staged AND commits both provided
    public static void validateMutualExclusivity(boolean staged, String ref1, String ref2)
        throws GitValidationException { ... }

    // Throws if neither --staged NOR commits provided
    public static void validateInputPresence(boolean staged, String ref1)
        throws GitValidationException { ... }

    // Validates hash format (7-40 hex characters)
    public static void validateHashFormat(String ref)
        throws GitValidationException { ... }
}
```

### Step 5: Integrate into ReviewArenaCli

**Location:** `src/main/java/dev/reviewarena/cli/ReviewArenaCli.java`

In the `run()` or `call()` method (picocli entry point):

```java
@Override
public Integer call() {
    try {
        // 1. Open repository (validates we're in a git repo)
        GitService gitService = new GitService();

        // 2. Check mutual exclusivity
        InputValidator.validateMutualExclusivity(staged, ref1, ref2);

        // 3. Check at least one input provided
        InputValidator.validateInputPresence(staged, ref1);

        // 4. If commits provided, validate they exist
        if (ref1 != null) {
            InputValidator.validateHashFormat(ref1);
            gitService.validateCommitExists(ref1);
        }
        if (ref2 != null) {
            InputValidator.validateHashFormat(ref2);
            gitService.validateCommitExists(ref2);
        }

        // 5. If two commits, validate ancestry
        if (ref1 != null && ref2 != null) {
            gitService.validateAncestry(ref1, ref2);
        }

        // Validation passed, continue with tournament...

    } catch (GitValidationException e) {
        System.err.println(e.getMessage());
        return e.getExitCode();
    }

    return 0;
}
```

## Error Messages

| Scenario | Message |
|----------|---------|
| Not a git repo | `Error: Not a git repository` |
| --staged with commits | `Error: --staged and commit refs are mutually exclusive` |
| No input provided | `Error: Provide commit ref(s) or --staged` |
| Invalid hash format | `Error: Invalid commit hash format: <ref>` |
| Commit not found | `Error: Commit <hash> not found` |
| Invalid ancestry | `Error: <ref1> is not an ancestor of <ref2>` |

## Testing Strategy

### Unit Tests

1. **GitService tests** (with test repository):
   - `testIsInsideGitRepo_success`
   - `testIsInsideGitRepo_notARepo`
   - `testValidateCommitExists_fullHash`
   - `testValidateCommitExists_abbreviatedHash`
   - `testValidateCommitExists_notFound`
   - `testValidateAncestry_valid`
   - `testValidateAncestry_invalid`

2. **InputValidator tests** (pure logic, no git):
   - `testMutualExclusivity_stagedAndCommit_throws`
   - `testMutualExclusivity_stagedOnly_passes`
   - `testMutualExclusivity_commitOnly_passes`
   - `testInputPresence_neither_throws`
   - `testInputPresence_staged_passes`
   - `testInputPresence_commit_passes`
   - `testHashFormat_valid40Char`
   - `testHashFormat_valid7Char`
   - `testHashFormat_tooShort`
   - `testHashFormat_invalidChars`

### Integration Tests

1. Run CLI in a non-git directory → expect exit 3
2. Run CLI with no arguments → expect exit 2
3. Run CLI with `--staged` and commit → expect exit 2
4. Run CLI with non-existent commit → expect exit 3
5. Run CLI with valid commit → expect success (exit 0 or continue)
6. Run CLI with invalid range → expect exit 3

## File Structure After Implementation

```
src/main/java/dev/reviewarena/
├── cli/
│   └── ReviewArenaCli.java      (modified - calls GitService)
└── git/
    ├── GitService.java          (new)
    ├── GitValidationException.java (new)
    └── InputValidator.java      (new)

src/test/java/dev/reviewarena/
└── git/
    ├── GitServiceTest.java      (new)
    └── InputValidatorTest.java  (new)
```

## Dependencies

Add to `pom.xml`:

```xml
<!-- JGit for git operations -->
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>6.8.0.202311291450-r</version>
</dependency>
```

## Open Questions (Resolved)

All questions have been resolved through discussion:

- ~~Git interaction approach~~ → JGit
- ~~Ref types~~ → Commit hashes only
- ~~--staged mutual exclusivity~~ → Error
- ~~Validation order~~ → Repo → Mutual excl → Args → Commits
- ~~Error verbosity~~ → Minimal
- ~~Code location~~ → GitService in git package
- ~~Ancestry validation~~ → Required
