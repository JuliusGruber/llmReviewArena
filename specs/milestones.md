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
| **GitService (JGit)** | `GitService.java`, `GitServiceTest.java` | Complete |
| **InputValidator** | `InputValidator.java`, `InputValidatorTest.java` | Complete |
| **CLI + GitService Integration** | `ReviewArenaCli.call()` uses GitService | Complete |
| **ArenaConfig** | `ArenaConfig.java`, `AgentConfig.java` | Complete |
| **ConfigLoader** | `ConfigLoader.java`, `ConfigLoaderTest.java` | Complete |
| **application.yaml** | `src/main/resources/application.yaml` | Complete |
| **logback.xml** | `src/main/resources/logback.xml` | Complete |
| **CLI + ConfigLoader Integration** | `ReviewArenaCli.call()` uses ConfigLoader | Complete |
| **Prompt Templates** | `src/main/resources/prompts/*.md` | Complete |
| **WorkspaceManager** | `WorkspaceManager.java`, `WorkspaceManagerTest.java` | Complete |
| **TemplateLoader** | `TemplateLoader.java`, `TemplateContext.java`, `TemplateLoaderTest.java` | Complete |
| **AgentProcess/Executor** | `AgentProcess.java`, `AgentExecutor.java`, tests | Complete |
| **ReviewAggregator** | `ReviewAggregator.java`, `ReviewAggregatorTest.java` | Complete |
| **Round 0 Execution** | Integrated in `ReviewArenaCli.call()` | Complete |

### All Milestones Complete ✅

| Component | Planned In | Status |
|-----------|-----------|--------|
| **Cross-Pollination Loop (Rounds 1-N)** | Milestone 3 | ✅ Complete |
| **Final Synthesis** | Milestone 4 | ✅ Complete |

---

## Milestone 1: Git Validation + Configuration + Workspace Setup ✅ COMPLETE

**Goal:** Complete the foundation layer so the CLI can validate inputs, load configuration, and prepare the workspace before agent execution.

### 1.1 GitService + Startup Validation ✅ COMPLETE

Implements the startup validation flow from `startup-validation-plan.md`.

```
src/main/java/dev/reviewarena/git/
├── GitService.java              # JGit operations ✅
├── GitValidationException.java  # Already exists ✅
└── InputValidator.java          # Argument validation ✅
```

**GitService responsibilities:** ✅
- Open and validate git repository
- Validate commit hashes exist (full or abbreviated)
- Validate ancestry for commit ranges

**InputValidator responsibilities:** ✅
- Mutual exclusivity check (`--staged` vs commits)
- Input presence check (at least one required)
- Hash format validation (7-40 hex chars)

**Integration point:** ✅ Called from `ReviewArenaCli.call()` before any other work.

### 1.2 Configuration Layer ✅ COMPLETE

Implements SmallRye Config integration from `implementation-decisions.md`.

```
src/main/java/dev/reviewarena/config/
├── ArenaConfig.java         # Record with all config fields ✅
├── AgentConfig.java         # Agent-specific configuration ✅
├── ConfigLoader.java        # SmallRye Config integration ✅
└── ConfigException.java     # Already exists ✅

src/main/resources/
├── application.yaml         # Default configuration ✅
└── logback.xml              # Logging configuration ✅
```

**ArenaConfig record fields:** ✅
```java
public record ArenaConfig(
    int maxRounds,
    int maxOutputSizeKb,
    int maxConcurrent,
    boolean showAgentOutput,
    long agentTimeoutMs,
    long roundTimeoutMs,
    long gracePeriodMs,
    int minAgents,
    Path outputDir,
    Map<String, AgentConfig> agents,
    List<String> reviewAgents
) {}
```

**ConfigLoader responsibilities:** ✅
- Load `arena.yaml` from current directory (if exists)
- Fall back to `application.yaml` defaults
- Merge CLI overrides (rounds, output dir, concurrency)
- Return fully-resolved `ArenaConfig`

**Integration point:** ✅ Called from `ReviewArenaCli.call()` after input validation.

### 1.3 Workspace Setup ✅ COMPLETE

Implements the `.arena/` directory structure from `spec.md`.

```
src/main/java/dev/reviewarena/io/
├── WorkspaceManager.java    # Creates .arena/ structure ✅
├── WorkspaceException.java  # Workspace errors ✅
├── TemplateLoader.java      # Loads prompt templates ✅
└── TemplateContext.java     # Template data model ✅
```

