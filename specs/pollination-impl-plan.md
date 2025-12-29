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

### Step 1: Update Config Validation for maxRounds

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

---

### Step 2: Add `executeRound` Overload with Agent Filter

**File:** `AgentExecutor.java` (package: `dev.reviewarena.agent`)

Add a method that accepts a set of agents to include:

```java
/**
 * Executes specific agents for a given round.
 *
 * @param round the round number (0-indexed)
 * @param agentNames set of agent names to execute (must be enabled in config)
 * @return map of agent name to execution result
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

    // ... rest of execution logic (same as existing executeRound)
}
```

**Rationale:** This allows rounds 1-N to exclude agents that failed in previous rounds without modifying the config object.

---

### Step 3: Verify ReviewAggregator Filters Failed Results ✅ ALREADY IMPLEMENTED

**File:** `ReviewAggregator.java` (package: `dev.reviewarena.agent`)

**Status:** Already implemented. The `aggregateRound()` method filters to successful results only at lines 55-59.

No code changes needed - just verify during implementation that this filtering remains in place.

---

### Step 4: Implement Cross-Pollination Loop in CLI

**File:** `ReviewArenaCli.java` (package: `dev.reviewarena.cli`)

Replace the TODO comments in the `call()` method with the cross-pollination implementation:

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

    // Execute round with only active agents
    Map<String, AgentResult> roundResults = executor.executeRound(round, activeAgents);

    // Handle catastrophic round failure (timeout killed all agents, etc.)
    if (roundResults.isEmpty()) {
        log.error("[ROUND] Round {} produced no results (timeout or catastrophic failure)", round);
        return 4;
    }

    // Update active agents: keep only those that succeeded in ALL rounds so far
    Set<String> successfulThisRound = AgentExecutor.getSuccessfulAgents(roundResults);
    activeAgents.retainAll(successfulThisRound);

    // Check minimum threshold
    if (activeAgents.size() < config.minAgents()) {
        log.error("[THRESHOLD] Only {} agents remain active, minimum {} required. Aborting tournament.",
            activeAgents.size(), config.minAgents());
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
| Grace period | `AgentProcess.java:132-151` | `handleTimeout()` with graceful shutdown |
| Process tree kill | `AgentProcess.java:197-229` | `destroyDescendants()` for Windows child processes |

**Verification checklist:**
- [ ] Confirm `AgentExecutor.waitForAllWithTimeout()` uses `config.roundTimeoutMs()`
- [ ] Confirm `AgentProcess` receives `config.gracePeriodMs()` via builder
- [ ] Confirm `AgentProcess.handleTimeout()` implements graceful → force kill sequence
- [ ] Confirm timed-out agents return `AgentResult.timeout()` status

**No new methods needed.** The existing architecture correctly encapsulates process lifecycle management within `AgentProcess`, which is instantiated per-agent and handles its own timeout/termination

---

## File Changes Summary

| File | Package | Changes |
|------|---------|---------|
| `ArenaConfig.java` | `dev.reviewarena.config` | Change validation: maxRounds >= 1 (was >= 0) |
| `AgentExecutor.java` | `dev.reviewarena.agent` | Add `executeRound(int, Set<String>)` overload only |
| `AgentProcess.java` | `dev.reviewarena.agent` | ✅ No changes - timeout/grace period already implemented |
| `ReviewAggregator.java` | `dev.reviewarena.agent` | ✅ No changes - verify filtering only |
| `ReviewArenaCli.java` | `dev.reviewarena.cli` | Replace TODO with cross-pollination loop, add `HashSet` import |

---

## Testing Strategy

### Unit Tests

1. **ConfigLoader tests:**
   - `testValidate_maxRoundsZero_throws`
   - `testValidate_maxRoundsOne_passes`
   - `testValidate_maxRoundsFive_passes`

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

Create both `.sh` and `.bat` versions for cross-platform testing:

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
set "output_path=%~1"
echo ## Summary > "%output_path%"
echo Mock review content >> "%output_path%"
exit /b 0
```

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

- [ ] Config validation rejects `maxRounds < 1`
- [ ] Cross-pollination rounds 1 through N execute successfully
- [ ] Progress logs show active agents at each round start
- [ ] Failed agents are excluded from subsequent rounds
- [ ] Tournament aborts if active agents < minAgents
- [ ] Round-level timeout (`roundTimeoutMs`) kills remaining agents when exceeded
- [ ] Grace period (`gracePeriodMs`) allows clean shutdown before force-kill
- [ ] Final `all_reviews.md` is generated after last round
- [ ] Dry-run mode displays full tournament flow (including timeout settings)
- [ ] All tests pass (unit + integration)
- [ ] Mock agents work on both Unix and Windows
- [ ] `spec.md` updated if implementation deviates from specification

---

## Implementation Order

1. Update config validation for `maxRounds >= 1` (change from >= 0)
2. Verify `ReviewAggregator` internal filtering ✅ (already implemented)
3. Add `AgentExecutor.executeRound(int, Set<String>)` overload
4. Verify existing timeout/grace period ✅ (already implemented in `AgentProcess`)
5. Implement cross-pollination loop in `ReviewArenaCli.call()`
6. Update dry-run output (include timeout settings)
7. Create cross-platform mock agent scripts
8. Write unit tests
9. Write integration tests (including timeout scenarios)

---

## Notes

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
