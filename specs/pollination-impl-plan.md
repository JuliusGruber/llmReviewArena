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

### What's Missing

1. **Multi-round loop** (rounds 1 through `maxRounds`)
2. **Failed agent tracking** across rounds
3. **Dynamic agent filtering** per round (exclude failed agents)
4. **Minimum threshold check** after each round
5. **Final synthesis step** (Claude only)

## Implementation Design

### Data Flow

```
Round 0:
  AgentExecutor.executeRound(0) → results
  ReviewAggregator.aggregateRound(0, results) → .arena/rounds/round-0/all_reviews.md
  activeAgents = getSuccessfulAgents(results)

Round 1:
  Filter config to only activeAgents
  AgentExecutor.executeRound(1) → results
  ReviewAggregator.aggregateRound(1, results) → .arena/rounds/round-1/all_reviews.md
  activeAgents = getSuccessfulAgents(results)
  Check: activeAgents.size() >= minAgents

...repeat for rounds 2-N...

Final Synthesis:
  Validate Claude is available
  Execute Claude with final-synth.md prompt
  Output: .arena/rounds/final/champion_review.md
```

### Key Design Decisions

1. **Agent filtering approach**: Create a filtered `ArenaConfig` with only active agents enabled
2. **Failure tracking**: Use `Set<String> activeAgents` to track which agents remain
3. **Early termination**: Abort tournament if `activeAgents.size() < minAgents`
4. **Synthesizer requirement**: Check Claude is in config and enabled before starting tournament

---

## Implementation Steps

### Step 1: Add `executeRound` Overload with Agent Filter

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

    // ... rest of execution logic (same as existing)
}
```

**Rationale:** This allows rounds 1-N to exclude agents that failed in previous rounds without modifying the config object.

---

### Step 2: Add Synthesizer Validation Helper

**File:** `AgentExecutor.java` (or new `SynthesizerValidator.java`)

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
            "Final synthesis requires Claude CLI. Add 'claude' to agents configuration.");
    }
    if (!claude.enabled()) {
        throw new AgentException(
            "Final synthesis requires Claude CLI. Enable 'claude' in agents configuration.");
    }
}
```

---

### Step 3: Add Final Synthesis Method

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
        throw new AgentException("Claude is required for final synthesis");
    }

    Path promptFile = workspace.getSynthesizerPromptPath();
    Path outputFile = workspace.getFinalDir().resolve("champion_review.md");
    Path agentDir = workspace.getFinalDir();

    List<String> command = commandBuilder.build(claude, promptFile, outputFile);

    AgentProcess process = AgentProcess.builder()
        .agentName("claude-synthesizer")
        .round(-1) // Indicates synthesis, not a tournament round
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

### Step 4: Add Workspace Helper for Synthesizer Prompt

**File:** `WorkspaceManager.java`

Add method to get synthesizer prompt path:

```java
/**
 * Gets the path to the synthesizer prompt file.
 */
public Path getSynthesizerPromptPath() {
    return promptsDir.resolve("final-synth.md");
}

/**
 * Gets the final output directory.
 */
public Path getFinalDir() {
    return arenaDir.resolve("rounds").resolve("final");
}
```

Update `generateAllRoundPrompts()` to also generate the synthesizer prompt:

```java
private void generateSynthesizerPrompt(int lastRound) throws IOException {
    String template = templateLoader.loadTemplate("final-synth.md");

    Path allReviewsPath = getRoundDir(lastRound).resolve("all_reviews.md");
    Path outputPath = getFinalDir().resolve("champion_review.md");

    String prompt = template
        .replace("${allReviewsPath}", allReviewsPath.toString())
        .replace("${outputPath}", outputPath.toString());

    Files.writeString(getSynthesizerPromptPath(), prompt, StandardCharsets.UTF_8);
}
```

**Note:** The synthesizer prompt must be generated AFTER the last round completes (not during workspace initialization) because we need to know which round was actually the last one.

---

### Step 5: Implement Cross-Pollination Loop in CLI