**WorkspaceManager responsibilities:** ✅
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
- Generate `task.md` from template with placeholder substitution ✅

**TemplateLoader responsibilities:** ✅
- Load prompt templates from classpath (`resources/prompts/`)
- Resolve placeholders using Freemarker (`${reviewTarget}`, `${roundNumber}`, etc.)
- Return constructed prompt content

### 1.4 Prompt Templates ✅ COMPLETE

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

## Milestone 2: Agent Process Layer ✅ COMPLETE

**Goal:** Implement the agent execution model with process management.

```
src/main/java/dev/reviewarena/agent/
├── AgentProcess.java        # Process wrapper with lifecycle ✅
├── AgentExecutor.java       # Runs agents with concurrency control ✅
├── CommandBuilder.java      # Builds agent commands ✅
├── OutputValidator.java     # Validates agent output ✅
├── AgentResult.java         # Execution result record ✅
├── ReviewAggregator.java    # Aggregates reviews ✅
└── AgentException.java      # Already exists ✅
```

**Key features:**
- ProcessBuilder-based agent spawning ✅
- Virtual threads for lightweight concurrency ✅
- Semaphore for `max-concurrent` enforcement ✅
- Timeout handling with graceful termination ✅
- stdout/stderr capture to log files ✅
- Round 0 execution integrated into CLI ✅

---

## Milestone 3: Cross-Pollination Rounds ✅ COMPLETE

**Goal:** Implement the multi-round tournament flow (rounds 1-N).

**Plan document:** `specs/pollination-impl-plan.md`

**Key features:**
- Cross-pollination loop (rounds 1 through maxRounds)
- Failed agent tracking across rounds
- Dynamic agent filtering per round
- Minimum threshold check after each round
- Config validation for maxRounds >= 1
- Round-level timeout enforcement (`roundTimeoutMs`)
- Grace period handling before force-kill (`gracePeriodMs`)

**Files to modify:**
- `ConfigLoader.java` - Add validation
- `AgentExecutor.java` - Add filtered executeRound overload, round-level timeout, grace period
- `AgentProcess.java` - Add terminate/forceKill methods if needed
- `ReviewAggregator.java` - Verify filtering
- `ReviewArenaCli.java` - Implement loop

---

## Milestone 4: Final Synthesis ✅ COMPLETE

**Goal:** Implement the final synthesis step to produce `champion_review.md`.

**Plan document:** `specs/synthesis-impl-plan.md`

**Key features:**
- Validate synthesizer agent availability (Claude required, no fallback)
- Generate synthesizer prompt at runtime with tournament metadata
- Write prompt to `.arena/rounds/final/prompt.md` for audit/debugging
- Execute synthesizer agent with `final-synth.md` template
- Output `champion_review.md` to `.arena/rounds/final/`

**Design decisions:**
- Agent startup validation deferred (GitHub issue to be created)
- Claude is **required** for synthesis (fail with exit code 4 if unavailable)
- Include round count and participating agents in prompt
- Prompt persisted for debugging/reproducibility

**Files to add/modify:**
- `final-synth.md` - Add tournament metadata placeholders
- `SynthesisContext.java` - Synthesis template data model
- `SynthesisResult.java` - Synthesis execution result record
- `WorkspaceManager.java` - Add `generateSynthesisPrompt()`, `getChampionReviewPath()`
- `AgentExecutor.java` - Add `selectSynthesizerAgent()`, `executeSynthesis()`
- `ReviewArenaCli.java` - Add synthesis step after cross-pollination loop

**Success criteria:**
- [x] `final-synth.md` template includes tournament metadata
- [x] `SynthesisContext` record creates valid context
- [x] Synthesis prompt written to `.arena/rounds/final/prompt.md`
- [x] Synthesizer agent validated (Claude required, fail if unavailable)
- [x] `champion_review.md` created in `.arena/rounds/final/`
- [x] Dry-run shows complete tournament flow including synthesis
- [x] Exit code 4 with [SYNTHESIS] prefix on synthesis failures

---

## Implementation Order

