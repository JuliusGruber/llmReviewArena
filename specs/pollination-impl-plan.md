# Cross-Pollination Rounds Implementation Plan

## Overview

This document describes the implementation plan for Rounds 1-N cross-pollination feature, where agents see `all_reviews.md` from the previous round and produce improved reviews.

**Feature Location:** `ReviewArenaCli.java:242-243` (currently marked as TODO)

## Current State Analysis

### What's Already Implemented

| Component | Status | Notes |
|-----------|--------|-------|
| Round 0 execution | Complete | `ReviewArenaCli.call()` lines 219-240 |
| AgentExecutor | Complete | `executeRound(int round)` works for any round number |
| ReviewAggregator | Complete | `aggregateRound(int round, results)` works for any round |
| WorkspaceManager | Complete | Pre-creates all round directories and prompts |
| Prompt templates | Complete | `round-1.md` through `round-5.md` include `${allReviewsPath}` |
| Directory structure | Complete | `.arena/rounds/round-N/<agent>/` pre-created |
| All round prompts | Complete | Pre-generated with correct `allReviewsPath` values |
| `getFinalDir()` | Complete | `WorkspaceManager.java:168-171` |
| `getSuccessfulAgents()` | Complete | `AgentExecutor.java:172-177` |

### What's Missing

1. **Synthesizer validation** (early, before Round 0)
2. **Multi-round loop** (rounds 1 through `maxRounds`)
3. **Failed agent tracking** across rounds
4. **Dynamic agent filtering** per round (exclude failed agents)
5. **Minimum threshold check** after each round
6. **Final synthesis step** (Claude only)
7. **ProcessType enum** for distinguishing round vs synthesis execution
8. **Config validation** for `maxRounds >= 1`

## Implementation Design

### Data Flow

```
Startup:
  Validate Claude is configured and enabled (fail fast)
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

Final Synthesis:
  Generate synthesizer prompt (references last completed round)
  Execute Claude with final-synth.md prompt
  Output: .arena/rounds/final/champion_review.md
```

### Key Design Decisions

1. **Early validation**: Validate Claude availability BEFORE Round 0 (fail fast)
2. **Agent filtering approach**: Create `executeRound(int, Set<String>)` overload to execute specific agents
3. **Failure tracking**: Use `Set<String> activeAgents` and `retainAll()` after each round
4. **Early termination**: Abort tournament if `activeAgents.size() < minAgents`
5. **Process type distinction**: Use `ProcessType` enum (ROUND, SYNTHESIS) instead of magic numbers
6. **Config constraint**: Require `maxRounds >= 1` (no skipping cross-pollination)
7. **Result filtering**: `ReviewAggregator` internally filters to successful agents only
8. **Synthesizer prompt**: Use `TemplateContext` for consistency with other prompts

---

## Implementation Steps

### Step 1: Add ProcessType Enum

**File:** `AgentProcess.java` (or new `ProcessType.java`)

Add enum to distinguish execution types:

```java
/**
 * Distinguishes between tournament round execution and final synthesis.
 */
public enum ProcessType {
    /** Regular tournament round (0-N) */
    ROUND,
    /** Final synthesis step */
    SYNTHESIS
}
```

Update `AgentProcess.builder()` to use `ProcessType` instead of round number for synthesis:

```java
// For rounds:
.processType(ProcessType.ROUND)
.round(round)

// For synthesis:
.processType(ProcessType.SYNTHESIS)
.round(null)  // or omit
```

---

### Step 2: Add Config Validation for maxRounds

**File:** `ConfigLoader.java` or `ArenaConfig.java`

Add validation that `maxRounds >= 1`:

```java
/**
 * Validates configuration constraints.
 *
 * @throws ConfigException if constraints are violated
 */
public void validate() {
    if (maxRounds < 1) {
        throw new ConfigException(
            "maxRounds must be at least 1 (cross-pollination requires at least one round). " +
            "Got: " + maxRounds);
    }
}
```

---

### Step 3: Add `executeRound` Overload with Agent Filter

**File:** `AgentExecutor.java`

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

### Step 4: Add Synthesizer Validation Helper

**File:** `AgentExecutor.java`

Add validation for synthesizer requirement:

```java
/**
 * Validates that Claude CLI is configured and enabled for synthesis.
 *
 * @throws AgentException if Claude is not available
 */
public void validateSynthesizerAvailable() {
    AgentConfig claude = config.agents().get("claude");
    if (claude == null) {
        throw new AgentException(
            "[SYNTHESIS] Final synthesis requires Claude CLI. Add 'claude' to agents configuration.");
    }
    if (!claude.enabled()) {
        throw new AgentException(
            "[SYNTHESIS] Final synthesis requires Claude CLI. Enable 'claude' in agents configuration.");
    }
}
```

