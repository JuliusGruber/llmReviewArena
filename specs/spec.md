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

### Abstraction Boundary Clarification

The "No REST / No Model APIs" principle applies to the **arena/orchestrator layer**, not to the CLI agents themselves:

| Layer | Uses APIs? | Responsibility |
|-------|------------|----------------|
| **Arena/Orchestrator** | No | Process management, filesystem I/O only |
| **CLI Agents** (Claude, Codex, Gemini) | Yes (internally) | May call LLM APIs under the hood |

**Key points:**
- The orchestrator spawns processes and reads/writes files—it never calls model APIs directly
- CLI agents like Claude Code internally use LLM APIs, but this is **abstracted away** from the arena
- References to "rate-limited backends" (e.g., in execution settings) refer to the LLM APIs that CLI tools use internally
- This separation keeps the arena model-agnostic while allowing agents to leverage their full capabilities

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
├── stdin             (prompt delivery via stdin pipe)
├── stdout            (captured logs + responses)
├── lifecycle         (start / stop / restart)
```

### Agent Configuration

Agents are defined via YAML configuration with explicit flag management:

```yaml
agents:
  claude:
    command: ["claude", "-p"]
    flags:
      auto-approve: true
      output-format: json

  codex:
    command: ["codex", "exec", "--full-auto", "-o", "@output", "-"]
    flags:
      auto-approve: false     # --full-auto already in command

  gemini:
    command: ["gemini"]
    flags:
      auto-approve: true
```

> **Note:** Prompts are delivered via **stdin** — the orchestrator reads the prompt file into memory and pipes it to the agent process's stdin. This avoids file locking issues on Windows and works uniformly across all agents.

> **Note:** The `@output` placeholder in commands is replaced with the absolute path to the agent's output file (e.g., `.arena/rounds/round-0/claude-1/review.md`).

> **Note:** Agent working directories are set dynamically per round (see [Arena Filesystem](#arena-filesystem)).

#### Flag Configuration Reference

The `flags` section provides a portable, CLI-agnostic way to configure agent behavior. The orchestrator translates these to CLI-specific flags at runtime:

| Config Flag | Claude CLI | Codex CLI | Gemini CLI |
|-------------|------------|-----------|------------|
| `auto-approve: true` | `--dangerously-skip-permissions` | `--full-auto` | `--yolo` |
| `allowed-tools: [...]` | `--allowedTools <list>` | N/A | N/A |
| `output-format: <format>` | `--output-format <format>` | N/A | N/A |

#### Mandatory vs Optional Flags

| Flag | Required? | Rationale |
|------|-----------|-----------|
| `auto-approve` | **Yes** | Agents must run non-interactively without prompts |
| `allowed-tools` | No | Optional security restriction (Claude only) |

**Default behavior:** If `flags` is omitted, the orchestrator uses safe defaults:
- `auto-approve: true` (required for non-interactive execution)
- `allowed-tools`: unrestricted (agent uses all available tools)

### Design Benefits

This abstraction keeps the arena:

- **Model-agnostic** - No coupling to specific LLM providers
- **Future-proof** - New CLI agents can be added via configuration
- **Extensible** - Compatible with any CLI agent, including custom MCP-based ones

## Agent Execution Model

### Ephemeral Agents (Mandatory)

The arena **requires ephemeral agents** - stateless, short-lived processes that are created fresh for each round. This ensures a clean context window every time:

1. **Start** agent process
2. **Feed** it a prompt (via stdin pipe)
3. **Let it work** (agent executes autonomously)
4. **Capture output** (from stdout)
5. **Kill** process

This approach ensures:
- Clean state for each evaluation round
- No cross-contamination between tasks
- Predictable, reproducible behavior
- Simple resource management

### Execution Parallelism

Within a single round, agents MAY execute in parallel since they operate on isolated directories and produce independent outputs. Across rounds, execution is always sequential (round N must complete before round N+1 begins).

| Mode | Description |
|------|-------------|
| **Parallel** (default) | All agents in a round start simultaneously |
| **Bounded parallel** | Limit concurrent agents (useful for rate-limited backends) |
| **Sequential** | One agent at a time (easier debugging, lower resource usage) |

Results are semantically identical regardless of execution order within a round.

**Configuration:**

```yaml
execution:
  max-concurrent: 0    # 0 = unlimited parallel, 1 = sequential, N = max N agents at once