| Order | Component | Dependencies | Status |
|-------|-----------|--------------|--------|
| 1 | GitService + InputValidator | None | ✅ Done |
| 2 | ArenaConfig + ConfigLoader | None | ✅ Done |
| 3 | application.yaml + logback.xml | None | ✅ Done |
| 4 | Prompt templates | None | ✅ Done |
| 5 | WorkspaceManager | ArenaConfig | ✅ Done |
| 6 | TemplateLoader | Prompt templates | ✅ Done |
| 7 | ReviewArenaCli integration | GitService, ConfigLoader, WorkspaceManager | ✅ Done |
| 8 | AgentProcess + AgentExecutor | WorkspaceManager | ✅ Done |
| 9 | Round 0 + ReviewAggregator | AgentExecutor | ✅ Done |
| 10 | Cross-Pollination Rounds 1-N | AgentExecutor, ReviewAggregator | ✅ Done |
| 11 | Final Synthesis | Cross-Pollination complete | ✅ Done |

---

## Testing Strategy

### Milestone 1 Tests

**GitService tests:** ✅ Complete (`GitServiceTest.java`)
- `testIsInsideGitRepo_success`
- `testIsInsideGitRepo_notARepo`
- `testValidateCommitExists_fullHash`
- `testValidateCommitExists_abbreviatedHash`
- `testValidateCommitExists_notFound`
- `testValidateAncestry_valid`
- `testValidateAncestry_invalid`

**InputValidator tests:** ✅ Complete (`InputValidatorTest.java`)
- `testMutualExclusivity_stagedAndCommit_throws`
- `testMutualExclusivity_stagedOnly_passes`
- `testHashFormat_valid`
- `testHashFormat_invalid`

**ConfigLoader tests:** ✅ Complete (`ConfigLoaderTest.java`)
- `testLoadsDefaultConfig`
- `testLoadsArenaYaml`
- `testCliOverridesConfig`
- `testMissingConfigUsesDefaults`

**WorkspaceManager tests:** ✅ Complete (`WorkspaceManagerTest.java`)
- `testInitialize_createsArenaDirectory`
- `testInitialize_createsRoundsDirectory`
- `testInitialize_createsRoundDirectories`
- `testInitialize_createsAgentDirectories`
- `testInitialize_createsFinalDirectory`
- `testInitialize_createsTaskMd`
- `testInitialize_clearsExistingArenaDir`
- `testInitialize_onlyCreatesEnabledAgentDirs`
- Path helper method tests

### Milestone 2 Tests ✅ Complete

**AgentExecutor tests:**
- Mock agent scripts that write canned `review.md` files
- Timeout behavior verification
- Concurrent execution with semaphore

### Milestone 3 Tests

**Cross-pollination tests:** (see `pollination-impl-plan.md`)
- Full tournament flow with mock agents
- Agent failure and exclusion
- Minimum threshold enforcement

### Milestone 4 Tests

**Synthesis tests:**
- Claude validation before Round 0
- Synthesizer prompt generation
- `champion_review.md` creation

---

## Success Criteria

### Milestone 1 Complete When: ✅
- [x] `review-arena abc1234` validates commit exists
- [x] `review-arena --staged` validates staged mode
- [x] Invalid commits produce exit code 3
- [x] `arena.yaml` is loaded and merged with CLI args
- [x] `.arena/` directory structure is created
- [x] `task.md` is generated with placeholders resolved
- [x] All prompt templates exist in resources

### Milestone 2 Complete When: ✅
- [x] Agents spawn as subprocesses
- [x] Agent output captured to `review.md`
- [x] Timeouts terminate agents gracefully
- [x] Concurrency respects `max-concurrent`
- [x] Round 0 executes and produces `all_reviews.md`

### Milestone 3 Complete When: ✅
- [x] Cross-pollination rounds 1-N execute successfully
- [x] Failed agents retry in subsequent rounds (per spec retry strategy)
- [x] Tournament aborts if below minAgents threshold
- [x] Round-level timeout kills remaining agents when exceeded
- [x] Grace period allows clean shutdown before force-kill
- [x] Final `all_reviews.md` generated after last round
- [x] Progress output shows round status (including timeout settings in dry-run)

### Milestone 4 Complete When: ✅
- [x] `final-synth.md` template includes tournament metadata
- [x] `SynthesisContext` record creates valid context
- [x] Synthesis prompt written to `.arena/rounds/final/prompt.md`
- [x] Synthesizer agent validated (Claude required, fail if unavailable)
- [x] `champion_review.md` created in `.arena/rounds/final/`
- [x] Dry-run shows complete tournament flow including synthesis
- [x] Exit code 4 with [SYNTHESIS] prefix on synthesis failures
