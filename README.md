# LLM Review Arena

A **process-orchestrated multi-agent code review tournament** that pits local CLI agents against each other in iterative rounds of collaborative refinement.

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

## The Tournament Model

```
Round 0 (Independent Reviews)
├── Claude CLI → review-claude.md
├── Codex CLI  → review-codex.md
└── Gemini CLI → review-gemini.md

Round 1 (Cross-Pollination)
├── All agents read: all_reviews.md (combined output)
├── Each produces: improved review incorporating best ideas
└── Context reset: fresh process, no conversation inertia

Round N (Convergence)
└── Reviews converge toward comprehensive synthesis
```

## Key Design Principles

| Principle | Why It Matters |
|-----------|----------------|
| **No REST / No Model APIs** | Process orchestration, not API orchestration |
| **Local CLI Agents Only** | Claude CLI, Codex CLI, Gemini CLI as subprocesses |
| **Filesystem as Communication** | Shared markdown files, not token passing |
| **Ephemeral Agents** | Fresh context each round prevents anchoring |
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
├── task.md                    # Code to review / PR description
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
│       ├── side_by_side.md    # Final reviews aligned by section
│       ├── issue_matrix.md    # Issue tracking across agents
│       ├── suggested_patches/ # Extracted diff snippets
│       ├── questions.md       # Questions for PR author
│       └── champion_review.md # Synthesized final review
└── evaluation/
    └── summary.md
```

## How It Works

### 1. Agent Process Lifecycle

Each round spawns ephemeral agent processes:

```
Start → Feed prompt (stdin) → Agent works → Capture output (stdout) → Kill
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

Agents are defined via YAML:

```yaml
agents:
  claude:
    command: ["claude", "chat", "--dangerously-allow-file-access"]
  codex:
    command: ["codex"]
  gemini:
    command: ["gemini", "chat"]
```

## Future Directions

- Judge agent to evaluate review quality per round
- Automatic convergence detection
- Support for additional CLI agents (local models, custom MCP agents)
- Tournament brackets for large agent pools
