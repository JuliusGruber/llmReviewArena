# Dockerfile for OpenAI Codex CLI
#
# This image packages the Codex CLI for non-interactive execution.
# The Codex CLI (v0.77+) is a native Rust binary distributed via npm.
#
# Usage:
#   docker build -t codex-cli:latest docker/codex/
#   docker run --rm -i -e OPENAI_API_KEY="$OPENAI_API_KEY" \
#     -v "$(pwd)":/workspace codex-cli:latest \
#     --full-auto -o /workspace/output.md - < prompt.md
#
# The image uses `codex exec` as the entrypoint, which:
#   - Reads prompts from stdin when using "-" argument
#   - Writes output to file when using "-o <path>" argument
#   - Runs fully automated with "--full-auto" flag

FROM node:22-slim

# Install Codex CLI globally
# The @openai/codex package now installs the native Rust binary
RUN npm install -g @openai/codex && \
    npm cache clean --force

# Create non-root user for security
RUN useradd -m -s /bin/bash codex

# Switch to non-root user
USER codex

# Set working directory (will be mounted from host)
WORKDIR /workspace

# Use codex exec as entrypoint for non-interactive execution
# Arguments like "--full-auto -o output.md -" are passed at runtime
ENTRYPOINT ["codex", "exec"]

# Default: show help if no arguments provided
CMD ["--help"]
