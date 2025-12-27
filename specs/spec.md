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
├── prompt_file       (prompt injection via file reference)
├── stdout            (captured logs + responses)
├── lifecycle         (start / stop / restart)
```

### Agent Configuration

Agents are defined via YAML configuration:

```yaml
agents:
  claude:
    command: ["claude", "-p", "@prompt.txt"]

  codex:
    command: ["codex", "exec", "@prompt.txt"]

  gemini:
    command: ["gemini", "-p", "@prompt.txt"]
```

> **Note:** Prompts are passed via file reference (`@prompt.txt`) for robustness with large or complex prompts. The orchestrator writes the prompt to a temporary file before invoking each agent.

> **Note:** Agent working directories are set dynamically per round (see [Arena Filesystem](#arena-filesystem)).

### Design Benefits

This abstraction keeps the arena:

- **Model-agnostic** - No coupling to specific LLM providers
- **Future-proof** - New CLI agents can be added via configuration
- **Extensible** - Compatible with any CLI agent, including custom MCP-based ones

## Agent Execution Model

### Ephemeral Agents (Mandatory)

The arena **requires ephemeral agents** - stateless, short-lived processes that are created fresh for each round. This ensures a clean context window every time:

1. **Start** agent process
2. **Feed** it a prompt (via file reference: `@prompt.txt`)
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
  max_concurrent: 0    # 0 = unlimited parallel, 1 = sequential, N = max N agents at once
```

> **Note:** Higher concurrency increases resource usage (memory, API rate limits). Use bounded or sequential execution when running agents that share rate-limited backends.

### Resource Limits

The arena enforces limits on agent outputs to prevent runaway processes:

**Configuration:**

```yaml
limits:
  max_output_size_kb: 500    # Maximum size per output file (e.g., review.md)
  max_rounds: 5              # Number of cross-pollination rounds after Round 0 (default: 5)
```

#### Round Counting (0-indexed)

Rounds are **always 0-indexed**, both internally and in user-facing output:

| Round | Type | Description |
|-------|------|-------------|
| Round 0 | Independent | Each agent reviews code independently, no cross-pollination |
| Round 1-N | Cross-pollination | Agents see all previous reviews and improve |

**`max_rounds` semantics:**
- `max_rounds: 5` means 5 cross-pollination rounds (Rounds 1-5) after Round 0
- Total rounds executed = Round 0 + `max_rounds` cross-pollination rounds
- Example: `max_rounds: 5` → Rounds 0, 1, 2, 3, 4, 5 (6 total rounds)

Progress output uses 0-indexed display: `Round 0/5`, `Round 1/5`, ..., `Round 5/5`

These are **spec-level limits** that the orchestrator enforces. OS-level resource controls (memory, CPU, disk quotas) are deployment-specific and outside this spec's scope—use containerization or OS process limits for production deployments.

### Process Timeouts

The arena enforces time limits on agent processes to prevent indefinite hangs:

**Configuration:**

```yaml
timeouts:
  agent_timeout_ms: 300000      # Per-agent process timeout (default: 5 minutes)
  round_timeout_ms: 900000      # Per-round timeout (default: 15 minutes)
  grace_period_ms: 5000         # Graceful shutdown window before SIGKILL

  per_agent:                    # Optional per-agent overrides
    claude: 600000              # 10 minutes (Claude tends to be thorough)
    codex: 300000
    gemini: 300000

  on_timeout: "kill_and_skip"   # kill_and_skip | kill_and_abort
  preserve_partial_output: false # If true, keep incomplete output with warning
```

> **Default Rationale:** 5 minutes allows thorough review of ~1000 LOC diffs. Complex reviews may need longer; use `per_agent` overrides as needed.

**Timeout Behavior:**

| Timeout Type | Trigger | Action |
|--------------|---------|--------|
| Agent timeout | Single agent exceeds `agent_timeout_ms` | SIGTERM → wait `grace_period_ms` → SIGKILL, exclude from round |
| Round timeout | Round exceeds `round_timeout_ms` | Kill all running agents, proceed with completed outputs |

**Timeout Actions:**

| Action | Behavior |
|--------|----------|
| `kill_and_skip` | Terminate agent, exclude from round, continue tournament |
| `kill_and_abort` | Terminate agent, abort entire tournament |

Partial output from timed-out agents is discarded by default. Set `preserve_partial_output: true` to keep incomplete reviews (marked with a `[TIMEOUT: incomplete]` warning header).

### Output Validation

The orchestrator validates agent output after each round:

| Check | Behavior on Failure |
|-------|---------------------|
| Output file exists (`review.md`) | Agent excluded from round, warning logged |
| File is non-empty | Agent excluded from round, warning logged |
| File within size limit | Truncated to limit, warning logged |
| Agent completes within timeout | Agent killed, excluded from round, warning logged |

**Configuration:**

```yaml
validation:
  required_file: "review.md"
  on_missing: "skip"    # skip = exclude agent from round, abort = stop tournament
```

The tournament continues with remaining agents. If ALL agents fail validation in a round, the tournament aborts.

> **Note:** Section-level validation (checking for "Summary", "High-risk issues", etc.) is optional and implementation-specific. The prompts instruct agents on required format; strict enforcement may cause unnecessary failures.

### Error Handling

When an agent fails during a round (crash, timeout, or invalid output), the orchestrator uses the **skip** strategy:

| Behavior | Description |
|----------|-------------|
| **Exclude from current round** | The failed agent's output is not included in `all_reviews.md` |
| **Exclude from subsequent rounds** | The agent is removed from the tournament entirely |
| **Log error to console** | Failure details are printed to stderr for visibility |

**Failure Types:**

| Failure | Detection | Logged Message |
|---------|-----------|----------------|
| Process crash | Non-zero exit code or unexpected termination | `[ERROR] Agent '<name>' crashed in round <N>: <exit_code/signal>` |
| Timeout | Exceeds `agent_timeout_ms` | `[ERROR] Agent '<name>' timed out in round <N> after <ms>ms` |
| Invalid output | Missing or empty `review.md` | `[ERROR] Agent '<name>' produced invalid output in round <N>: <reason>` |

**Example console output:**

```
[ERROR] Agent 'codex' crashed in round 1: exit code 1
[INFO] Excluding 'codex' from remaining rounds. Continuing with: claude, gemini
```

The tournament continues with the remaining agents. This ensures a single flaky agent does not block the entire review process.

## Arena Filesystem

Since agents are local and tool-enabled, the **filesystem becomes the shared communication layer** - not tokens.

### Directory Structure

The following structure is used for **code review tournaments** (the primary use case for this arena):

```
project-root/                    # Agents run here (working directory)
├── .arena/
│   ├── task.md                  # Task definition, rubric, git range to review
│   ├── rounds/
│   │   ├── round-0/
│   │   │   ├── claude/
│   │   │   │   └── review.md
│   │   │   ├── codex/
│   │   │   │   └── review.md
│   │   │   └── gemini/
│   │   │       └── review.md
│   │   ├── round-1/
│   │   │   └── ...
│   │   └── final/
│   │       └── champion_review.md # Synthesized final review
│   └── evaluation/
│       └── summary.md
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

Agents write their review output to the path specified in the prompt (e.g., `.arena/rounds/round-0/claude/review.md`), but operate from the project root to leverage their full capabilities.

**Why not isolate agents?** The power of tool-enabled CLI agents comes from their ability to explore, run tests, and investigate. Sandboxing them to a subdirectory would severely limit their effectiveness.

### File Access and Paths

Agents access all arena files using **relative paths from the project root**:

| Resource | Path from project root |
|----------|------------------------|
| Task definition | `.arena/task.md` |
| Own output | `.arena/rounds/round-N/<agent>/review.md` |
| Previous round reviews | `.arena/rounds/round-N/all_reviews.md` |
| Source code | Direct paths (e.g., `src/main/App.java`) |

**Path format:** Prompts reference files using relative paths. Agents may internally resolve to absolute paths if needed, but all prompt-specified paths are relative to the project root.

**Write access:** Agents can write anywhere within the project. The orchestrator specifies output paths in prompts (e.g., "Write your review to `.arena/rounds/round-1/claude/review.md`"). Agents are trusted to write only to their designated output locations.

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

The review target is specified as a git reference in `task.md`. Agents use git commands to examine the changes directly:

| Target Type | Example | Description |
|-------------|---------|-------------|
| Commit range | `HEAD~3..HEAD` | Review last 3 commits |
| Branch comparison | `main..feature-x` | Review branch changes |
| Staged changes | `--staged` | Review currently staged files |
| Single commit | `abc1234` | Review specific commit |
| PR reference | `origin/main..HEAD` | Review PR-style diff |

Agents have full repository access and can explore related code, run tests, or investigate context as needed. No separate `target/` directory is required—agents work directly with the git repository.

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
│   ├── task.md                         # Instructions + rubric + git range to review
│   ├── rounds/
│   │   ├── round-0/
│   │   │   ├── claude/
│   │   │   │   └── review.md
│   │   │   ├── codex/
│   │   │   │   └── review.md
│   │   │   ├── gemini/
│   │   │   │   └── review.md
│   │   │   └── all_reviews.md          # Combined reviews from all agents
│   │   ├── round-1/
│   │   │   └── ...
│   │   └── final/
│   │       ├── side_by_side.md
│   │       ├── issue_matrix.md
│   │       ├── suggested_patches/
│   │       ├── questions.md
│   │       └── champion_review.md
│   └── evaluation/
│       └── summary.md                  # Tournament metrics and analysis
└── <project files>                     # Full source tree accessible to agents
```

### 2) Round 0 – Independent Reviews

For each agent (fresh process):

1. Start agent in the **project root** directory
2. Feed it:
   - The rubric + `task.md` (contains git range to review)
   - Output path: `.arena/rounds/round-0/<agent>/review.md`
3. Agent explores code, runs `git diff`, investigates as needed
4. Agent writes review to specified output path
5. Stop the agent process (ephemeral processes = clean state)

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

Repeat for a fixed number of rounds:

1. Merge round-(k) reviews into `round-k/all_reviews.md`
2. Each agent produces an improved review that:
   - Removes weak/incorrect comments
   - Adds missed issues
   - Tightens prioritization
   - Adds actionable patches/tests

**Stop when:** Fixed round limit reached (configurable via `max_rounds`, see [Resource Limits](#resource-limits))

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
├── round-3.md           # Round 3 - Final convergence prompt (optional)
└── final-synth.md       # Final synthesizer prompt (optional)
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

### Round 3 — Final Convergence (Optional)

**Purpose:** Produce near-identical, very high-quality reviews.

**Prompt:** `resources/prompts/round-3.md`

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

**Prompt:** `resources/prompts/final-synth.md`

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