```

> **Note:** Higher concurrency increases resource usage (memory, API rate limits). Use bounded or sequential execution when running agents that share rate-limited backends.

### Resource Limits

The arena enforces limits on agent outputs to prevent runaway processes:

**Configuration:**

```yaml
limits:
  rounds: 5              # Number of cross-pollination rounds after Round 0 (default: 5, minimum: 1)
  max-output-size-kb: 500    # Maximum size per output file (e.g., review.md)
```

**Constraint:** `rounds` must be at least 1. Cross-pollination is the core value proposition of the arena—running only Round 0 with no improvement cycle provides no tournament benefit over running a single agent directly. The orchestrator rejects `rounds: 0` with exit code 5 (config error).

#### Round Counting (0-indexed)

Rounds are **always 0-indexed**, both internally and in user-facing output:

| Round | Type | Description |
|-------|------|-------------|
| Round 0 | Independent | Each agent reviews code independently, no cross-pollination |
| Round 1-N | Cross-pollination | Agents see all previous reviews and improve |

**`rounds` semantics:**
- `rounds: 5` means 5 cross-pollination rounds (Rounds 1-5) after Round 0
- Total rounds executed = Round 0 + `rounds` cross-pollination rounds
- Example: `rounds: 5` → Rounds 0, 1, 2, 3, 4, 5 (6 total rounds)

Progress output uses 0-indexed display: `Round 0/5`, `Round 1/5`, ..., `Round 5/5`

These are **spec-level limits** that the orchestrator enforces. OS-level resource controls (memory, CPU, disk quotas) are deployment-specific and outside this spec's scope—use containerization or OS process limits for production deployments.

### Process Timeouts

The arena enforces time limits on agent processes to prevent indefinite hangs:

**Configuration:**

```yaml
timeouts:
  agent-timeout-ms: 600000      # Per-agent process timeout (default: 10 minutes)
  round-timeout-ms: 900000      # Per-round timeout (default: 15 minutes)
  grace-period-ms: 5000         # Graceful shutdown window before force kill
```

> **Default Rationale:** 10 minutes allows thorough review of complex diffs.

**Timeout Behavior:**

| Timeout Type | Trigger | Action |
|--------------|---------|--------|
| Agent timeout | Single agent exceeds `agent-timeout-ms` | Request graceful termination → wait `grace-period-ms` → force kill, exclude from round |
| Round timeout | Round exceeds `round-timeout-ms` | Kill all running agents, proceed with completed outputs |

Partial output from timed-out agents is always discarded. The tournament continues with the remaining agents.

### Output Validation

The orchestrator validates agent output after each round:

| Check | Behavior on Failure |
|-------|---------------------|
| Output file exists (`review.md`) | Agent excluded from round, warning logged |
| File is non-empty | Agent excluded from round, warning logged |
| File exceeds size limit | Warning logged, full content kept (no truncation) |
| Agent completes within timeout | Agent killed, excluded from round, warning logged |


The tournament continues with remaining agents. If ALL agents fail validation in a round, the tournament aborts.

> **Note:** Section-level validation (checking for "Summary", "High-risk issues", etc.) is optional and implementation-specific. The prompts instruct agents on required format; strict enforcement may cause unnecessary failures.

### Error Handling

When an agent fails during a round (crash, timeout, or invalid output), the orchestrator uses the **retry** strategy:

| Behavior | Description |
|----------|-------------|
| **Exclude from current round** | The failed agent's output is not included in `all_reviews.md` |
| **Retry in subsequent rounds** | The agent still participates in all subsequent rounds |
| **Log error to console** | Failure details are printed to stderr for visibility |

**Rationale:** Transient failures (API timeouts, rate limits) should not permanently disqualify an agent. This maintains review diversity across all rounds and is more resilient to flaky external LLM APIs.

**Failure Types:**

| Failure | Detection | Logged Message |
|---------|-----------|----------------|
| Process crash | Non-zero exit code or unexpected termination | `[ERROR] Agent '<name>' crashed in round <N>: <exit_code/signal>` |
| Timeout | Exceeds `agent-timeout-ms` | `[ERROR] Agent '<name>' timed out in round <N> after <ms>ms` |
| Invalid output | Missing or empty `review.md` | `[ERROR] Agent '<name>' produced invalid output in round <N>: <reason>` |

**Example console output:**

```
[ERROR] Agent 'codex' crashed in round 1: exit code 1
[INFO] Starting round 2/5 with agents: [claude, codex, gemini]
```

The tournament continues with all agents in subsequent rounds. This ensures transient failures do not permanently exclude an agent from the review process.

### Minimum Agent Threshold

The arena requires a minimum number of agents to maintain meaningful cross-pollination:

**Configuration:**

```yaml
tournament:
  min-agents: 1    # Minimum agents required (default: 1)