---

### Step 5: Add TemplateContext Support for Synthesizer

**File:** `TemplateContext.java`

Add factory method for synthesizer context:

```java
/**
 * Creates a context for the final synthesizer prompt.
 *
 * @param allReviewsPath path to the final round's all_reviews.md
 * @param outputPath path where champion_review.md should be written
 * @return the template context
 */
public static TemplateContext forSynthesis(String allReviewsPath, String outputPath) {
    return new TemplateContext(
        null,  // roundNumber not applicable
        outputPath,
        allReviewsPath,
        null,  // commit1
        null,  // commit2
        null   // stagedFlag
    );
}
```

---

### Step 6: Add Workspace Helpers for Synthesizer

**File:** `WorkspaceManager.java`

Add methods to support synthesizer prompt generation:

```java
/**
 * Gets the path to the synthesizer prompt file.
 */
public Path getSynthesizerPromptPath() {
    return getPromptsDir().resolve("final-synth.md");
}

/**
 * Generates the synthesizer prompt for the final synthesis step.
 * Must be called at runtime after the last round completes.
 *
 * @param lastRound the last completed round number
 * @throws WorkspaceException if prompt generation fails
 */
public void generateSynthesizerPrompt(int lastRound) {
    try {
        Path allReviewsPath = getRoundDir(lastRound).resolve("all_reviews.md");
        Path outputPath = getFinalDir().resolve("champion_review.md");

        TemplateContext ctx = TemplateContext.forSynthesis(
            allReviewsPath.toString(),
            outputPath.toString()
        );

        String content = templateLoader.render("final-synth.md", ctx);
        Files.writeString(getSynthesizerPromptPath(), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
        throw new WorkspaceException("Failed to generate synthesizer prompt", e);
    }
}
```

**Note:** The synthesizer prompt must be generated at runtime (not during workspace initialization) because we need to know which round was actually the last one.

---

### Step 7: Add Final Synthesis Method

**File:** `AgentExecutor.java`

Add method to execute the synthesizer:

```java
/**
 * Executes the final synthesis step using Claude.
 *
 * @param lastRound the last completed round number
 * @return the synthesis result
 * @throws AgentException if synthesis fails
 */
public AgentResult executeSynthesis(int lastRound) {
    AgentConfig claude = config.agents().get("claude");
    if (claude == null || !claude.enabled()) {
        throw new AgentException("[SYNTHESIS] Claude is required for final synthesis");
    }

    Path promptFile = workspace.getSynthesizerPromptPath();
    Path outputFile = workspace.getFinalDir().resolve("champion_review.md");
    Path agentDir = workspace.getFinalDir();

    List<String> command = commandBuilder.build(claude, promptFile, outputFile);

    AgentProcess process = AgentProcess.builder()
        .agentName("claude-synthesizer")
        .processType(ProcessType.SYNTHESIS)
        .command(command)
        .workingDir(workspace.getArenaDir().getParent())
        .outputFile(outputFile)
        .promptFile(promptFile)
        .stdoutLog(agentDir.resolve("synthesis-stdout.log"))
        .stderrLog(agentDir.resolve("synthesis-stderr.log"))
        .timeoutMs(config.agentTimeoutMs())
        .gracePeriodMs(config.gracePeriodMs())
        .outputValidator(outputValidator)
        .build();

    return process.execute();
}
```

---

### Step 8: Ensure ReviewAggregator Filters Failed Results

**File:** `ReviewAggregator.java`

Verify or add filtering to only include successful results:

```java
/**
 * Aggregates reviews from a round into all_reviews.md.
 * Only includes reviews from agents that succeeded.
 *
 * @param round the round number
 * @param results map of agent name to result (may include failures)
 * @return path to the generated all_reviews.md
 */
public Path aggregateRound(int round, Map<String, AgentResult> results) {
    // Filter to successful results only
    Map<String, AgentResult> successfulResults = results.entrySet().stream()
        .filter(e -> e.getValue().isSuccess())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    // ... rest of aggregation logic using successfulResults
}
```

---

### Step 9: Implement Cross-Pollination Loop in CLI

**File:** `ReviewArenaCli.java`

Replace the TODO comments (lines 242-243) with the cross-pollination implementation:

