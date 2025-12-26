# CLI Specification

## Basic Usage

```bash
review-arena [options] <ref1> [ref2]
```

This triggers the multi-round code review tournament.

## Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `ref1` | **Yes** | Git reference (commit hash, branch, tag, or HEAD~N) |
| `ref2` | No | End reference for range comparison |

### Behavior

- If only `ref1` is provided: Review the changes introduced in that single commit/ref
- If both `ref1` and `ref2` are provided: Review the diff between the two references
- With `--staged`: Review currently staged changes (ignores ref arguments)

## Options

| Option | Short | Description |
|--------|-------|-------------|
| `--help` | `-h` | Show help and usage information |
| `--version` | `-v` | Show version number |
| `--config <file>` | `-c` | Path to config file (default: `arena.yaml`) |
| `--agents <list>` | `-a` | Comma-separated list of agents to use (overrides config) |
| `--rounds <n>` | `-r` | Maximum rounds (overrides config) |
| `--output <dir>` | `-o` | Output directory (default: `.arena`) |
| `--parallel` | | Force parallel agent execution |
| `--sequential` | | Force sequential agent execution |
| `--verbose` | | Verbose output with detailed progress |
| `--quiet` | `-q` | Minimal output (for CI/scripts) |
| `--dry-run` | | Preview what would happen without executing |
| `--staged` | | Review staged changes instead of commits |
| `--resume` | | Resume an interrupted review session |

## Examples

```bash
# Review a single commit
review-arena abc1234

# Review changes between two commits
review-arena abc1234 def5678

# Review using branch names
review-arena main feature-branch

# Review last 3 commits with specific agents
review-arena HEAD~3 HEAD --agents claude,gemini

# Review staged changes
review-arena --staged

# Custom output directory with verbose logging
review-arena abc1234 --output ./reviews --verbose

# CI/CD usage (quiet mode, check exit code)
review-arena abc1234 --quiet && echo "Review complete"

# Preview without executing
review-arena abc1234 --dry-run

# Resume an interrupted review
review-arena --resume
```

## Exit Codes

For CI/CD integration and scripting:

| Code | Meaning |
|------|---------|
| 0 | Success - review completed |
| 1 | General error |
| 2 | Invalid arguments / usage error |
| 3 | Git error (no repository, invalid reference) |
| 4 | Agent error (CLI not found, execution failed) |
| 5 | Configuration error (invalid config file) |

## Environment Variables

For automation and default configuration:

| Variable | Description |
|----------|-------------|
| `REVIEW_ARENA_CONFIG` | Default config file path |
| `REVIEW_ARENA_AGENTS` | Default agents (comma-separated) |
| `REVIEW_ARENA_OUTPUT_DIR` | Default output directory |
| `REVIEW_ARENA_MAX_ROUNDS` | Default maximum rounds |

## Progress Output

When running (non-quiet mode), display progress:

```
review-arena v1.0.0
Reviewing: abc1234..def5678 (15 files changed)

[Round 1/3] Running independent reviews...
  ✓ claude (12.3s)
  ✓ gemini (8.1s)
  ✓ codex (15.7s)
[Round 2/3] Synthesizing reviews...
  ✓ claude (18.2s)
  ✓ gemini (14.5s)
  ✓ codex (20.1s)
[Round 3/3] Final synthesis...
  ✓ claude (10.4s)
  ✓ gemini (9.8s)
  ✓ codex (12.3s)

✓ Review complete!
  Output: .arena/rounds/final/champion_review.md
  Summary: 3 critical issues, 7 suggestions, 2 questions
```

## Error Handling

| Error | Behavior |
|-------|----------|
| Invalid git reference | Clear error with suggestion (e.g., "Did you mean 'main'?") |
| No git repository | Prompt to run from a git repository or specify path |
| Agent not installed | List missing agents with install instructions |
| Mid-review crash | Checkpoint saved, suggest `--resume` to continue |

## Integration with Config File

CLI arguments override config file settings. Precedence (highest to lowest):

1. CLI arguments
2. Environment variables
3. Config file (`arena.yaml`)
4. Built-in defaults

## Future Considerations (Out of Scope for v1)

- Subcommand architecture (`review-arena review`, `review-arena status`, `review-arena clean`)
- PR integration (`review-arena pr 123`)
- Watch mode for continuous review
- Interactive mode for real-time feedback
