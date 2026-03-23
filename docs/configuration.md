# Configuration

## How Configuration Is Loaded

Review Arena uses a layered configuration system. Understanding where config files are loaded from is important for correct agent selection.

### Config File Resolution

By default, Review Arena looks for `arena.yaml` **in the current working directory** — the directory you run the command from, not where the JAR is located.

```
# If you run from /home/user/my-project:
cd /home/user/my-project
review-arena abc1234
# → Loads /home/user/my-project/arena.yaml (if it exists)
# → Falls back to built-in defaults if not found
```

If no `arena.yaml` is found in the current directory, the app **silently falls back** to built-in defaults bundled inside the JAR (`application.yaml`). The defaults configure 3 Claude agents (`claude, claude, claude`) with Codex and Gemini disabled.

### Override the Config Path

You can point to a specific config file using either:

| Method | Example |
|--------|---------|
| `-c` / `--config` flag | `review-arena -c /path/to/arena.yaml abc1234` |
| `REVIEW_ARENA_CONFIG` env var | `REVIEW_ARENA_CONFIG=/path/to/arena.yaml review-arena abc1234` |

### Configuration Precedence

Settings are resolved in this order (highest priority wins):

```
CLI arguments  →  Environment variables  →  arena.yaml  →  Built-in defaults
   (highest)                                                    (lowest)
```

For example, `--rounds 3` on the command line overrides `rounds: 5` in `arena.yaml`, which overrides the built-in default of 5.

### Per-Project Configuration

The recommended approach is to place an `arena.yaml` in each project you want to review. This lets each project define its own agent mix:

```yaml
# my-project/arena.yaml
review-agents: claude, codex, claude

agents:
  codex:
    enabled: true
```

### Common Pitfall: Config Not Picked Up

If you define a PowerShell/bash function or alias to run the JAR:

```powershell
# PowerShell
function review-arena {
    java -jar "C:\path\to\review-arena-1.0-SNAPSHOT.jar" @args
}
```

```bash
# Bash
alias review-arena='java -jar /path/to/review-arena-1.0-SNAPSHOT.jar'
```

The app will look for `arena.yaml` in **whatever directory you're currently in**, not in the directory containing the JAR. If you run `review-arena` from a project that doesn't have its own `arena.yaml`, the built-in defaults are used instead.

**Fixes:**

1. **Copy `arena.yaml` into each project** (recommended — allows per-project config):
   ```bash
   cp /path/to/llmReviewArena/arena.yaml /path/to/my-project/arena.yaml
   ```

2. **Always pass the config path explicitly** via your shell function:
   ```powershell
   # PowerShell
   function review-arena {
       java -jar "C:\path\to\review-arena-1.0-SNAPSHOT.jar" -c "C:\path\to\arena.yaml" @args
   }
   ```

3. **Set the environment variable** so all invocations use the same config:
   ```powershell
   $env:REVIEW_ARENA_CONFIG = "C:\path\to\arena.yaml"
   ```

## Built-In Defaults

When no `arena.yaml` is found, the following defaults (from `application.yaml`) are used:

| Setting | Default |
|---------|---------|
| `review-agents` | `claude, claude, claude` |
| `limits.rounds` | `5` |
| `limits.max-output-size-kb` | `500` |
| `execution.max-concurrent` | `0` (unlimited) |
| `timeouts.agent-timeout-ms` | `600000` (10 min) |
| `timeouts.round-timeout-ms` | `900000` (15 min) |
| `timeouts.grace-period-ms` | `5000` (5 sec) |
| `tournament.min-agents` | `1` |
| `agents.claude.enabled` | `true` |
| `agents.codex.enabled` | `false` |
| `agents.gemini.enabled` | `false` |

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REVIEW_ARENA_CONFIG` | Config file path | `arena.yaml` (in CWD) |
| `REVIEW_ARENA_OUTPUT_DIR` | Output directory | `.arena` |
| `REVIEW_ARENA_MAX_ROUNDS` | Max rounds | `5` |
| `REVIEW_ARENA_MAX_CONCURRENT` | Max concurrent agents | `0` (unlimited) |

## arena.yaml Reference

A full example with all available settings:

```yaml
# Agent selection — type shorthands, auto-expanded to numbered instances
# e.g., "claude, codex, claude" becomes claude-1, codex-1, claude-2
review-agents: claude, codex, claude

agents:
  # Type templates: config inherited by all generated instances of this type
  claude:
    command: ["claude", "-p"]
    flags:
      auto-approve: true
      output-format: json

  codex:
    command: ["codex", "exec", "--full-auto", "-o", "@output", "-"]
    flags:
      auto-approve: false
    enabled: true  # Must enable explicitly (disabled by default)

  gemini:
    command: ["gemini"]
    flags:
      auto-approve: true
    enabled: true  # Must enable explicitly (disabled by default)

  # Synthesis agent (always Claude, used for final champion_review.md)
  synthesis:
    command: ["claude", "-p"]
    docker:
      enabled: false

execution:
  max-concurrent: 0  # 0 = unlimited, 1 = sequential

limits:
  rounds: 5
  max-output-size-kb: 500

timeouts:
  agent-timeout-ms: 600000
  round-timeout-ms: 900000
  grace-period-ms: 5000

tournament:
  min-agents: 1  # Set to 2 to enforce cross-pollination
```