```java
// === EARLY VALIDATION (before Round 0) ===
executor.validateSynthesizerAvailable();
log.info("Synthesizer validation passed: Claude is available");

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
Set<String> activeAgents = AgentExecutor.getSuccessfulAgents(round0Results);
int lastCompletedRound = 0;

// === CROSS-POLLINATION ROUNDS (1 through maxRounds) ===
for (int round = 1; round <= config.maxRounds(); round++) {
    log.info("Starting round {}/{} with active agents: {}",
        round, config.maxRounds(), activeAgents);

    // Execute round with only active agents
    Map<String, AgentResult> roundResults = executor.executeRound(round, activeAgents);

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

// === FINAL SYNTHESIS ===
log.info("Starting final synthesis with Claude");

// Generate synthesizer prompt (needs to reference last completed round)
workspaceManager.generateSynthesizerPrompt(lastCompletedRound);

AgentResult synthesisResult = executor.executeSynthesis(lastCompletedRound);
if (!synthesisResult.isSuccess()) {
    log.error("[SYNTHESIS] Final synthesis failed: {}", synthesisResult.failureReason());
    return 4;
}

Path championReview = workspaceManager.getFinalDir().resolve("champion_review.md");
log.info("Tournament complete! Final review: {}", championReview);

return 0;
```

---

### Step 10: Update Dry-Run to Show Full Tournament

**File:** `ReviewArenaCli.java`

Update `printDryRunSummary()` to include cross-pollination rounds:

```java
private void printDryRunSummary(boolean staged, String ref1, String ref2, ArenaConfig config) {
    log.info("Dry run - would execute:");
    log.info("  Review target: {}", staged ? "--staged" : ref1 + (ref2 != null ? ".." + ref2 : ""));
    log.info("  Config file: {}", configFile);
    log.info("Effective configuration:");
    log.info("  Output directory: {}", config.outputDir());
    log.info("  Max rounds: {} (Round 0 + {} cross-pollination rounds)",
        config.maxRounds() + 1, config.maxRounds());
    log.info("  Concurrency: {}", config.maxConcurrent() == 0 ? "unlimited" : config.maxConcurrent());
    log.info("  Agent timeout: {}ms", config.agentTimeoutMs());
    log.info("  Minimum agents: {}", config.minAgents());
    log.info("Agents ({} configured):", config.agents().size());
    config.agents().forEach((name, agent) -> {
        String status = agent.enabled() ? "enabled" : "disabled";
        log.info("  - {} ({}): {}", name, status, String.join(" ", agent.command()));
    });
    log.info("Tournament flow:");
    log.info("  1. Validate Claude available for synthesis");
    log.info("  2. Round 0: Independent reviews (all agents)");
    for (int i = 1; i <= config.maxRounds(); i++) {
        log.info("  {}. Round {}: Cross-pollination (surviving agents)", i + 2, i);
    }
    log.info("  {}. Final: Synthesis (Claude only)", config.maxRounds() + 3);
}
```

---

## File Changes Summary

| File | Changes |
|------|---------|
| `ProcessType.java` (new) | Add enum: ROUND, SYNTHESIS |
| `AgentProcess.java` | Use ProcessType instead of magic round number |
| `ConfigLoader.java` | Add validation: maxRounds >= 1 |
| `AgentExecutor.java` | Add `executeRound(int, Set<String>)`, `validateSynthesizerAvailable()`, `executeSynthesis(int)` |
| `TemplateContext.java` | Add `forSynthesis(String, String)` factory method |
| `WorkspaceManager.java` | Add `getSynthesizerPromptPath()`, `generateSynthesizerPrompt(int)` |
| `ReviewAggregator.java` | Ensure internal filtering of failed results |
| `ReviewArenaCli.java` | Replace TODO with cross-pollination loop + synthesis |

---

## Testing Strategy

### Unit Tests

1. **ProcessType tests:**
   - `testProcessType_roundAndSynthesisAreDifferent`

2. **ConfigLoader tests:**
   - `testValidate_maxRoundsZero_throws`
   - `testValidate_maxRoundsOne_passes`
   - `testValidate_maxRoundsFive_passes`

3. **AgentExecutor tests:**
   - `testExecuteRoundWithAgentFilter_onlyExecutesSpecifiedAgents`
   - `testExecuteRoundWithAgentFilter_ignoresDisabledAgents`
   - `testExecuteRoundWithAgentFilter_logsActiveAgents`
   - `testValidateSynthesizerAvailable_claudeEnabled_passes`
   - `testValidateSynthesizerAvailable_claudeDisabled_throws`
   - `testValidateSynthesizerAvailable_claudeMissing_throws`
   - `testExecuteSynthesis_success`
   - `testExecuteSynthesis_claudeNotAvailable_throws`
   - `testExecuteSynthesis_usesProcessTypeSynthesis`

4. **TemplateContext tests:**
   - `testForSynthesis_createsValidContext`
   - `testForSynthesis_containsCorrectPaths`

5. **WorkspaceManager tests:**
   - `testGetSynthesizerPromptPath_returnsCorrectPath`
   - `testGenerateSynthesizerPrompt_createsFile`
   - `testGenerateSynthesizerPrompt_usesTemplateContext`
   - `testGenerateSynthesizerPrompt_containsCorrectPaths`

