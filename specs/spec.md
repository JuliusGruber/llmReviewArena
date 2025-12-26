# LLM Review Arena Specification

## Overview

A **process-orchestrated agent arena** - not an API-orchestrated multi-model system.

## Core Principles

| Principle | Description |
|-----------|-------------|
| No REST / No Model APIs | System does not use REST endpoints or model APIs |
| Local CLI Agents Only | All agents run as local command-line processes |
| Fully Terminal-Based | Entire system operates within the terminal |
| Agents-Only Execution | All work is performed by agents, not direct API calls |

## Supported CLI Agents

The arena orchestrates the following CLI agents as processes:

- **Claude CLI** - Anthropic's Claude Code CLI
- **Codex CLI** - OpenAI's Codex CLI
- **Gemini CLI** - Google's Gemini CLI

## Architecture

The system uses **process orchestration** to manage and coordinate multiple CLI agents, spawning them as subprocesses and managing their inputs/outputs through the terminal.

## Core Abstraction: AgentProcess

At the heart of the system is a small, powerful abstraction:

```
AgentProcess
├── name              (claude, codex, gemini)
├── command           (shell command to start it)
├── working_directory (isolated per agent)
├── stdin             (prompt injection)
├── stdout            (captured logs + responses)
├── lifecycle         (start / stop / restart)
```

### Agent Configuration

Agents are defined via YAML configuration:

```yaml
agents:
  claude:
    command: ["claude", "chat", "--dangerously-allow-file-access"]

  codex:
    command: ["codex"]

  gemini:
    command: ["gemini", "chat"]
```

> **Note:** Agent working directories are set dynamically per round (see [Arena Filesystem](#arena-filesystem)).

### Design Benefits

This abstraction keeps the arena:

- **Model-agnostic** - No coupling to specific LLM providers
- **Future-proof** - New CLI agents can be added via configuration
- **Extensible** - Compatible with any CLI agent, including custom MCP-based ones

## Agent Execution Model

### Ephemeral Agents (Recommended)

The recommended execution model uses **ephemeral agents** - stateless, short-lived processes that are created fresh for each round:

1. **Start** agent process
2. **Feed** it a prompt (via stdin)
3. **Let it work** (agent executes autonomously)
4. **Capture output** (from stdout)
5. **Kill** process

This approach ensures:
- Clean state for each evaluation round
- No cross-contamination between tasks
- Predictable, reproducible behavior
- Simple resource management

## Arena Filesystem

Since agents are local and tool-enabled, the **filesystem becomes the shared communication layer** - not tokens.

### Directory Structure

```
.arena/
├── task.md                    # Current task definition
├── rounds/
│   ├── round-0/
│   │   ├── claude/
│   │   │   └── solution.md
│   │   ├── codex/
│   │   │   └── solution.md
│   │   └── gemini/
│   │       └── solution.md
│   ├── round-1/
│   │   └── ...
│   └── final/
└── evaluation/
    └── README.md
```

### Agent Capabilities

Each agent operates in its round-specific working directory (e.g., `.arena/rounds/round-0/claude/`) and can:

- **Write actual files** - Create solutions, code, documentation
- **Run tests** - Execute and validate their work
- **Inspect previous rounds** - Learn from prior attempts
- **Diff other agents' output** - Compare approaches across agents

This filesystem-based communication is a **major advantage over API-only systems**, enabling rich, tool-augmented collaboration.

## Code Review Tournament Flow

The arena implements a **multi-round tournament** for code review, where agents iteratively improve their reviews by seeing competitors' outputs each round.

### 0) Inputs (What You Give the Arena)

| Input | Description |
|-------|-------------|
| **Target to review** | Git diff / PR branch / patch file (recommended) + context files (README, ADR, test failures) |
| **Review rubric** | Short, explicit criteria: correctness & edge cases, security & privacy, performance, maintainability/design, tests |
| **Output format contract** | Ensures reviews are comparable |

#### Required Review Output Format

Each review must contain:
- Summary
- High-risk issues (must fix)
- Medium/low issues
- Suggested patch snippets
- Test suggestions
- Questions for author

### 1) Workspace + Artifacts (Filesystem as Shared Prompt)

The arena creates a deterministic structure for code review tasks:

```
.arena/
├── task.md                           # Instructions + rubric + links to files/diff
├── target/                           # Checked-out code or extracted patch
├── rounds/
│   ├── round-0/
│   │   ├── claude/
│   │   │   └── review.md
│   │   ├── codex/
│   │   │   └── review.md
│   │   ├── gemini/
│   │   │   └── review.md
│   │   └── all_reviews.md            # Combined reviews from all agents
│   ├── round-1/
│   │   └── ...
│   └── final/
│       ├── side_by_side.md
│       ├── issue_matrix.md
│       ├── suggested_patches/
│       ├── questions.md
│       └── champion_review.md
```

### 2) Round 0 – Independent Reviews

For each agent (fresh process):

1. Start agent in its own working directory
2. Feed it:
   - The rubric + `task.md`
   - The diff/path to repo
   - Strict instruction: "Write review to `review.md`"
3. Stop the agent process (ephemeral processes = clean state)

**Result:** Three independent reviews with no cross-contamination.

### 3) Build the Combined Submission

To avoid pairwise sharing overhead, assemble a single combined file:

**`all_reviews.md`** containing:
- The original task/rubric
- Review A (Claude)
- Review B (Codex)
- Review C (Gemini)

> This matches the scaling trick: put all responses for the round into one markdown file.

### 4) Round 1 – Review-of-Reviews (Cross-Pollination)

For each agent (new fresh process to reset context for strict round isolation):

**Give it:**
- `task.md`
- The combined `all_reviews.md`
- Instruction:
  - "Identify what the other reviews missed or got wrong."
  - "Merge the best insights into a stronger review."
  - "Be more specific: file/line references, concrete fixes, test cases."

**Output:** `round-1/<agent>/review.md`

### 5) Round 2..N – Iterative Refinement

Repeat until convergence:

1. Merge round-(k) reviews into `round-k/all_reviews.md`
2. Each agent produces an improved review that:
   - Removes weak/incorrect comments
   - Adds missed issues
   - Tightens prioritization
   - Adds actionable patches/tests

**Stop when:**
- Reviews converge (mostly same issues), or
- Fixed round limit reached (e.g., 3–5 rounds)

### 6) Final Output for Human Evaluation

Generate an "evaluation pack":

| File | Purpose |
|------|---------|
| `final/side_by_side.md` | Round N reviews, aligned by section |
| `final/issue_matrix.md` | Issue → which agent flagged it, severity, evidence |
| `final/suggested_patches/` | Diff snippets extracted from reviews |
| `final/questions.md` | Questions to ask the PR author |

Human picks:
- The best final review, or
- A merged "champion review"

### 7) Optional: Synthesizer Final Step

After the last round, run one more agent process in a dedicated role:

- **Input:** The final `all_reviews.md`
- **Output:** `final/champion_review.md` that merges duplicated comments and produces one clean review (keeping citations to evidence)

### 8) Key Behaviors (The Tournament Method)

| Behavior | Description |
|----------|-------------|
| **Same initial prompt** | All agents receive identical starting prompt |
| **Cross-pollination** | Each round, agents see competitors' outputs and improve |
| **Avoid combinatorial blow-up** | One combined markdown file per round (not pairwise) |
| **Optional state reset** | Fresh process each round for strict isolation |
