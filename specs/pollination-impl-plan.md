# Cross-Pollination Rounds Implementation Plan

## Overview

This document describes the implementation plan for Rounds 1-N cross-pollination feature, where agents see `all_reviews.md` from the previous round and produce improved reviews.

**Feature Location:** `ReviewArenaCli.call()` method (currently marked as TODO)

**Note:** This is **Milestone 3**. Final synthesis (champion_review.md generation) is deferred to Milestone 4.

## Current State Analysis

### What's Already Implemented

| Component | Status | Notes |
|-----------|--------|-------|
| Round 0 execution | Complete | `ReviewArenaCli.call()` method |
| AgentExecutor | Complete | `executeRound(int round)` works for any round number |
| ReviewAggregator | Complete | `aggregateRound(int round, results)` works for any round; already filters failed results |
| WorkspaceManager | Complete | Pre-creates all round directories and prompts |
| Prompt templates | Complete | `round-1.md` through `round-5.md` include `${allReviewsPath}` |
| Directory structure | Complete | `.arena/rounds/round-N/<agent>/` pre-created |
| All round prompts | Complete | Pre-generated with correct `allReviewsPath` values |
| `getFinalDir()` | Complete | `WorkspaceManager.getFinalDir()` method |
| `getSuccessfulAgents()` | Complete | `AgentExecutor.getSuccessfulAgents()` static method |

### What's Missing

1. **Multi-round loop** (rounds 1 through `maxRounds`)
2. **Failed agent tracking** across rounds
3. **Dynamic agent filtering** per round (exclude failed agents)
4. **Minimum threshold check** after each round
5. **Config validation** for `maxRounds >= 1`
6. **Round-level timeout** enforcement (`roundTimeoutMs`)
7. **Grace period** handling before force-kill (`gracePeriodMs`)

## Implementation Design

### Data Flow

```
Startup:
  Validate maxRounds >= 1

Round 0:
  AgentExecutor.executeRound(0) → results
  ReviewAggregator.aggregateRound(0, results) → .arena/rounds/round-0/all_reviews.md
  activeAgents = getSuccessfulAgents(results)

Round 1:
  Log active agents for this round
  AgentExecutor.executeRound(1, activeAgents) → results
  ReviewAggregator.aggregateRound(1, results) → .arena/rounds/round-1/all_reviews.md
  activeAgents = intersection(activeAgents, getSuccessfulAgents(results))
  Check: activeAgents.size() >= minAgents

...repeat for rounds 2-N...

Done:
  Log final all_reviews.md location
  (Synthesis deferred to Milestone 4)
```

### Key Design Decisions

1. **Agent filtering approach**: Create `executeRound(int, Set<String>)` overload to execute specific agents
2. **Failure tracking**: Use `Set<String> activeAgents` and `retainAll()` after each round
3. **Early termination**: Abort tournament if `activeAgents.size() < minAgents`
4. **Config constraint**: Require `maxRounds >= 1` (no skipping cross-pollination)
5. **Result filtering**: `ReviewAggregator` internally filters to successful agents only

---

## Implementation Steps

### Step 1: Update Config Validation for maxRounds ✅ DONE

**File:** `ArenaConfig.java` (package: `dev.reviewarena.config`)

Modify the existing compact constructor validation. Change line 50-51 from:

```java
if (maxRounds < 0) {
    throw new ConfigException("maxRounds must be non-negative, got: " + maxRounds);
```

To:

```java
if (maxRounds < 1) {
    throw new ConfigException(
        "maxRounds must be at least 1 (cross-pollination requires at least one round). Got: " + maxRounds);
```

**Note:** `ArenaConfig` is a record that validates in its compact constructor, not via a separate `validate()` method.

**Important:** This change aligns the code with `spec.md` which already states `max-rounds` minimum is 1. The existing code incorrectly allowed 0.

#### Required Test Update

**File:** `ArenaConfigTest.java` (package: `dev.reviewarena.config`)

Update the existing test `testValidation_zeroMaxRounds_allowed` (lines 117-122) which incorrectly expects maxRounds=0 to succeed:

**Before:**
```java
@Test
void testValidation_zeroMaxRounds_allowed() {
    // 0 rounds means no cross-pollination, just initial round
    ArenaConfig config = createConfigWith(b -> b.maxRounds = 0);
    assertEquals(0, config.maxRounds());
}
```

**After:**
```java
@Test
void testValidation_zeroMaxRounds_throws() {
    ConfigException ex = assertThrows(ConfigException.class,
        () -> createConfigWith(b -> b.maxRounds = 0));

    assertTrue(ex.getMessage().contains("maxRounds must be at least 1"));
}
```

#### Also Update `testValidation_negativeMaxRounds_throws` (lines 30-35)

The existing test expects the old error message. Update the assertion to match the new message:

**Before:**
```java
@Test
void testValidation_negativeMaxRounds_throws() {
    ConfigException ex = assertThrows(ConfigException.class,
        () -> createConfigWith(b -> b.maxRounds = -1));

    assertTrue(ex.getMessage().contains("maxRounds must be non-negative"));
}
```

**After:**
```java
@Test
void testValidation_negativeMaxRounds_throws() {
    ConfigException ex = assertThrows(ConfigException.class,
        () -> createConfigWith(b -> b.maxRounds = -1));

    assertTrue(ex.getMessage().contains("maxRounds must be at least 1"));
}
```

---

### Step 2: Add `executeRound` Overload with Agent Filter

**File:** `AgentExecutor.java` (package: `dev.reviewarena.agent`)

Refactor to extract shared execution logic and add a filtered overload.

**Step 2a: Extract common execution logic into private method:**

```java
/**
 * Executes the given agents for a round.
 *
 * @param agents the agents to execute
 * @param round the round number
 * @return map of agent name to execution result
 */
private Map<String, AgentResult> executeAgents(List<AgentConfig> agents, int round) {
    // Concurrency control: 0 = unlimited, else use semaphore
    Semaphore semaphore = config.maxConcurrent() > 0
        ? new Semaphore(config.maxConcurrent())
        : null;

    ConcurrentHashMap<String, AgentResult> results = new ConcurrentHashMap<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> futures = new ArrayList<>();

        for (AgentConfig agent : agents) {
            Future<?> future = executor.submit(() -> {
                if (semaphore != null) {
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                try {
                    AgentResult result = executeAgent(agent, round);
                    results.put(agent.name(), result);
                    logResult(result);
                } finally {
                    if (semaphore != null) {
                        semaphore.release();
                    }
                }
            });
            futures.add(future);
        }

        // Wait for all agents with round timeout
        waitForAllWithTimeout(futures, config.roundTimeoutMs());

    } catch (Exception e) {
        log.error("Round {} execution failed: {}", round, e.getMessage());
        throw new AgentException("Round execution failed: " + e.getMessage(), e);
    }

    int successes = (int) results.values().stream().filter(AgentResult::isSuccess).count();
    log.info("Round {} complete: {}/{} agents succeeded", round, successes, agents.size());

    return Map.copyOf(results);
}
```

**Step 2b: Refactor existing `executeRound(int)` to use the helper:**

```java
/**
 * Executes all enabled agents for a given round.
 *
 * @param round the round number (0-indexed)
 * @return map of agent name to execution result
 * @throws AgentException if round execution fails catastrophically
 */
public Map<String, AgentResult> executeRound(int round) {
    List<AgentConfig> enabledAgents = getEnabledAgents();

    if (enabledAgents.isEmpty()) {
        log.warn("No enabled agents to execute for round {}", round);
        return Map.of();
    }

    log.info("Starting round {}/{} with {} agents: {}",
        round, config.maxRounds(), enabledAgents.size(),
        enabledAgents.stream().map(AgentConfig::name).toList());

    return executeAgents(enabledAgents, round);
}
```

**Note:** The log format is updated to show `round/maxRounds` for consistency with the filtered overload.

**Step 2c: Add new filtered overload:**

