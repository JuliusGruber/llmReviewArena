# CLI Specification

## Basic Usage

```bash
review-arena [options] <ref1> [ref2]
```

This triggers the multi-round code review tournament.

## Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `ref1` | **Yes** | Git commit hash |
| `ref2` | No | End commit hash for range comparison |

### Supported Git Reference Formats

| Format | Example | Description |
|--------|---------|-------------|
| Single commit | `abc1234` | Review changes in one commit |
| Commit range | `abc1234 def5678` | Review changes between two commits |
| Staged changes | `--staged` | Review currently staged changes |

### Behavior

- If only `ref1` is provided: Review the changes introduced in that single commit
- If both `ref1` and `ref2` are provided: Review the diff between the two commits
- With `--staged`: Review currently staged changes (ignores ref arguments)

### Git Integration

The orchestrator does **not** perform git operations itself. It simply passes the commit hash(es) or `--staged` flag to agents via prompts. Agents use their own git capabilities to inspect the code changes.

This keeps the orchestrator simple and leverages the full power of tool-enabled CLI agents.

## Options

| Option | Short | Description |
|--------|-------|-------------|
| `--help` | `-h` | Show help and usage information |
| `--config <file>` | `-c` | Path to config file (default: `arena.yaml`) |
| `--rounds <n>` | `-r` | Maximum rounds (default: 5, overrides config) |
| `--output <dir>` | `-o` | Output directory (default: `.arena`) |
| `--parallel` | | Force parallel agent execution |
| `--sequential` | | Force sequential agent execution |
| `--max-concurrent <n>` | | Limit concurrent agents (0=unlimited, 1=sequential) |
| `--staged` | | Review staged changes instead of commits |


## Examples

```bash
# Review a single commit
review-arena abc1234

# Review changes between two commits
review-arena abc1234 def5678

# Review staged changes
review-arena --staged

# Custom output directory
review-arena abc1234 --output ./reviews
```

## Exit Codes

For CI/CD integration and scripting:

| Code | Meaning |
|------|---------|
| 0 | Success - review completed |
| 1 | General error |
| 2 | Invalid arguments / usage error |
| 3 | Git error (no repository, invalid reference) |
| 4 | Agent error (CLI not found, execution failed, insufficient agents) |
| 5 | Configuration error (invalid config file) |

## Environment Variables

For automation and default configuration:

| Variable | Description |
|----------|-------------|
| `REVIEW_ARENA_CONFIG` | Default config file path |
| `REVIEW_ARENA_OUTPUT_DIR` | Default output directory |
| `REVIEW_ARENA_MAX_ROUNDS` | Default maximum rounds (built-in default: 5) |
| `REVIEW_ARENA_MAX_CONCURRENT` | Default max concurrent agents |

## Progress Output

Progress uses **0-indexed round numbers** (matching internal round numbering):

```
Reviewing: abc1234..def5678 (15 files changed)

[Round 0/5] Running independent reviews...
  ✓ claude (12.3s)
  ✓ gemini (8.1s)
  ✓ codex (15.7s)
[Round 1/5] Cross-pollination round 1...
  ✓ claude (18.2s)
  ✓ gemini (14.5s)
  ✓ codex (20.1s)
[Round 2/5] Cross-pollination round 2...
  ✓ claude (10.4s)
  ✓ gemini (9.8s)
  ✓ codex (12.3s)
...
[Round 5/5] Final cross-pollination round...
  ✓ claude (8.1s)
  ✓ gemini (7.2s)
  ✓ codex (9.5s)

✓ Review complete!
  Output: .arena/rounds/final/champion_review.md
  Summary: 3 critical issues, 7 suggestions, 2 questions
```

**Format:** `[Round X/N]` where X is the current round (0-indexed) and N is `max-rounds`.

## Error Handling

| Error | Behavior |
|-------|----------|
| Invalid git reference | `Error: Commit <hash> not found` |
| Invalid hash format | `Error: Invalid commit hash format: <ref>` |
| No git repository | `Error: Not a git repository` |
| Agent not installed | `Error: Agent '<name>' not found` |

Error messages are minimal and direct. Exit codes provide machine-readable status (see [Exit Codes](#exit-codes)).

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