6. **ReviewAggregator tests:**
   - `testAggregateRound_filtersFailedResults`
   - `testAggregateRound_includesOnlySuccessfulAgents`

### Integration Tests

1. **Full tournament flow with mock agents:**
   - `testFullTournament_allAgentsSucceed_completesAllRounds`
   - `testFullTournament_agentFailsInRound1_excludedFromRound2`
   - `testFullTournament_dropsBelowMinAgents_abortsWithCode4`
   - `testFullTournament_synthesisCompletes_createsChampionReview`
   - `testFullTournament_logsActiveAgentsEachRound`

2. **Edge cases:**
   - `testTournament_allAgentsFailRound1_abortsImmediately`
   - `testTournament_claudeNotConfigured_failsBeforeRound0`
   - `testTournament_maxRoundsOne_executesOneRoundPlusSynthesis`

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
set output_path=%1
echo ## Summary > %output_path%
echo Mock review content >> %output_path%
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
| Success | 0 | - | "Tournament complete!" |
| Claude not configured | 4 | [SYNTHESIS] | "Final synthesis requires Claude CLI..." |
| Claude disabled | 4 | [SYNTHESIS] | "Final synthesis requires Claude CLI..." |
| Agents drop below minAgents | 4 | [THRESHOLD] | "Only N agents remain active..." |
| Synthesis fails | 4 | [SYNTHESIS] | "Final synthesis failed: <reason>" |
| Round execution catastrophic failure | 4 | [ROUND] | "Round execution failed: <reason>" |
| maxRounds < 1 | 5 | [CONFIG] | "maxRounds must be at least 1..." |

---

## Acceptance Criteria

The feature is complete when:

- [ ] Config validation rejects `maxRounds < 1`
- [ ] Claude availability is validated before Round 0 starts
- [ ] Cross-pollination rounds 1 through N execute successfully
- [ ] Progress logs show active agents at each round start
- [ ] Failed agents are excluded from subsequent rounds
- [ ] Tournament aborts if active agents < minAgents
- [ ] Final synthesis executes with Claude after last round
- [ ] `champion_review.md` is generated in `.arena/rounds/final/`
- [ ] Dry-run mode displays full tournament flow
- [ ] All tests pass (unit + integration)
- [ ] Mock agents work on both Unix and Windows
- [ ] `spec.md` updated if implementation deviates from specification

---

## Implementation Order

1. Add `ProcessType` enum
2. Update `AgentProcess` to use `ProcessType`
3. Add config validation for `maxRounds >= 1`
4. Add `TemplateContext.forSynthesis()` factory method
5. Add `WorkspaceManager.getSynthesizerPromptPath()`
6. Add `WorkspaceManager.generateSynthesizerPrompt(int)`
7. Verify/add `ReviewAggregator` internal filtering
8. Add `AgentExecutor.executeRound(int, Set<String>)` overload
9. Add `AgentExecutor.validateSynthesizerAvailable()`
10. Add `AgentExecutor.executeSynthesis(int)`
11. Implement cross-pollination loop in `ReviewArenaCli.call()`
12. Update dry-run output
13. Create cross-platform mock agent scripts
14. Write unit tests
15. Write integration tests

---

## Notes

### Prompt Pre-generation vs Runtime Generation

The current implementation pre-generates all round prompts during `WorkspaceManager.initialize()`. This works because:

- Round prompts (0-N) use `${allReviewsPath}` which points to the previous round's `all_reviews.md`
- The path is deterministic: `.arena/rounds/round-{N-1}/all_reviews.md`
- Even if an agent fails, the file structure is the same

However, the **synthesizer prompt must be generated at runtime** because:

- It needs to reference the actual last completed round (not necessarily `maxRounds`)
- If tournament aborts early due to threshold, synthesis doesn't run anyway
- Using `TemplateContext.forSynthesis()` keeps it consistent with other prompts

### Agent Failure Semantics

Per spec (`spec.md:236-246`):

> When an agent fails during a round (crash, timeout, or invalid output), the orchestrator uses the **skip** strategy:
> - **Exclude from current round** - The failed agent's output is not included in `all_reviews.md`
> - **Exclude from subsequent rounds** - The agent is removed from the tournament entirely

This means we maintain a `Set<String> activeAgents` and use `retainAll()` after each round to keep only agents that succeeded in ALL rounds so far.

### Early Validation Rationale

Validating Claude availability before Round 0 ensures:
- Fast failure if synthesis won't work
- No wasted compute running rounds that can't complete
- Clear error message before any agents execute

### Config Constraint Rationale

Requiring `maxRounds >= 1` ensures:
- Cross-pollination always happens (the core value proposition)
- Avoids edge case of Round 0 → Synthesis with no improvement cycle
- Simplifies the flow (no special case for maxRounds=0)