```java
/**
 * Executes specific agents for a given round.
 *
 * @param round the round number (0-indexed)
 * @param agentNames set of agent names to execute (must be enabled in config)
 * @return map of agent name to execution result
 * @throws AgentException if round execution fails catastrophically
 */
public Map<String, AgentResult> executeRound(int round, Set<String> agentNames) {
    List<AgentConfig> agents = config.agents().values().stream()
        .filter(AgentConfig::enabled)
        .filter(a -> agentNames.contains(a.name()))
        .sorted(Comparator.comparing(AgentConfig::name))
        .toList();

    if (agents.isEmpty()) {
        log.warn("No matching agents to execute for round {}", round);
        return Map.of();
    }

    log.info("Starting round {}/{} with {} agents: {}",
        round, config.maxRounds(), agents.size(),
        agents.stream().map(AgentConfig::name).toList());

    return executeAgents(agents, round);
}
```

**Rationale:** This refactoring:
1. Eliminates code duplication between the two overloads
2. Allows rounds 1-N to exclude agents that failed in previous rounds
3. Keeps all concurrency and timeout logic in one place

---

### Step 3: Verify ReviewAggregator Filters Failed Results ✅ ALREADY IMPLEMENTED

**File:** `ReviewAggregator.java` (package: `dev.reviewarena.agent`)

**Status:** Already implemented. The `aggregateRound()` method filters to successful results only at lines 55-59.

No code changes needed - just verify during implementation that this filtering remains in place.

---

### Step 4: Implement Cross-Pollination Loop in CLI

**File:** `ReviewArenaCli.java` (package: `dev.reviewarena.cli`)

**Step 4a: Add required imports:**

Add to the imports section (near line 21):

```java
import java.util.HashSet;
import java.util.Set;
```

**Step 4b: Replace the TODO comments in the `call()` method:**

Replace the existing Round 0 execution code AND the TODO comments with the complete cross-pollination implementation:

```java
// === ROUND 0 ===
Map<String, AgentResult> round0Results = executor.executeRound(0);

// Check minimum agents threshold for Round 0
long successCount = round0Results.values().stream()
    .filter(AgentResult::isSuccess)
    .count();

if (successCount < config.minAgents()) {
    log.error("[THRESHOLD] Only {} agents succeeded in Round 0, minimum {} required. Aborting.",
        successCount, config.minAgents());
    return 4;
}

// Aggregate Round 0 reviews
Path allReviews = aggregator.aggregateRound(0, round0Results);
log.info("Round 0 complete: {} agents produced reviews, aggregated to {}",
    successCount, allReviews);

// Track active agents (start with all successful from round 0)
// Use explicit HashSet for guaranteed mutability (Collectors.toSet() is implementation-dependent)
Set<String> activeAgents = new HashSet<>(AgentExecutor.getSuccessfulAgents(round0Results));
int lastCompletedRound = 0;

// === CROSS-POLLINATION ROUNDS (1 through maxRounds) ===
for (int round = 1; round <= config.maxRounds(); round++) {
    log.info("Starting round {}/{} with active agents: {}",
        round, config.maxRounds(), activeAgents);

    // Safety check: ensure we have agents to execute (should not happen if threshold checks pass)
    if (activeAgents.isEmpty()) {
        log.error("[ROUND] No active agents remaining for round {} (internal error)", round);
        return 4;
    }

    // Execute round with only active agents
    Map<String, AgentResult> roundResults = executor.executeRound(round, activeAgents);

    // Handle round failure (all agents timed out, crashed, or were filtered)
    if (roundResults.isEmpty()) {
        log.error("[ROUND] Round {} produced no results. " +
            "This may indicate all agents timed out or crashed.", round);
        return 4;
    }

    // Update active agents: keep only those that succeeded in ALL rounds so far
    Set<String> successfulThisRound = AgentExecutor.getSuccessfulAgents(roundResults);
    activeAgents.retainAll(successfulThisRound);

    // Check minimum threshold
    if (activeAgents.size() < config.minAgents()) {
        log.error("[THRESHOLD] Only {} agents remain active after round {}, minimum {} required. " +
            "Aborting tournament.", activeAgents.size(), round, config.minAgents());
        return 4;
    }

    // Aggregate this round's reviews
    Path roundAllReviews = aggregator.aggregateRound(round, roundResults);
    log.info("Round {} complete: {} agents succeeded, aggregated to {}",
        round, activeAgents.size(), roundAllReviews);

    lastCompletedRound = round;
}

// === TOURNAMENT COMPLETE ===
Path finalAllReviews = workspaceManager.getRoundDir(lastCompletedRound).resolve("all_reviews.md");
log.info("Cross-pollination complete! Final reviews: {}", finalAllReviews);
log.info("Synthesis step not yet implemented (Milestone 4)");

return 0;
```

