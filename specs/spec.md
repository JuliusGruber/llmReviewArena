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

## Agent Prompt Templates

The following prompts drive the multi-round tournament. They are designed to:
- Work with Claude / Codex / Gemini CLI
- Not assume API usage
- Keep output structured and comparable
- Converge rather than drift

### Global Invariants (task.md)

This file is referenced every round and defines the shared context:

```markdown
# Code Review Arena – Task

You are participating in a multi-round code review arena.

## Goal
Produce the highest-quality, most useful code review possible.

The review should help a human author:
- find real bugs and risks
- understand why they matter
- fix them efficiently

## Review Rubric
Evaluate the code with respect to:

1. Correctness & edge cases
2. Security & privacy
3. Performance & scalability
4. Maintainability & design
5. Tests & observability

## Constraints
- Be concrete and actionable
- Prefer evidence over speculation
- Reference files, symbols, or diff hunks where possible
- Avoid generic advice
- Do not repeat trivial style nitpicks unless they matter

## Output Contract
Write your review to `review.md` using **exactly** this structure:

### Summary
### High-risk issues (must fix)
### Medium / low-risk issues
### Suggested patches (diff snippets or pseudocode)
### Test suggestions
### Questions for the author
```

### Round 0 — Independent Review

**Purpose:** Establish diverse, unpolluted first-pass reviews.

**Each agent sees:**
- The code/diff
- `task.md`
- No other agent output

**Prompt:** `round-0/prompt.txt`

```
You are an expert software engineer performing a rigorous code review.

This is Round 0.
No other reviews exist yet.

Your task:
- Review the provided code or diff thoroughly.
- Identify concrete issues using the rubric in task.md.
- Prioritize correctness and real risk over stylistic preferences.

Rules:
- Do NOT assume missing context unless clearly required.
- Do NOT mention other reviewers or models.
- Write only the final review.

Write your output to `review.md` following the required structure.
```

### Round 1 — Review of Reviews (Core Tournament Step)

**Purpose:** The core tournament mechanism — see competitors' outputs, identify misses, merge the best ideas.

**Each agent sees:**
- Original code
- All Round-0 reviews merged into `all_reviews.md`
- Still produces its own improved review

**Prompt:** `round-1/prompt.txt`

```
This is Round 1 of a multi-round code review arena.

You are given:
- the original code under review
- a file containing reviews from other agents (all_reviews.md)

Your task:
1. Read all competing reviews carefully.
2. Identify:
   - issues they missed
   - incorrect or weak claims
   - places where an issue is mentioned but not actionable
3. Produce a strictly better review by:
   - keeping the strongest insights
   - removing noise or speculation
   - adding missing high-impact issues
   - improving prioritization and clarity

Important:
- Do NOT reference other reviewers by name.
- Do NOT argue defensively.
- Act as if you want the best possible review to exist, regardless of authorship.

Write a complete, standalone review to `review.md`
using the same structure as before.
```

> This prompt is the direct analog of the "which solution is better, and can you combine the best of both?" step.

### Round 2 — Precision, Evidence, and Fixability

**Purpose:** Reviews often converge conceptually but remain vague. This round forces engineering precision.

**Each agent sees:**
- Original code
- Round-1 `all_reviews.md`

**Prompt:** `round-2/prompt.txt`

```
This is Round 2.

You are reviewing an already strong set of reviews.

Your task:
- Increase precision and usefulness.
- Eliminate vague or speculative comments.
- Ensure every high-risk issue includes:
  - concrete evidence (file, function, behavior)
  - why it matters
  - how to fix or mitigate it

Focus especially on:
- subtle correctness bugs
- edge cases
- security implications
- design flaws that will cause future bugs

If multiple reviews mention the same issue:
- consolidate it
- choose the strongest framing
- remove duplication

Write a refined, high-signal review to `review.md`.
```

### Round 3 — Final Convergence (Optional)

**Purpose:** Produce near-identical, very high-quality reviews.

**Prompt:** `round-3/prompt.txt`

```
This is the final refinement round.

Assume the author will read only one review.

Your task:
- Produce the cleanest, clearest, most authoritative review possible.
- Remove redundancy.
- Ensure severity levels are correct.
- Ensure suggested fixes are realistic.

Bias toward:
- fewer but higher-impact comments
- clarity over exhaustiveness
- decisions the author can act on immediately

Write the final review to `review.md`.
```

> At this point, agents typically differ only in wording, not substance.

### Final Synthesizer Round (Optional)

**Purpose:** Produce one canonical review from multiple high-quality finals.

**Prompt:** `final-synth/prompt.txt`

```
You are the final synthesizer in a code review arena.

You are given multiple high-quality final reviews.

Your task:
- Merge them into one single, cohesive review.
- Remove duplicates.
- Resolve conflicting recommendations.
- Keep the strongest phrasing and evidence.

Do NOT introduce new issues.
Do NOT speculate.

Produce one final review in `champion_review.md`
using the standard review structure.
```

### Why This Works

| Property | Description |
|----------|-------------|
| Independent first pass | Diverse initial perspectives without contamination |
| Iterative cross-review | Each round builds on competitors' insights |
| Shared combined file | One `all_reviews.md` per round avoids pairwise explosion |
| Fresh context each round | New process = clean slate |
| Convergence over debate | Prompts drive toward agreement, not argument |
| Filesystem-grounded | Real files, real diffs, real CLI agents |