```

**Behavior:**

| Condition | Action |
|-----------|--------|
| Agents drop below `min-agents` | Tournament aborts with exit code 4 |
| Single agent remains (if min-agents > 1) | Tournament aborts—no cross-pollination possible |
| All agents fail in Round 0 | Tournament aborts with exit code 4 |

`min-agents: 1` allows single-agent continuation (useful when only Claude is enabled), though cross-pollination benefits require 2+ agents.

**Rationale:** The default of 1 supports the common case where only Claude is installed and enabled. Cross-pollination is most valuable with 2+ agents, but a single-agent tournament still produces useful reviews through iterative self-refinement.

**Example console output:**

```
[ERROR] Agent 'codex' crashed in round 0: exit code 1
[ERROR] Agent 'gemini' timed out in round 0 after 600000ms
[WARN] Only 1 agent remaining. Cross-pollination benefits are reduced.
```

> **Note:** Set `min-agents: 2` to enforce cross-pollination — the tournament will abort if fewer than 2 agents are available.

## Arena Filesystem

Since agents are local and tool-enabled, the **filesystem becomes the shared communication layer** - not tokens.

### Directory Structure

The following structure is used for **code review tournaments** (the primary use case for this arena):

```
project-root/                    # Agents run here (working directory)
├── .arena/
│   ├── prompts/
│   │   ├── task.md               # Task definition, rubric, git range
│   │   ├── round-0-<agent>.md    # Pre-generated round prompts per agent
│   │   └── round-N-<agent>.md
│   ├── rounds/
│   │   ├── round-0/
│   │   │   ├── claude-1/
│   │   │   │   └── review.md
│   │   │   ├── codex-1/
│   │   │   │   └── review.md
│   │   │   └── all_reviews.md
│   │   ├── round-1/
│   │   │   └── ...
│   │   └── final/
│   │       ├── prompt.md
│   │       └── champion_review.md # Synthesized final review
└── <project files>              # Full source tree accessible to agents
```

> **Note:** For other tournament types (e.g., code generation), the output file would change accordingly (e.g., `solution.md`). The structure remains the same.

> See [Code Review Tournament Flow](#code-review-tournament-flow) for the complete structure including combined review files and final outputs.

### Agent Working Directory

Agents are spawned with their working directory set to the **project root** (the directory containing `.arena/`). This gives agents full access to:

- The complete source tree
- Git repository (history, branches, diffs)
- Build tools, test runners, linters
- Any project tooling

Agents write their review output to the path specified in the prompt (e.g., `.arena/rounds/round-0/claude-1/review.md`), but operate from the project root to leverage their full capabilities.

**Why not isolate agents?** The power of tool-enabled CLI agents comes from their ability to explore, run tests, and investigate. Sandboxing them to a subdirectory would severely limit their effectiveness.

### File Access and Paths

Agents access all arena files using **relative paths from the project root**:

| Resource | Path from project root |
|----------|------------------------|
| Task definition | `.arena/prompts/task.md` |
| Own output | `.arena/rounds/round-N/<agent>/review.md` |
| Previous round reviews | `.arena/rounds/round-N/all_reviews.md` |
| Source code | Direct paths (e.g., `src/main/App.java`) |

**Path format:** Prompts reference files using relative paths. Agents may internally resolve to absolute paths if needed, but all prompt-specified paths are relative to the project root.

**Write access:** Agents can write anywhere within the project. The orchestrator specifies output paths in prompts (e.g., "Write your review to `.arena/rounds/round-1/claude-1/review.md`"). Agents are trusted to write only to their designated output locations.

### Agent Capabilities

Each agent operates from the project root and can:

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
| **Target to review** | Git reference specifying what to review (see below) |
| **Review rubric** | Short, explicit criteria: correctness & edge cases, security & privacy, performance, maintainability/design, tests |
| **Output format contract** | Ensures reviews are comparable |

#### Review Target Specification

The review target is specified in `task.md` using one of three formats:

| Target Type | Example | Description |
|-------------|---------|-------------|
| Single commit | `abc1234` | Review changes in one commit |
| Commit range | `abc1234 def5678` | Review changes between two commits |
| Staged changes | `--staged` | Review currently staged changes |

The orchestrator passes these references directly to agents via prompts. Agents use their own git capabilities to examine the changes—the orchestrator does not perform git operations itself.

Agents have full repository access and can explore related code, run tests, or investigate context as needed.

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
project-root/                           # Agents run here (working directory)
├── .arena/
│   ├── prompts/
│   │   ├── task.md                     # Task definition, rubric, git range
│   │   ├── round-0-<agent>.md          # Pre-generated round prompts per agent
│   │   └── round-N-<agent>.md
│   ├── rounds/
│   │   ├── round-0/
│   │   │   ├── claude-1/
│   │   │   │   └── review.md
│   │   │   ├── codex-1/
│   │   │   │   └── review.md
│   │   │   └── all_reviews.md          # Combined reviews from all agents
│   │   ├── round-1/
│   │   │   └── ...
│   │   └── final/
│   │       ├── prompt.md
│   │       └── champion_review.md      # Synthesized final review
└── <project files>                     # Full source tree accessible to agents
```