**Note:** The code above replaces the existing Round 0 code (lines 219-240 in current `ReviewArenaCli.java`) and the TODO comments (lines 242-243). The Round 0 handling is enhanced with better logging prefixes for consistency.

---

### Step 5: Update Dry-Run to Show Full Tournament

**File:** `ReviewArenaCli.java` (package: `dev.reviewarena.cli`)

Update `printDryRunSummary()` method to include cross-pollination rounds:

```java
private void printDryRunSummary(boolean staged, String ref1, String ref2, ArenaConfig config) {
    log.info("Dry run - would execute:");
    log.info("  Review target: {}", staged ? "--staged" : ref1 + (ref2 != null ? ".." + ref2 : ""));
    log.info("  Config file: {}", configFile);
    log.info("Effective configuration:");
    log.info("  Output directory: {}", config.outputDir());
    log.info("  Cross-pollination rounds: {}", config.maxRounds());
    log.info("  Total rounds: {} (Round 0 + {} cross-pollination)",
        config.maxRounds() + 1, config.maxRounds());
    log.info("  Concurrency: {}", config.maxConcurrent() == 0 ? "unlimited" : config.maxConcurrent());
    log.info("  Agent timeout: {}ms", config.agentTimeoutMs());
    log.info("  Round timeout: {}ms", config.roundTimeoutMs());
    log.info("  Grace period: {}ms", config.gracePeriodMs());
    log.info("  Minimum agents: {}", config.minAgents());
    log.info("Agents ({} configured):", config.agents().size());
    config.agents().forEach((name, agent) -> {
        String status = agent.enabled() ? "enabled" : "disabled";
        log.info("  - {} ({}): {}", name, status, String.join(" ", agent.command()));
    });
    log.info("Tournament flow:");
    log.info("  1. Round 0: Independent reviews (all agents)");
    for (int i = 1; i <= config.maxRounds(); i++) {
        log.info("  {}. Round {}: Cross-pollination (surviving agents)", i + 1, i);
    }
    log.info("  Note: Final synthesis (Milestone 4) not yet implemented");
}
```

---

### Step 6: Verify Existing Timeout Implementation ✅ ALREADY IMPLEMENTED

**Status:** No code changes needed - verify only.

Round-level timeout and grace period handling are **already fully implemented**:

| Feature | Location | Implementation |
|---------|----------|----------------|
| Round timeout | `AgentExecutor.java:85` | `waitForAllWithTimeout(futures, config.roundTimeoutMs())` |
| Per-agent timeout | `AgentProcess.java:93` | `process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)` |
| Grace period | `AgentProcess.java:117-154` | `handleTimeout()` with graceful shutdown |
| Process tree kill | `AgentProcess.java:197-229` | `destroyDescendants()` for Windows child processes |

**Verification checklist (all verified):**
- [x] `AgentExecutor.waitForAllWithTimeout()` uses `config.roundTimeoutMs()` — verified at `AgentExecutor.java:85`
- [x] `AgentProcess` receives `config.gracePeriodMs()` via builder — verified at `AgentExecutor.java:115`
- [x] `AgentProcess.handleTimeout()` implements graceful → force kill sequence — verified at `AgentProcess.java:117-154`
- [x] Timed-out agents return `AgentResult.timeout()` status — verified at `AgentProcess.java:153`

**Note:** Line numbers are approximate and may shift slightly with code changes. Use method names for reliable navigation.

**No new methods needed.** The existing architecture correctly encapsulates process lifecycle management within `AgentProcess`, which is instantiated per-agent and handles its own timeout/termination

---

## File Changes Summary

### Production Code

