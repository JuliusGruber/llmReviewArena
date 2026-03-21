# LLM Review Arena

## What It Does

A multi-agent code review tournament that orchestrates local CLI agents (Claude, Codex, Gemini) to perform iterative, collaborative code reviews:

1. **Round 0**: Each agent independently reviews code
2. **Rounds 1-N**: Agents see combined reviews from previous rounds and improve
3. **Final Synthesis**: Claude synthesizes all rounds into a `champion_review.md`

**Key design**: filesystem-based communication (no REST APIs) - agents share work via markdown files in a `.arena/` directory.

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

## Prerequisites: Coding Agents

LLM Review Arena orchestrates CLI-based coding agents as subprocesses. You need at least one agent installed.

### Required: Claude CLI

Claude CLI is **mandatory** - it's used for the final synthesis step that produces `champion_review.md`.

1. **Install** from [Anthropic's Claude Code](https://docs.anthropic.com/en/docs/claude-code)
2. **Verify installation:**
   ```bash
   claude --version
   ```
3. **Authenticate** following Claude Code setup instructions

### Optional: Additional Agents

Install additional agents to enable multi-agent tournament rounds. The more agents, the richer the cross-pollination.

#### Gemini CLI (enabled by default)

Install from [Google's Gemini CLI](https://github.com/google-gemini/gemini-cli):

```bash
npm install -g @google/gemini-cli
```

Verify: `gemini --version`

#### Codex CLI (disabled by default)

```bash
npm install -g @openai/codex
```

**Additional requirement:** Python 3 must be installed (used by the wrapper script).

Verify: `codex --version && python --version`

### Agent Configuration

Create `arena.yaml` in your project root to select which agents participate:

```yaml
# Select and order review agents using type shorthands
# The system auto-generates numbered instances (claude-1, codex-1, claude-2)
review-agents: claude, codex, claude

agents:
  # Synthesis agent (always Claude, used for final champion_review.md)
  synthesis:
    command:
      - claude
      - -p

  # Enable codex as a type template (disabled by default)
  codex:
    enabled: true
```

### Custom Agent Paths

If agents are not in your PATH, specify the full command on the type template:

```yaml
agents:
  claude:
    command:
      - /usr/local/bin/claude
      - -p
```

### Minimum Agents

The tournament requires at least 1 agent by default (allows single-agent mode when only Claude is enabled). For cross-pollination enforcement, set:

```yaml
tournament:
  min-agents: 2
```

## Docker Support

Run agents inside Docker containers for improved isolation, security, and reproducibility. This is especially useful for:

- **CI/CD environments** where agents may not be installed locally
- **Reproducible reviews** with consistent agent versions
- **Isolation** to prevent agents from accessing files outside the project

### Prerequisites

- Docker or Docker Desktop installed and running
- `docker` command available in your PATH

Verify Docker installation:

```bash
docker info
```

### Enabling Docker Mode

Add `docker` configuration to agent type templates or the synthesis agent in `arena.yaml`:

```yaml
agents:
  # Type template: Docker config is inherited by all generated instances
  claude:
    docker:
      enabled: true
      # image: "ghcr.io/zeeno-atl/claude-code:latest"  # Optional, uses default
      # memory: "4g"  # Optional: memory limit
      # cpus: "2"     # Optional: CPU limit
    command: ["claude", "-p"]
    flags:
      auto-approve: true

  # Synthesis agent: separate Docker config for final synthesis step
  synthesis:
    docker:
      enabled: true
    command: ["claude", "-p"]
```

### Default Images

| Agent | Default Image | Notes |
|-------|---------------|-------|
| Claude | `ghcr.io/zeeno-atl/claude-code:latest` | Community-maintained image |
| Codex | _(none — user must specify image)_ | Community images don't support non-interactive mode |
| Gemini | `tgagor/gemini-cli:latest` | Community-maintained image |

### Environment Variables

API keys are automatically passed to containers when set in your environment:

| Variable | Agent |
|----------|-------|
| `ANTHROPIC_API_KEY` | Claude |
| `OPENAI_API_KEY` | Codex |
| `GEMINI_API_KEY` | Gemini |
| `GOOGLE_API_KEY` | Gemini (alternate) |

### How Docker Mode Works

1. The project directory is mounted as `/workspace` in the container
2. All paths in commands are automatically translated (e.g., `C:\project\.arena\...` → `/workspace/.arena/...`)
3. Containers are ephemeral (`--rm`) and cleaned up after each agent run
4. Stdin is forwarded to containers for prompt delivery

### Isolation Notes

Docker with `--network host` provides **convenience isolation**, not security isolation:

| Provides | Does NOT Provide |
|----------|------------------|
| Filesystem isolation (only `/workspace` accessible) | Network isolation |
| Process isolation | Protection from localhost services |
| Clean environment | Full sandboxing |

For stricter isolation, consider running in a network-restricted environment.

### Troubleshooting

| Issue | Solution |
|-------|----------|
| "Docker is not installed or not in PATH" | Install Docker Desktop and ensure `docker` is in your PATH |
| "Docker daemon is not running" | Start Docker Desktop or run `sudo systemctl start docker` |
| Container can't find files | Ensure files are under the project directory (mounted at `/workspace`) |
| API key not working in container | Verify the env var is set in your shell: `echo $ANTHROPIC_API_KEY` |

## Inspiration

This project is a vibe coding experiment inspired by Jeffrey Emanuel's work on multi-round LLM collaboration:

- [Making Complex Code Changes with Claude Code](https://www.jeffreyemanuel.com/writing/making_complex_code_changes_with_cc) - Dialectical process for iterative plan refinement between competing models
- [LLM Multi-Round Coding Tournament](https://www.jeffreyemanuel.com/writing/llm_multi_round_coding_tournament) - Tournament structure where models synthesize each other's solutions

The core insight: **collective intelligence outperforms individual genius** through structured cross-pollination of approaches.

## The Tournament Model

```
Round 0 (Independent Reviews)
├── claude-1 → claude-1/review.md
├── codex-1  → codex-1/review.md
└── claude-2 → claude-2/review.md

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
├── prompts/
│   ├── task.md                    # Task definition, rubric, and constraints
│   ├── round-0-claude-1.md       # Pre-generated round prompts
│   └── ...
├── rounds/
│   ├── round-0/
│   │   ├── claude-1/
│   │   │   ├── review.md
│   │   │   ├── stdout.log
│   │   │   └── stderr.log
│   │   ├── codex-1/
│   │   │   └── review.md
│   │   ├── claude-2/
│   │   │   └── review.md
│   │   └── all_reviews.md     # Combined output (input for round-1)
│   ├── round-1/
│   │   └── ...
│   └── final/
│       ├── prompt.md          # Persisted synthesis prompt
│       └── champion_review.md
```

## How It Works

### 1. Agent Process Lifecycle

Each round spawns ephemeral agent processes:

```
Start → Feed prompt (via stdin) → Agent works → Capture output (review.md) → Kill
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
# Select review agents using type shorthands (auto-expanded to numbered instances)
review-agents: claude, codex, claude

agents:
  # Type templates: config inherited by generated instances
  claude:
    command: ["claude", "-p"]
    flags:
      output-format: json
  codex:
    command: ["codex", "exec", "--full-auto", "-o", "@output", "-"]
    flags:
      auto-approve: false
  gemini:
    command: ["gemini"]

  # Synthesis agent (always Claude, used for final champion_review.md)
  synthesis:
    command: ["claude", "-p"]

execution:
  max-concurrent: 0    # 0 = unlimited parallel, 1 = sequential, N = max N agents at once

limits:
  max-output-size-kb: 500    # Maximum size per output file
  rounds: 5                  # Maximum cross-pollination rounds (default: 5)

timeouts:
  agent-timeout-ms: 600000   # Per-agent timeout (default: 10 minutes)
  round-timeout-ms: 900000   # Per-round timeout (default: 15 minutes)
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
| `--dry-run` | | Show what would happen without running agents |
| `--quiet` | `-q` | Suppress agent output (don't stream to console) |

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
| **Retry in subsequent rounds** | The agent still participates in all subsequent rounds |
| **Log error to console** | Failure details are printed to stderr |

The tournament continues with remaining agents. Transient failures (API timeouts, rate limits) do not permanently disqualify an agent. This maintains review diversity across all rounds.

## Future Directions

- Judge agent to evaluate review quality per round
- Support for additional CLI agents (local models, custom MCP agents)
- Tournament brackets for large agent pools
- Subcommand architecture (`review-arena review`, `review-arena status`, `review-arena clean`)
- PR integration (`review-arena pr 123`)
- Watch mode for continuous review
- Interactive mode for real-time feedback