**File:** `ReviewArenaCli.java`

Replace the TODO comments (lines 242-243) with the cross-pollination implementation:

```java
// Track active agents (start with all successful from round 0)
Set<String> activeAgents = AgentExecutor.getSuccessfulAgents(round0Results);
int lastCompletedRound = 0;

// Execute cross-pollination rounds (1 through maxRounds)
for (int round = 1; round <= config.maxRounds(); round++) {
    log.info("Starting round {}/{}", round, config.maxRounds());

    // Execute round with only active agents
    Map<String, AgentResult> roundResults = executor.executeRound(round, activeAgents);

    // Update active agents based on this round's results
    Set<String> successfulThisRound = AgentExecutor.getSuccessfulAgents(roundResults);
    activeAgents.retainAll(successfulThisRound); // Keep only agents that succeeded

    // Check minimum threshold
    if (activeAgents.size() < config.minAgents()) {
        log.error("Only {} agents remain active, minimum {} required. Aborting tournament.",
            activeAgents.size(), config.minAgents());
        return 4; // Agent error exit code
    }

    // Aggregate this round's reviews
    Path allReviews = aggregator.aggregateRound(round, roundResults);
    log.info("Round {} complete: {} agents succeeded, aggregated to {}",
        round, activeAgents.size(), allReviews);

    lastCompletedRound = round;
}

// Final synthesis step
log.info("Starting final synthesis with Claude");
executor.validateSynthesizerAvailable();

// Generate synthesizer prompt (needs to reference last completed round)
workspaceManager.generateSynthesizerPrompt(lastCompletedRound);

AgentResult synthesisResult = executor.executeSynthesis(lastCompletedRound);
if (!synthesisResult.isSuccess()) {
    log.error("Final synthesis failed: {}", synthesisResult.failureReason());
    return 4;
}

log.info("Tournament complete! Final review: {}",
    workspaceManager.getFinalDir().resolve("champion_review.md"));
```

---

### Step 6: Update Dry-Run to Show Full Tournament

**File:** `ReviewArenaCli.java`

Update `printDryRunSummary()` to include cross-pollination rounds:

```java
private void printDryRunSummary(boolean staged, String ref1, String ref2, ArenaConfig config) {
    log.info("Dry run - would execute:");
    log.info("  Review target: {}", staged ? "--staged" : ref1 + (ref2 != null ? ".." + ref2 : ""));
    log.info("  Config file: {}", configFile);
    log.info("Effective configuration:");
    log.info("  Output directory: {}", config.outputDir());
    log.info("  Max rounds: {} (Round 0 + {} cross-pollination rounds)", config.maxRounds(), config.maxRounds());
    log.info("  Concurrency: {}", config.maxConcurrent() == 0 ? "unlimited" : config.maxConcurrent());
    log.info("  Agent timeout: {}ms", config.agentTimeoutMs());
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
    log.info("  {}. Final: Synthesis (Claude only)", config.maxRounds() + 2);
}
```

---

## File Changes Summary

| File | Changes |
|------|---------|
| `AgentExecutor.java` | Add `executeRound(int, Set<String>)`, `validateSynthesizerAvailable()`, `executeSynthesis(int)` |
| `WorkspaceManager.java` | Add `getSynthesizerPromptPath()`, `getFinalDir()`, `generateSynthesizerPrompt(int)` |
| `ReviewArenaCli.java` | Replace TODO with cross-pollination loop + synthesis |

---

## Testing Strategy

### Unit Tests

1. **AgentExecutor tests:**
   - `testExecuteRoundWithAgentFilter_onlyExecutesSpecifiedAgents`
   - `testExecuteRoundWithAgentFilter_ignoresDisabledAgents`
   - `testValidateSynthesizerAvailable_claudeEnabled_passes`
   - `testValidateSynthesizerAvailable_claudeDisabled_throws`
   - `testValidateSynthesizerAvailable_claudeMissing_throws`
   - `testExecuteSynthesis_success`
   - `testExecuteSynthesis_claudeNotAvailable_throws`