| File | Package | Changes |
|------|---------|---------|
| `ArenaConfig.java` | `dev.reviewarena.config` | Change validation: maxRounds >= 1 (was >= 0) |
| `AgentExecutor.java` | `dev.reviewarena.agent` | Refactor: extract `executeAgents()` helper, add `executeRound(int, Set<String>)` overload |
| `AgentProcess.java` | `dev.reviewarena.agent` | ✅ No changes - timeout/grace period already implemented |
| `ReviewAggregator.java` | `dev.reviewarena.agent` | ✅ No changes - verify filtering only |
| `ReviewArenaCli.java` | `dev.reviewarena.cli` | Replace TODO with cross-pollination loop, add `HashSet` import |

### Test Code

| File | Package | Changes |
|------|---------|---------|
| `ArenaConfigTest.java` | `dev.reviewarena.config` | **Update existing:** rename `testValidation_zeroMaxRounds_allowed` → `testValidation_zeroMaxRounds_throws`, change assertion |
| `AgentExecutorTest.java` | `dev.reviewarena.agent` | Add tests for new `executeRound(int, Set<String>)` overload |
| `ReviewArenaCliIT.java` | `dev.reviewarena.cli` | **New file:** `src/test/java/dev/reviewarena/cli/ReviewArenaCliIT.java` — Integration tests for full tournament flow |

---

## Testing Strategy

### Existing Test Updates (REQUIRED)

Before adding new tests, update these existing tests that will break:

1. **ArenaConfigTest.java:**
   - **Update** `testValidation_zeroMaxRounds_allowed` → rename to `testValidation_zeroMaxRounds_throws`
   - Change from `assertEquals(0, config.maxRounds())` to `assertThrows(ConfigException.class, ...)`
   - **Update** `testValidation_negativeMaxRounds_throws` → change expected message
   - Change from `"maxRounds must be non-negative"` to `"maxRounds must be at least 1"`

### New Unit Tests

1. **ArenaConfigTest.java (additions):**
   - `testValidate_maxRoundsOne_passes` - verify minimum valid value
   - `testValidate_maxRoundsFive_passes` - verify default value works

2. **AgentExecutor tests:**
   - `testExecuteRoundWithAgentFilter_onlyExecutesSpecifiedAgents`
   - `testExecuteRoundWithAgentFilter_ignoresDisabledAgents`
   - `testExecuteRoundWithAgentFilter_logsActiveAgents`

3. **ReviewAggregator tests:**
   - `testAggregateRound_filtersFailedResults`
   - `testAggregateRound_includesOnlySuccessfulAgents`

### Integration Tests

1. **Full tournament flow with mock agents:**
   - `testFullTournament_allAgentsSucceed_completesAllRounds`
   - `testFullTournament_agentFailsInRound1_excludedFromRound2`
   - `testFullTournament_dropsBelowMinAgents_abortsWithCode4`
   - `testFullTournament_logsActiveAgentsEachRound`

2. **Edge cases:**
   - `testTournament_allAgentsFailRound1_abortsImmediately`
   - `testTournament_allAgentsFailRound3_abortsCorrectly`
   - `testTournament_maxRoundsOne_executesOneRoundOnly`
   - `testTournament_roundTimeout_killsRemainingAgents`
   - `testTournament_gracePeriod_allowsCleanShutdown`

3. **Config validation:**
   - `testConfig_maxRoundsZero_failsWithConfigException`

### Mock Agent Scripts (Cross-Platform)

Create both `.sh` and `.bat` versions for cross-platform testing.

**Location:** `src/test/resources/mock-agents/`

**Unix (mock-agent-success.sh):**
```bash
#!/bin/bash
output_path="$1"
echo "## Summary" > "$output_path"
echo "Mock review content" >> "$output_path"
exit 0
```

**Windows (mock-agent-success.bat):**
```batch
@echo off
setlocal
set "output_path=%~1"
(
  echo ## Summary
  echo Mock review content
) > "%output_path%"
exit /b 0
```

> **Note:** Using parentheses with redirection avoids trailing space issues with Windows `echo`.

**Unix (mock-agent-fail.sh):**
```bash
#!/bin/bash
exit 1
```

**Windows (mock-agent-fail.bat):**
```batch
@echo off
exit /b 1
```

**Unix (mock-agent-timeout.sh):**
```bash
#!/bin/bash
sleep 999999
```

**Windows (mock-agent-timeout.bat):**
```batch
@echo off
ping -n 999999 127.0.0.1 > nul
```