### 2) Round 0 – Independent Reviews

For each agent (fresh process):

1. Start agent in the **project root** directory
2. Feed it:
   - The rubric + `task.md` (contains git range to review)
   - Output path: `.arena/rounds/round-0/<agent>/review.md` (e.g., `claude-1`, `claude-2`)
3. Agent explores code, runs `git diff`, investigates as needed
4. Agent writes review to specified output path
5. Stop the agent process (ephemeral processes = clean state)

**Result:** Independent reviews with no cross-contamination (e.g., 3 reviews from `claude-1`, `claude-2`, `claude-3`).

### 3) Build the Combined Submission

To avoid pairwise sharing overhead, assemble a single combined file per round.

#### `all_reviews.md` Lifecycle

The orchestrator creates `all_reviews.md` at the **end of each round**:

```
Round 0 ends → orchestrator creates round-0/all_reviews.md
Round 1 starts → agents read round-0/all_reviews.md
Round 1 ends → orchestrator creates round-1/all_reviews.md
Round 2 starts → agents read round-1/all_reviews.md
...
```

| Round | Reads from | Writes to |
|-------|------------|-----------|
| Round 0 | (none) | `round-0/all_reviews.md` |
| Round 1 | `round-0/all_reviews.md` | `round-1/all_reviews.md` |
| Round N | `round-(N-1)/all_reviews.md` | `round-N/all_reviews.md` |

#### `all_reviews.md` Format

The file contains **only the reviews**, with clear H1 headings to separate each agent's contribution:

```markdown
# Claude

[Full content of claude/review.md]

# Codex

[Full content of codex/review.md]

# Gemini

[Full content of gemini/review.md]
```

**Key points:**
- Each agent's review starts with a prominent H1 heading (`# AgentName`)
- No additional metadata (task content, round number) is included—agents already have access to `task.md`
- The heading makes it unambiguous where each agent's review begins and ends

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

Repeat for a fixed number of rounds:

1. Merge round-(k) reviews into `round-k/all_reviews.md`
2. Each agent produces an improved review that:
   - Removes weak/incorrect comments
   - Adds missed issues
   - Tightens prioritization
   - Adds actionable patches/tests