2. **WorkspaceManager tests:**
   - `testGetSynthesizerPromptPath_returnsCorrectPath`
   - `testGetFinalDir_returnsCorrectPath`
   - `testGenerateSynthesizerPrompt_createsFile`
   - `testGenerateSynthesizerPrompt_containsCorrectPaths`

### Integration Tests

1. **Full tournament flow with mock agents:**
   - `testFullTournament_allAgentsSucceed_completesAllRounds`
   - `testFullTournament_agentFailsInRound1_excludedFromRound2`
   - `testFullTournament_dropsBelowMinAgents_abortsWithCode4`
   - `testFullTournament_synthesisCompletes_createsChampionReview`

2. **Edge cases:**
   - `testTournament_allAgentsFailRound1_abortsImmediately`
   - `testTournament_claudeNotConfigured_failsBeforeStart`
   - `testTournament_maxRoundsZero_onlyRound0AndSynthesis`

### Mock Agent Scripts

Create shell script mock agents that simulate success/failure scenarios:

```bash
# mock-agent-success.sh - Writes a valid review
#!/bin/bash
output_path="$1"
echo "## Summary\nMock review content" > "$output_path"
exit 0

# mock-agent-fail.sh - Fails with non-zero exit
#!/bin/bash
exit 1

# mock-agent-timeout.sh - Sleeps forever (tests timeout)
#!/bin/bash
sleep 999999
```

---

## Exit Code Mapping

| Scenario | Exit Code | Message |
|----------|-----------|---------|
| Success (all rounds + synthesis complete) | 0 | "Tournament complete!" |
| Agents drop below minAgents threshold | 4 | "Only N agents remain active..." |
| Claude not configured for synthesis | 4 | "Final synthesis requires Claude CLI..." |
| Synthesis fails | 4 | "Final synthesis failed: <reason>" |
| Round execution catastrophic failure | 4 | "Round execution failed: <reason>" |

---

## Acceptance Criteria

The feature is complete when:

- [ ] Cross-pollination rounds 1 through N execute successfully
- [ ] Failed agents are excluded from subsequent rounds
- [ ] Tournament aborts if active agents < minAgents
- [ ] Final synthesis executes with Claude after last round
- [ ] `champion_review.md` is generated in `.arena/rounds/final/`
- [ ] Progress output shows round status clearly
- [ ] Dry-run mode displays full tournament flow
- [ ] All tests pass (unit + integration)

---

## Implementation Order

1. Add `WorkspaceManager` helper methods (`getSynthesizerPromptPath`, `getFinalDir`, `generateSynthesizerPrompt`)
2. Add `AgentExecutor.executeRound(int, Set<String>)` overload
3. Add `AgentExecutor.validateSynthesizerAvailable()`
4. Add `AgentExecutor.executeSynthesis(int)`
5. Implement cross-pollination loop in `ReviewArenaCli.call()`
6. Update dry-run output
7. Write unit tests
8. Write integration tests

---

## Notes

### Prompt Pre-generation vs Runtime Generation

The current implementation pre-generates all round prompts during `WorkspaceManager.initialize()`. This works because:

- Round prompts (0-N) use `${allReviewsPath}` which points to the previous round's `all_reviews.md`
- The path is deterministic: `.arena/rounds/round-{N-1}/all_reviews.md`
- Even if an agent fails, the file structure is the same

However, the **synthesizer prompt must be generated at runtime** because:

- It needs to reference the actual last completed round (not necessarily `maxRounds`)
- If tournament aborts early due to threshold, synthesis still needs correct path

### Agent Failure Semantics

Per spec (`spec.md:236-246`):

> When an agent fails during a round (crash, timeout, or invalid output), the orchestrator uses the **skip** strategy:
> - **Exclude from current round** - The failed agent's output is not included in `all_reviews.md`
> - **Exclude from subsequent rounds** - The agent is removed from the tournament entirely

This means we maintain a `Set<String> activeAgents` and use `retainAll()` after each round.