---

## Exit Code Mapping

| Scenario | Exit Code | Log Prefix | Message |
|----------|-----------|------------|---------|
| Success (all rounds complete) | 0 | - | "Cross-pollination complete!" |
| Agents drop below minAgents | 4 | [THRESHOLD] | "Only N agents remain active..." |
| Round execution catastrophic failure | 4 | [ROUND] | "Round execution failed: <reason>" |
| maxRounds < 1 | 5 | [CONFIG] | "maxRounds must be at least 1..." |

---

## Acceptance Criteria

The feature is complete when:

- [ ] Existing test `testValidation_zeroMaxRounds_allowed` updated to expect failure
- [ ] Existing test `testValidation_negativeMaxRounds_throws` updated with new message
- [ ] Config validation rejects `maxRounds < 1`
- [ ] Cross-pollination rounds 1 through N execute successfully
- [ ] Progress logs show active agents at each round start
- [ ] Failed agents are excluded from subsequent rounds
- [ ] Tournament aborts if active agents < minAgents
- [ ] Round-level timeout (`roundTimeoutMs`) kills remaining agents when exceeded
- [ ] Grace period (`gracePeriodMs`) allows clean shutdown before force-kill
- [ ] Final `all_reviews.md` is generated after last round
- [ ] Dry-run mode displays full tournament flow (including timeout settings)
- [ ] All tests pass (unit + integration) - **no regressions**
- [ ] Mock agents work on both Unix and Windows
- [ ] `spec.md` updated if implementation deviates from specification

---

## Implementation Order

1. **Update existing tests** (prevents build break):
   - `testValidation_zeroMaxRounds_allowed` → `testValidation_zeroMaxRounds_throws`
   - `testValidation_negativeMaxRounds_throws` → update expected message
2. Update config validation for `maxRounds >= 1` (change from >= 0)
3. Verify `ReviewAggregator` internal filtering ✅ (already implemented)
4. Refactor `AgentExecutor`: extract `executeAgents()` helper method
5. Add `AgentExecutor.executeRound(int, Set<String>)` overload
6. Verify existing timeout/grace period ✅ (already implemented in `AgentProcess`)
7. Implement cross-pollination loop in `ReviewArenaCli.call()`
8. Update dry-run output (include timeout settings)
9. Create cross-platform mock agent scripts
10. Write new unit tests
11. Write integration tests (including timeout scenarios)

---

## Notes

### Spec Alignment

This implementation plan **fixes a spec violation** in the current code. The spec (`spec.md`, line 169) clearly states:

> `max-rounds: 5` # Number of cross-pollination rounds after Round 0 (default: 5, **minimum: 1**)

However, the current `ArenaConfig.java` validation allows `maxRounds >= 0`, which contradicts the spec. This plan corrects that discrepancy. The existing test `testValidation_zeroMaxRounds_allowed` was also incorrect and must be updated.

### Prompt Pre-generation

The current implementation pre-generates all round prompts during `WorkspaceManager.initialize()`. This works because:

- Round prompts (0-N) use `${allReviewsPath}` which points to the previous round's `all_reviews.md`
- The path is deterministic: `.arena/rounds/round-{N-1}/all_reviews.md`
- Even if an agent fails, the file structure is the same

### Agent Failure Semantics

Per spec (`spec.md`, Error Handling section):

> When an agent fails during a round (crash, timeout, or invalid output), the orchestrator uses the **skip** strategy:
> - **Exclude from current round** - The failed agent's output is not included in `all_reviews.md`
> - **Exclude from subsequent rounds** - The agent is removed from the tournament entirely

This means we maintain a `Set<String> activeAgents` and use `retainAll()` after each round to keep only agents that succeeded in ALL rounds so far.

### Config Constraint Rationale

Requiring `maxRounds >= 1` ensures:
- Cross-pollination always happens (the core value proposition)
- Avoids edge case of Round 0 only with no improvement cycle
- Simplifies the flow (no special case for maxRounds=0)

### Deferred: Final Synthesis

The final synthesis step (generating `champion_review.md` using Claude) is deferred to **Milestone 4**. This document (Milestone 3) focuses solely on the cross-pollination loop mechanics.