**Stop when:** Fixed round limit reached (configurable via `rounds`, see [Resource Limits](#resource-limits))

### 6) Final Output

After all rounds complete, the arena produces:

| File | Purpose |
|------|---------|
| `round-N/all_reviews.md` | Combined final reviews from all agents |
| `round-N/<agent>/review.md` | Individual agent reviews from final round |
| `final/champion_review.md` | Synthesized final review (see next step) |

The human reviewer can examine individual agent reviews or the combined file before the synthesizer runs.

### 7) Synthesizer Final Step

After the last round, **Claude** runs one more agent process in the dedicated synthesizer role. Claude is always used for this step regardless of which agents participated in the tournament rounds.

- **Agent:** Claude CLI (always)
- **Input:** The final `all_reviews.md`
- **Output:** `final/champion_review.md` that merges duplicated comments and produces one clean review (keeping citations to evidence)

**Rationale:** Using a single, consistent agent for synthesis ensures deterministic output format and avoids ambiguity about which agent produces the final deliverable.

### 8) Key Behaviors (The Tournament Method)

| Behavior | Description |
|----------|-------------|
| **Same initial prompt** | All agents receive identical starting prompt |
| **Cross-pollination** | Each round, agents see competitors' outputs and improve |
| **Avoid combinatorial blow-up** | One combined markdown file per round (not pairwise) |
| **Ephemeral processes** | Fresh process each round for strict isolation (mandatory) |

## Agent Prompt Templates

### Prompt Storage Location

All prompt templates are stored as Markdown files in the `resources/prompts/` directory:

```
resources/prompts/
├── task.md              # Global invariants (review rubric, constraints, output contract)
├── round-0.md           # Round 0 - Independent review prompt
├── round-1.md           # Round 1 - Review of reviews prompt
├── round-2.md           # Round 2 - Precision and evidence prompt
├── round-3.md           # Round 3 - Refinement prompt
├── round-4.md           # Round 4 - Consolidation prompt
├── round-5.md           # Round 5 - Final convergence prompt
└── final-synth.md       # Final synthesizer prompt
```

**Rationale:**
- Markdown format allows rich formatting and is human-readable
- Centralized location makes prompts easy to find and modify
- Prompts can be version-controlled and reviewed like any other code
- Enables future extensibility (e.g., custom prompt sets for different review types)

At runtime, the orchestrator loads these templates and injects them into agents. The templates may contain placeholders (e.g., `{{round_number}}`, `{{agent_name}}`) that are substituted at runtime.

### Prompt Content

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

**Prompt:** `resources/prompts/round-0.md`

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

**Prompt:** `resources/prompts/round-1.md`

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

**Prompt:** `resources/prompts/round-2.md`

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

### Round 3 — Refinement

**Purpose:** Tighten prioritization and remove weak claims.

**Prompt:** `resources/prompts/round-3.md`

```
This is Round 3.

Reviews are converging. Your task is to refine further:
- Remove any remaining vague or speculative comments
- Strengthen evidence for each issue
- Ensure severity levels are calibrated correctly
- Consolidate duplicate issues into single, authoritative entries

Focus on:
- Actionable feedback the author can implement immediately
- Clear file/line references for every issue
- Realistic fix suggestions

Write a refined review to `review.md`.
```

### Round 4 — Consolidation

**Purpose:** Merge remaining duplicates and finalize prioritization.

**Prompt:** `resources/prompts/round-4.md`

```
This is Round 4.

Reviews are nearly converged. Your task:
- Identify and merge any remaining duplicate issues
- Finalize severity levels (critical vs medium vs low)
- Ensure each issue has concrete evidence and a clear fix
- Remove any low-value nitpicks that distract from real issues

Produce a clean, consolidated review.

Write to `review.md`.
```

### Round 5 — Final Convergence

**Purpose:** Produce near-identical, very high-quality reviews.

**Prompt:** `resources/prompts/round-5.md`

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

### Final Synthesizer Round

**Purpose:** Produce one canonical review from multiple high-quality finals.

**Prompt:** `resources/prompts/final-synth.md`

```
You are the final synthesizer in a code review arena.

## Tournament Summary
- **Rounds completed:** ${roundCount} (Round 0 + ${crossPollinationRounds} cross-pollination rounds)
- **Participating agents:** ${participatingAgents}

## Input
You are given multiple high-quality final reviews from: `${allReviewsPath}`

## Your Task
- Merge them into one single, cohesive review.
- Remove duplicates.
- Resolve conflicting recommendations.
- Keep the strongest phrasing and evidence.

## Rules
- Do NOT introduce new issues.
- Do NOT speculate.
- Do NOT reference the original reviewers by name.

## Output
Produce one final review in `${outputPath}` using the standard review structure:

### Summary
### High-risk issues (must fix)
### Medium / low-risk issues
### Suggested patches (diff snippets or pseudocode)
### Test suggestions
### Questions for the author
```

**Note:** The synthesis prompt is persisted to `.arena/rounds/final/prompt.md` for debugging and reproducibility.

### Why This Works

| Property | Description |
|----------|-------------|
| Independent first pass | Diverse initial perspectives without contamination |
| Iterative cross-review | Each round builds on competitors' insights |
| Shared combined file | One `all_reviews.md` per round avoids pairwise explosion |
| Fresh context each round | New process = clean slate |
| Convergence over debate | Prompts drive toward agreement, not argument |
| Filesystem-grounded | Real files, real diffs, real CLI agents |

## Implementation Decisions

The following decisions supplement the specification with concrete implementation choices.

### Technology Stack

| Decision | Choice |
|----------|--------|
| Language | Java |
| Build System | Maven |
| Minimum Version | Java 21 LTS |
| Configuration | SmallRye Config (MicroProfile Config) |

**Rationale:** Java 21 LTS provides virtual threads for efficient concurrent agent execution, pattern matching for cleaner code, and long-term support stability.

### Configuration Management

The project uses **SmallRye Config** (MicroProfile Config reference implementation) for type-safe configuration injection:

```java
@ConfigProperty(name = "limits.rounds", defaultValue = "5")
int rounds;
```

**Key capabilities:**
- `@ConfigProperty` annotation for type-safe YAML property injection
- Hierarchical property names (e.g., `limits.rounds`, `timeouts.agent-timeout-ms`)
- Multiple config sources: `application.yaml`, environment variables, system properties
- Default values when configuration is missing

See [Implementation Decisions](implementation-decisions.md#configuration-with-microprofile-config) for detailed usage patterns and Maven dependencies.

### task.md Generation

The `task.md` file is stored at `.arena/prompts/task.md` and contains the **global invariants** (rubric, constraints, output contract). It is copied as-is during workspace initialization. It does not contain placeholders—review target information is passed via round-specific prompts.

### Round Prompt Placeholders

Round prompts (`round-0.md`, `round-1.md`, etc.) are templated with placeholders that are substituted at prompt generation time:

**Available placeholders:**

| Placeholder | Description | Used In |
|-------------|-------------|---------|
| `${roundNumber}` | Current round (0-indexed) | Round prompts |
| `${outputPath}` | Path where agent should write output | Round prompts |
| `${allReviewsPath}` | Path to combined reviews from previous round (null for round 0) | Round 1+ prompts |
| `${commit1}` | First commit hash (empty if `--staged`) | Round prompts |
| `${commit2}` | Second commit hash for ranges (empty for single commit or `--staged`) | Round prompts |
| `${stagedFlag}` | The staged flag value (`--staged` or empty) | Round prompts |

> **Note:** Placeholders use FreeMarker syntax (`${name}`) and are substituted at prompt generation time.

### Prompt Construction

Prompts are **pre-generated at initialization time** and stored in `.arena/prompts/`. The prompt sent to agents is constructed by **concatenation**:

```
[Contents of task.md with placeholders resolved]

---

[Contents of round-N.md prompt]
```

The orchestrator:
1. Pre-generates all round prompts at workspace initialization and stores them in `.arena/prompts/`
2. For rounds > 0, regenerates prompts with embedded previous review content
3. Pipes prompt content to agent stdin

### Directory Creation

The **orchestrator pre-creates all directories** before spawning agents:

```
.arena/
├── prompts/
│   ├── task.md                      # Created and populated by orchestrator
│   ├── round-0-claude-1.md          # Pre-generated prompt for claude-1 round 0
│   ├── round-0-claude-2.md          # Pre-generated prompt for claude-2 round 0
│   └── ...
├── rounds/
│   ├── round-0/
│   │   ├── claude-1/                # Pre-created by orchestrator
│   │   ├── claude-2/                # Pre-created by orchestrator
│   │   └── claude-3/                # Pre-created by orchestrator
│   └── ...
```

Agents write directly to their designated output path without needing to create directories.

### Synthesizer Requirement

**Claude is required** for the final synthesis step, configured as the `synthesis` agent. The orchestrator fails with exit code 4 in these scenarios:

| Scenario | Error Message |
|----------|---------------|
| Synthesis agent not configured | `"Final synthesis requires Claude CLI. Ensure 'synthesis' agent is configured in arena.yaml."` |
| Synthesis prompt generation fails | `"Failed to generate synthesis prompt: <reason>"` |
| Synthesis execution fails | `"Synthesis failed: <reason>"` |

This is a hard requirement, not a soft fallback. The synthesizer role is critical to producing the final `champion_review.md`.

**Edge cases:**
- If Claude is the only surviving agent type, synthesis still runs (Claude synthesizes its own review)
- The `synthesis` agent is separate from the `claude` type template used by `review-agents`
- The `enabled` flag on `synthesis` only controls tournament participation, not synthesis availability

### State Recovery

**No state recovery** - each tournament run starts fresh:

- The orchestrator clears/recreates `.arena/` directory on startup
- No checkpoint files or resume capability
- If interrupted, the user must restart the tournament

**Rationale:** Simpler implementation, deterministic behavior, avoids stale state issues. Users can preserve outputs by copying `.arena/` before re-running.
