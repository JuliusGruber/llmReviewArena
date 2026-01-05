# CLAUDE.md

## GitHub Issues
- When asked about an "issue" or to review an issue, always use this project's GitHub repo (llmReviewArena), not the Anthropic repo
- Always run `git remote -v` before using `gh` CLI commands to get the correct repository owner/name
- Never assume GitHub usernames from local paths (e.g., `C:\Users\juliu` does not mean the GitHub user is "juliu" or similar)

## Required Reading
- Always read `specs/spec.md` before implementing features - this is the main project specification
- Always read `specs/flow-diagram.md` to understand the application flow

## Shell Environment
- Running on Windows with bash shell (Git Bash/WSL)
- Use Unix-style paths and commands, not Windows CMD syntax
- Avoid `cd /d` - use plain `cd` or absolute paths instead
- Prefer using absolute paths directly rather than changing directories

## Testing
- When asked to "run tests", run ALL tests: `mvn verify -Dsurefire.includes="**/*Test*,**/*IT*"`
- This ensures both unit tests (`*Test.java`) and integration tests (`*IT.java`) are executed

## Logging
- Use SLF4J + Logback for all logging (see `specs/implementation-decisions.md` for details)

## Context7 Usage
- Use Context7 MCP tools to look up current library documentation before implementing features
- Always check Context7 when adding new dependencies or using unfamiliar APIs
- Prefer Context7 docs over training knowledge for version-specific syntax and best practices

## Implementation Plan Review
Before approving any implementation plan:

1. **Verify assumptions by reading code** - If a plan claims "X cannot be used because Y", read X's source code to confirm. Cite the specific lines that prove the constraint exists.

2. **Search for reuse before creating** - Before the plan creates new process, I/O, or utility code, search the codebase for existing solutions. Prefer wrapping or adapting existing code over duplication.
