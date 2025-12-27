# LLM Review Arena

A **process-orchestrated multi-agent code review tournament** that pits local CLI agents against each other in iterative rounds of collaborative refinement.

## Quick Start

```bash
# Review a single commit
review-arena abc1234

# Review changes between two commits
review-arena abc1234 def5678

# Review staged changes
review-arena --staged

# Review with custom round count
review-arena abc1234 --rounds 3
```

## Inspiration

This project is a vibe coding experiment inspired by Jeffrey Emanuel's work on multi-round LLM collaboration:

- [Making Complex Code Changes with Claude Code](https://www.jeffreyemanuel.com/writing/making_complex_code_changes_with_cc) - Dialectical process for iterative plan refinement between competing models
- [LLM Multi-Round Coding Tournament](https://www.jeffreyemanuel.com/writing/llm_multi_round_coding_tournament) - Tournament structure where models synthesize each other's solutions

The core insight: **collective intelligence outperforms individual genius** through structured cross-pollination of approaches.

## What It Does

The arena orchestrates multiple LLM CLI agents to perform **iterative code review**:

1. **Round 0**: Each agent independently reviews the same code/PR
2. **Round 1-N**: Each agent sees all previous reviews and synthesizes improvements
3. **Final**: A comprehensive, battle-tested review emerges from collaborative refinement

Default: **5 cross-pollination rounds** after Round 0 (6 total rounds, configurable via `--rounds` or `max_rounds` in config)

## The Tournament Model

```
Round 0 (Independent Reviews)
├── Claude CLI → claude/review.md
├── Codex CLI  → codex/review.md
└── Gemini CLI → gemini/review.md

Round 1 (Cross-Pollination)
├── All agents read: all_reviews.md (combined output)
├── Each produces: improved review incorporating best ideas
└── Context reset: fresh process, no conversation inertia

Round N (Final, default N=5)
└── Reviews refined through fixed number of rounds
```

## Key Design Principles

| Principle | Why It Matters |
|-----------|----------------|
| **No REST / No Model APIs** | Process orchestration, not API orchestration |
| **Local CLI Agents Only** | Claude CLI, Codex CLI, Gemini CLI as subprocesses |
| **Filesystem as Communication** | Shared markdown files, not token passing |
| **Ephemeral Agents** | Fresh context window each round (mandatory) |
| **Shared Output File** | Avoids combinatorial explosion of pairwise comparisons |

## Avoiding Combinatorial Explosion

Instead of each agent reviewing each other agent's output (N x N comparisons), we:

1. **Combine all outputs** into a single shared file (`all_reviews.md`)
2. **Reset context** each round with a fresh agent process
3. **Single prompt** instructs: "synthesize the best ideas from ALL reviews"

This keeps complexity linear while maximizing cross-pollination.

## Supported CLI Agents

- **Claude CLI** - Anthropic's Claude Code (`claude`)
- **Codex CLI** - OpenAI's Codex (`codex`)
- **Gemini CLI** - Google's Gemini (`gemini`)

## Arena Filesystem

```
.arena/
├── task.md                    # Task definition, rubric, and constraints
├── target/                    # Code under review
├── rounds/
│   ├── round-0/
│   │   ├── claude/
│   │   │   └── review.md
│   │   ├── codex/
│   │   │   └── review.md
│   │   ├── gemini/
│   │   │   └── review.md
│   │   └── all_reviews.md     # Combined output (input for round-1)
│   ├── round-1/
│   │   ├── [agent]/
│   │   │   └── review.md      # Synthesized improvements
│   │   └── all_reviews.md     # Combined output (input for round-2)
│   └── final/
│       └── champion_review.md # Synthesized final review (always produced by Claude)
```

## How It Works

### 1. Agent Process Lifecycle

Each round spawns ephemeral agent processes:

```
Start → Feed prompt (@prompt.txt) → Agent works → Capture output (review.md) → Kill
```

### 2. Round Execution

```yaml
# Round 0: Independent review
prompt: "Review this code. Write your review to review.md"

# Round 1+: Collaborative synthesis
prompt: |
  Read all_reviews.md containing all previous reviews.
  Identify the best ideas from each.
  Synthesize an improved review that combines complementary insights.
  Write to review.md
```

### 3. Context Reset Strategy

Each round starts a **new agent process** rather than continuing a conversation. This:

- Prevents anchoring to previous reasoning
- Allows fresh analytical perspectives
- Avoids conversation compression artifacts

## Why Code Review?

Code review is ideal for this tournament approach because:

- **No single correct answer** - Multiple valid perspectives exist
- **Complementary insights** - Security, performance, style, architecture
- **Measurable improvement** - Reviews get more comprehensive each round
- **Real-world value** - Better reviews = better code

## Configuration

Configuration file: `arena.yaml`

```yaml
agents:
  claude:
    command: ["claude", "-p", "@prompt.txt"]
  codex:
    command: ["codex", "exec", "@prompt.txt"]
  gemini:
    command: ["gemini", "-p", "@prompt.txt"]

execution:
  max_concurrent: 0    # 0 = unlimited parallel, 1 = sequential, N = max N agents at once

limits:
  max_output_size_kb: 500    # Maximum size per output file
  max_rounds: 5              # Maximum tournament rounds (default: 5)

timeouts:
  agent_timeout_ms: 300000   # Per-agent timeout (default: 5 minutes)
  round_timeout_ms: 900000   # Per-round timeout (default: 15 minutes)
  # See specs/spec.md for advanced timeout options (grace_period, per_agent, on_timeout)
```

## CLI Usage

```bash
review-arena [options] <ref1> [ref2]
```

### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `ref1` | **Yes** | Git commit hash |
| `ref2` | No | End commit hash for range comparison |

### Options

| Option | Short | Description |
|--------|-------|-------------|
| `--help` | `-h` | Show help and usage information |
| `--config <file>` | `-c` | Path to config file (default: `arena.yaml`) |
| `--rounds <n>` | `-r` | Maximum rounds (default: 5) |
| `--output <dir>` | `-o` | Output directory (default: `.arena`) |
| `--parallel` | | Force parallel agent execution |
| `--sequential` | | Force sequential agent execution |
| `--max-concurrent <n>` | | Limit concurrent agents (0=unlimited, 1=sequential) |
| `--staged` | | Review staged changes instead of commits |

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

| Variable | Description |
|----------|-------------|
| `REVIEW_ARENA_CONFIG` | Default config file path |
| `REVIEW_ARENA_OUTPUT_DIR` | Default output directory |
| `REVIEW_ARENA_MAX_ROUNDS` | Default maximum rounds (built-in default: 5) |
| `REVIEW_ARENA_MAX_CONCURRENT` | Default max concurrent agents |

Precedence (highest to lowest): CLI args → Environment variables → Config file → Built-in defaults

## Error Handling

When an agent fails during a round (crash, timeout, or invalid output):

| Behavior | Description |
|----------|-------------|
| **Exclude from current round** | The failed agent's output is not included in `all_reviews.md` |
| **Exclude from subsequent rounds** | The agent is removed from the tournament entirely |
| **Log error to console** | Failure details are printed to stderr |

The tournament continues with remaining agents. A single flaky agent does not block the entire review.

## Future Directions

- Judge agent to evaluate review quality per round
- Support for additional CLI agents (local models, custom MCP agents)
- Tournament brackets for large agent pools
- Subcommand architecture (`review-arena review`, `review-arena status`, `review-arena clean`)
- PR integration (`review-arena pr 123`)
- Watch mode for continuous review
- Interactive mode for real-time feedback
