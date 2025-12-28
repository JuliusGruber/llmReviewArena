# CLAUDE.md

## Shell Environment
- Running on Windows with bash shell (Git Bash/WSL)
- Use Unix-style paths and commands, not Windows CMD syntax
- Avoid `cd /d` - use plain `cd` or absolute paths instead
- Prefer using absolute paths directly rather than changing directories

## Testing
- When asked to "run tests", run ALL tests: `mvn verify -Dsurefire.includes="**/*Test*,**/*IT*"`
- This ensures both unit tests (`*Test.java`) and integration tests (`*IT.java`) are executed
