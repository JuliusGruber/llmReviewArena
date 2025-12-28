# Next Milestones

This document outlines the next development priorities based on analysis of recent commits and current implementation state.

## Current Implementation State

### Implemented ✅

| Component | Files | Status |
|-----------|-------|--------|
| **CLI Layer (picocli)** | `ReviewArenaCli.java`, `CommitHashConverter.java`, `ExceptionHandler.java` | Complete |
| **Exception Classes** | `GitValidationException.java`, `ConfigException.java`, `AgentException.java` | Complete |
| **Dry-run Mode** | Integrated in `ReviewArenaCli.java` | Complete |
| **CLI Tests** | `CommitHashConverterTest.java`, `ReviewArenaCliTest.java`, `ReviewArenaCliIT.java` | Complete |
| **Maven Project** | `pom.xml` with all dependencies, fat JAR config | Complete |

### Not Implemented ❌

| Component | Planned In | Status |
|-----------|-----------|--------|
| **GitService (JGit)** | startup-validation-plan.md | Not started |
| **InputValidator** | startup-validation-plan.md | Not started |
| **ArenaConfig** | implementation-decisions.md | Not started |
| **ConfigLoader** | implementation-decisions.md | Not started |
| **WorkspaceManager** | spec.md | Not started |
| **Prompt Templates** | spec.md | Not created |
| **application.yaml** | implementation-decisions.md | Not created |
| **logback.xml** | implementation-decisions.md | Not created |
| **AgentProcess/Executor** | spec.md | Not started |
| **Tournament Orchestrator** | spec.md | Not started |

---

## Milestone 1: Git Validation + Configuration + Workspace Setup

**Goal:** Complete the foundation layer so the CLI can validate inputs, load configuration, and prepare the workspace before agent execution.

### 1.1 GitService + Startup Validation

Implements the startup validation flow from `startup-validation-plan.md`.

```
src/main/java/dev/reviewarena/git/
├── GitService.java              # JGit operations (new)
├── GitValidationException.java  # Already exists
└── InputValidator.java          # Argument validation (new)
```

**GitService responsibilities:**
- Open and validate git repository
- Validate commit hashes exist (full or abbreviated)
- Validate ancestry for commit ranges

**InputValidator responsibilities:**
- Mutual exclusivity check (`--staged` vs commits)
- Input presence check (at least one required)
- Hash format validation (7-40 hex chars)

**Integration point:** Called from `ReviewArenaCli.call()` before any other work.

### 1.2 Configuration Layer

Implements SmallRye Config integration from `implementation-decisions.md`.

```
src/main/java/dev/reviewarena/config/
├── ArenaConfig.java         # Record with all config fields (new)
├── ConfigLoader.java        # SmallRye Config integration (new)
└── ConfigException.java     # Already exists

src/main/resources/
├── application.yaml         # Default configuration (new)
└── logback.xml              # Logging configuration (new)
```

**ArenaConfig record fields:**
```java
public record ArenaConfig(
    int maxRounds,
    int maxOutputSizeKb,
    int maxConcurrent,
    long agentTimeoutMs,
    long roundTimeoutMs,
    long gracePeriodMs,
    String onTimeout,
    boolean preservePartialOutput,
    int minAgents,
    Map<String, AgentConfig> agents
) {}
```

**ConfigLoader responsibilities:**
- Load `arena.yaml` from current directory (if exists)
- Fall back to `application.yaml` defaults
- Merge CLI overrides (rounds, output dir, concurrency)
- Return fully-resolved `ArenaConfig`

### 1.3 Workspace Setup

Implements the `.arena/` directory structure from `spec.md`.

```
src/main/java/dev/reviewarena/io/
├── WorkspaceManager.java    # Creates .arena/ structure (new)
├── TemplateLoader.java      # Loads prompt templates (new)
└── ReviewAggregator.java    # Writes all_reviews.md (new)
```

**WorkspaceManager responsibilities:**
- Clear existing `.arena/` directory (fresh start)
- Create directory structure:
  ```
  .arena/
  ├── task.md
  └── rounds/
      ├── round-0/
      │   ├── claude/
      │   ├── codex/
      │   └── gemini/
      ├── round-1/
      │   └── ...
      └── final/
  ```
- Generate `task.md` from template with placeholder substitution

**TemplateLoader responsibilities:**
- Load prompt templates from classpath (`resources/prompts/`)
- Resolve placeholders (`{{review_target}}`, `{{round_number}}`, etc.)
- Return constructed prompt content

### 1.4 Prompt Templates

Create the prompt templates specified in `spec.md`.

```
src/main/resources/prompts/
├── task.md              # Global invariants (rubric, constraints, output contract)
├── round-0.md           # Round 0 - Independent review
├── round-1.md           # Round 1 - Review of reviews
├── round-2.md           # Round 2 - Precision and evidence
├── round-3.md           # Round 3 - Refinement
├── round-4.md           # Round 4 - Consolidation
├── round-5.md           # Round 5 - Final convergence
└── final-synth.md       # Final synthesizer
```

Content is defined in `spec.md` section "Agent Prompt Templates".

---

## Milestone 2: Agent Process Layer

**Goal:** Implement the agent execution model with process management.

```
src/main/java/dev/reviewarena/agent/
├── AgentProcess.java        # Process wrapper with lifecycle
├── AgentExecutor.java       # Runs agents with concurrency control
├── AgentConfig.java         # Agent-specific configuration
├── AgentResult.java         # Execution result record
└── AgentException.java      # Already exists
```

**Key features:**
- ProcessBuilder-based agent spawning
- Virtual threads for lightweight concurrency
- Semaphore for `max-concurrent` enforcement
- Timeout handling with graceful termination
- stdout/stderr capture to log files

---

## Milestone 3: Tournament Orchestrator

**Goal:** Implement the multi-round tournament flow.

```
src/main/java/dev/reviewarena/tournament/
├── TournamentOrchestrator.java  # Main orchestration logic
├── RoundExecutor.java           # Executes a single round
├── RoundResult.java             # Round outcome record
└── TournamentResult.java        # Final tournament outcome
```

**Key features:**
- Round 0: Independent reviews (parallel)
- Rounds 1-N: Cross-pollination with `all_reviews.md`
- Final synthesizer step (Claude only)
- Error handling (skip failed agents, abort if below threshold)
- Progress output via logger

---

## Implementation Order

| Order | Component | Dependencies |
|-------|-----------|--------------|
| 1 | GitService + InputValidator | None |
| 2 | ArenaConfig + ConfigLoader | None |
| 3 | application.yaml + logback.xml | None |
| 4 | Prompt templates | None |
| 5 | WorkspaceManager | ArenaConfig |
| 6 | TemplateLoader | Prompt templates |
| 7 | ReviewArenaCli integration | GitService, ConfigLoader, WorkspaceManager |
| 8 | AgentProcess + AgentExecutor | WorkspaceManager |
| 9 | TournamentOrchestrator | AgentExecutor, TemplateLoader |
| 10 | ReviewAggregator | TournamentOrchestrator |

---

## Testing Strategy

### Milestone 1 Tests

**GitService tests:**
- `testIsInsideGitRepo_success`
- `testIsInsideGitRepo_notARepo`
- `testValidateCommitExists_fullHash`
- `testValidateCommitExists_abbreviatedHash`
- `testValidateCommitExists_notFound`
- `testValidateAncestry_valid`
- `testValidateAncestry_invalid`

**InputValidator tests:**
- `testMutualExclusivity_stagedAndCommit_throws`
- `testMutualExclusivity_stagedOnly_passes`
- `testHashFormat_valid`
- `testHashFormat_invalid`

**ConfigLoader tests:**
- `testLoadsDefaultConfig`
- `testLoadsArenaYaml`
- `testCliOverridesConfig`
- `testMissingConfigUsesDefaults`

**WorkspaceManager tests:**
- `testCreatesDirectoryStructure`
- `testClearsExistingArenaDir`
- `testGeneratesTaskMd`

### Milestone 2 Tests

**AgentExecutor tests:**
- Mock agent scripts that write canned `review.md` files
- Timeout behavior verification
- Concurrent execution with semaphore

### Milestone 3 Tests

**TournamentOrchestrator tests:**
- Full tournament flow with mock agents
- Error handling (agent failure, threshold)
- `all_reviews.md` aggregation

---

## Success Criteria

### Milestone 1 Complete When:
- [ ] `review-arena abc1234` validates commit exists
- [ ] `review-arena --staged` validates staged mode
- [ ] Invalid commits produce exit code 3
- [ ] `arena.yaml` is loaded and merged with CLI args
- [ ] `.arena/` directory structure is created
- [ ] `task.md` is generated with placeholders resolved
- [ ] All prompt templates exist in resources

### Milestone 2 Complete When:
- [ ] Agents spawn as subprocesses
- [ ] Agent output captured to `review.md`
- [ ] Timeouts terminate agents gracefully
- [ ] Concurrency respects `max-concurrent`

### Milestone 3 Complete When:
- [ ] Full tournament runs with real CLI agents
- [ ] Cross-pollination works via `all_reviews.md`
- [ ] Final `champion_review.md` is generated
- [ ] Progress output shows round status
