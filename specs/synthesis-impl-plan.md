# Final Synthesis Implementation Plan

## Overview

This document describes the implementation plan for the Final Synthesis step (Milestone 4), where a single agent produces the `champion_review.md` by merging all final reviews into one authoritative document.

**Feature Location:** `ReviewArenaCli.call()` method (after cross-pollination loop)

**Note:** This is **Milestone 4**. Cross-pollination (Milestone 3) is complete.

## Design Decisions (From User Discussion)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Agent availability validation | **Deferred** | Create GitHub issue for startup agent validation feature |
| Fallback behavior | **Use any available agent** | If Claude unavailable, use first enabled agent |
| Prompt metadata | **Include tournament context** | Include round count and participating agents |
| Prompt location | **Persisted** | Write to `.arena/rounds/final/prompt.md` for debugging/audit |

## Current State Analysis

### What's Already Implemented

| Component | Status | Notes |
|-----------|--------|-------|
| Cross-pollination loop | Complete | Rounds 0-N execute successfully |
| `WorkspaceManager.getFinalDir()` | Complete | Returns `.arena/rounds/final/` |
| `final-synth.md` template | Complete | Has `${allReviewsPath}` and `${outputPath}` placeholders |
| `TemplateLoader` | Complete | FreeMarker-based template rendering |
| `TemplateContext` | Complete | Has `forTask()` and `forRound()` factory methods |
| `AgentProcess` | Complete | Executes agents with timeout/graceful termination |
| Final directory creation | Complete | Created during `WorkspaceManager.initialize()` |

### What's Missing

1. **`TemplateContext.forSynthesis()`** - Factory method for synthesis prompt context
2. **Synthesizer prompt generation** - Generate prompt with tournament metadata at runtime
3. **Agent selection logic** - Select synthesizer agent (prefer Claude, fallback to others)
4. **`AgentExecutor.executeSynthesis()`** - Execute synthesizer agent
5. **CLI integration** - Add synthesis step after cross-pollination loop
6. **Dry-run update** - Show synthesis step in dry-run output
7. **GitHub issue** - For agent startup validation feature

---

## Implementation Design

### Data Flow

```
Cross-pollination complete:
  finalAllReviews = .arena/rounds/round-N/all_reviews.md
  activeAgents = Set<String> of agents that completed all rounds

Select synthesizer:
  synthesizer = selectSynthesizerAgent(config, activeAgents)

Generate prompt:
  synthContext = TemplateContext.forSynthesis(
      allReviewsPath = finalAllReviews,
      outputPath = .arena/rounds/final/champion_review.md,
      roundCount = maxRounds + 1,
      participatingAgents = activeAgents
  )
  promptContent = templateLoader.render("final-synth.md", synthContext)
  Write promptContent to .arena/rounds/final/prompt.md

Execute synthesis:
  result = executor.executeSynthesis(synthesizer, promptPath, outputPath)

Done:
  Log champion_review.md location
  Exit 0 or 4 (synthesis failure)
```

### Key Design Decisions

1. **Agent selection priority**: Prefer `claude` if enabled, else first enabled agent alphabetically
2. **Prompt persistence**: Write to `.arena/rounds/final/prompt.md` for audit/debugging
3. **Tournament metadata**: Include round count and participating agents in prompt
4. **Failure semantics**: Synthesis failure returns exit code 4 with `[SYNTHESIS]` prefix
5. **Template enhancement**: Update `final-synth.md` to include metadata placeholders

---

## Implementation Steps

### Step 1: Update `final-synth.md` Template

**File:** `src/main/resources/prompts/final-synth.md`

Enhance the template to include tournament metadata:

```markdown
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

---

### Step 2: Add `TemplateContext.forSynthesis()` Factory

**File:** `src/main/java/dev/reviewarena/io/TemplateContext.java`

**Step 2a: Update the record to include synthesis-specific fields:**

Add new fields to the record:

```java
public record TemplateContext(
        int roundNumber,
        String outputPath,
        String allReviewsPath,
        String commit1,
        String commit2,
        String stagedFlag,
        // New synthesis-specific fields
        Integer roundCount,
        Integer crossPollinationRounds,
        String participatingAgents
) {
```

**Step 2b: Update existing factory methods to pass nulls for new fields:**

```java
public static TemplateContext forTask() {
    return new TemplateContext(-1, null, null, "", "", "", null, null, null);
}

public static TemplateContext forRound(int roundNumber, String outputPath, String allReviewsPath,
                                       String commit1, String commit2, String stagedFlag) {
    return new TemplateContext(roundNumber, outputPath, allReviewsPath,
                               commit1, commit2, stagedFlag, null, null, null);
}
```

**Step 2c: Add new factory method for synthesis:**

```java
/**
 * Creates a context for synthesis prompt generation.
 *
 * @param outputPath          path where synthesizer should write champion_review.md
 * @param allReviewsPath      path to final round's all_reviews.md
 * @param roundCount          total rounds completed (Round 0 + cross-pollination rounds)
 * @param crossPollinationRounds number of cross-pollination rounds
 * @param participatingAgents comma-separated list of agents that completed all rounds
 * @return a template context for synthesis prompt rendering
 */
public static TemplateContext forSynthesis(String outputPath, String allReviewsPath,
                                           int roundCount, int crossPollinationRounds,
                                           String participatingAgents) {
    return new TemplateContext(-1, outputPath, allReviewsPath,
                               "", "", "",
                               roundCount, crossPollinationRounds, participatingAgents);
}
```

**Step 2d: Update `toDataModel()` to include new fields:**

```java
public Map<String, Object> toDataModel() {
    Map<String, Object> model = new HashMap<>();

    if (roundNumber >= 0) {
        model.put("roundNumber", roundNumber);
    }
    if (outputPath != null) {
        model.put("outputPath", outputPath);
    }
    if (allReviewsPath != null) {
        model.put("allReviewsPath", allReviewsPath);
    }
    if (commit1 != null) {
        model.put("commit1", commit1);
    }
    if (commit2 != null) {
        model.put("commit2", commit2);
    }
    if (stagedFlag != null) {
        model.put("stagedFlag", stagedFlag);
    }
    // New synthesis fields
    if (roundCount != null) {
        model.put("roundCount", roundCount);
    }
    if (crossPollinationRounds != null) {
        model.put("crossPollinationRounds", crossPollinationRounds);
    }
    if (participatingAgents != null) {
        model.put("participatingAgents", participatingAgents);
    }

    return model;
}
```

---

### Step 3: Add `generateSynthesisPrompt()` to WorkspaceManager

**File:** `src/main/java/dev/reviewarena/io/WorkspaceManager.java`

**Step 3a: Add constant for synthesis template:**

```java
private static final String TASK_TEMPLATE = "task.md";
private static final String SYNTHESIS_TEMPLATE = "final-synth.md";
```

**Step 3b: Add method to generate synthesis prompt:**

```java
/**
 * Generates the synthesis prompt at runtime (after tournament completes).
 *
 * @param finalRound           the last completed round number
 * @param participatingAgents  set of agents that completed all rounds
 * @return the path to the generated synthesis prompt
 * @throws WorkspaceException if prompt generation fails
 */
public Path generateSynthesisPrompt(int finalRound, Set<String> participatingAgents) {
    try {
        Path finalDir = getFinalDir();
        Path promptPath = finalDir.resolve("prompt.md");
        Path outputPath = finalDir.resolve("champion_review.md");
        Path allReviewsPath = getRoundDir(finalRound).resolve("all_reviews.md");

        // Build relative paths for the prompt
        String allReviewsRelative = ".arena/rounds/round-" + finalRound + "/all_reviews.md";
        String outputRelative = ".arena/rounds/final/champion_review.md";

        // Calculate round counts
        int roundCount = finalRound + 1;  // Round 0 + cross-pollination rounds
        int crossPollinationRounds = finalRound;  // Rounds 1 through N

        // Format participating agents
        String agentsList = participatingAgents.stream()
            .sorted()
            .collect(java.util.stream.Collectors.joining(", "));

        // Get task.md content to prepend
        String taskContent = templateLoader.render(TASK_TEMPLATE, TemplateContext.forTask());

        // Render synthesis template
        TemplateContext ctx = TemplateContext.forSynthesis(
            outputRelative,
            allReviewsRelative,
            roundCount,
            crossPollinationRounds,
            agentsList
        );
        String synthContent = templateLoader.render(SYNTHESIS_TEMPLATE, ctx);

        // Combine task + synthesis into complete prompt
        String fullPrompt = taskContent + "\n\n---\n\n" + synthContent;

        Files.writeString(promptPath, fullPrompt, StandardCharsets.UTF_8);

        return promptPath;
    } catch (IOException e) {
        throw new WorkspaceException("Failed to generate synthesis prompt", e);
    }
}

/**
 * Gets the path to the champion_review.md output file.
 *
 * @return the champion review path
 */
public Path getChampionReviewPath() {
    return getFinalDir().resolve("champion_review.md");
}
```

---

### Step 4: Add `executeSynthesis()` to AgentExecutor

**File:** `src/main/java/dev/reviewarena/agent/AgentExecutor.java`

**Step 4a: Add synthesis execution method:**

```java
/**
 * Executes the synthesis step using the specified agent.
 *
 * @param agentName the name of the agent to use for synthesis
 * @param promptPath the path to the synthesis prompt
 * @param outputPath the path where champion_review.md should be written
 * @return the execution result
 * @throws AgentException if synthesis fails catastrophically
 */
public AgentResult executeSynthesis(String agentName, Path promptPath, Path outputPath) {
    AgentConfig agentConfig = config.agents().get(agentName);
    if (agentConfig == null || !agentConfig.enabled()) {
        throw new AgentException("Synthesizer agent '" + agentName + "' not found or disabled");
    }

    log.info("[SYNTHESIS] Starting synthesis with agent: {}", agentName);

    Path finalDir = workspace.getFinalDir();

    List<String> command = commandBuilder.build(agentConfig, promptPath, outputPath);

    AgentProcess process = AgentProcess.builder()
        .agentName(agentName)
        .round(-1)  // Special round indicator for synthesis
        .command(command)
        .workingDir(workspace.getArenaDir().getParent()) // project root
        .outputFile(outputPath)
        .promptFile(promptPath)
        .stdoutLog(finalDir.resolve("synthesis-stdout.log"))
        .stderrLog(finalDir.resolve("synthesis-stderr.log"))
        .timeoutMs(config.agentTimeoutMs())
        .gracePeriodMs(config.gracePeriodMs())
        .outputValidator(outputValidator)
        .build();

    AgentResult result = process.execute();

    if (result.isSuccess()) {
        log.info("[SYNTHESIS] Synthesis completed successfully in {}ms", result.durationMs());
    } else {
        log.error("[SYNTHESIS] Synthesis failed: {}", result.failureReason());
    }

    return result;
}

/**
 * Selects the best agent for synthesis.
 * Prefers "claude" if available and enabled, otherwise first enabled agent alphabetically.
 *
 * @param preferredAgents optional set of agents to prefer (e.g., those that completed tournament)
 * @return the selected agent name
 * @throws AgentException if no enabled agents are available
 */
public String selectSynthesizerAgent(Set<String> preferredAgents) {
    // First, try to find "claude" if it's enabled and in preferred set
    AgentConfig claude = config.agents().get("claude");
    if (claude != null && claude.enabled() &&
        (preferredAgents == null || preferredAgents.contains("claude"))) {
        return "claude";
    }

    // Fall back to first enabled agent from preferred set (alphabetically)
    if (preferredAgents != null && !preferredAgents.isEmpty()) {
        return preferredAgents.stream()
            .filter(name -> {
                AgentConfig agent = config.agents().get(name);
                return agent != null && agent.enabled();
            })
            .sorted()
            .findFirst()
            .orElseThrow(() -> new AgentException(
                "No enabled agents available for synthesis from: " + preferredAgents));
    }

    // Last resort: any enabled agent
    return config.agents().values().stream()
        .filter(AgentConfig::enabled)
        .map(AgentConfig::name)
        .sorted()
        .findFirst()
        .orElseThrow(() -> new AgentException("No enabled agents available for synthesis"));
}
```

---

### Step 5: Integrate Synthesis into ReviewArenaCli

**File:** `src/main/java/dev/reviewarena/cli/ReviewArenaCli.java`

**Step 5a: Replace the TODO comments with synthesis implementation:**

Replace lines 285-290 (the "TOURNAMENT COMPLETE" section) with:

```java
// === TOURNAMENT COMPLETE - START SYNTHESIS ===
Path finalAllReviews = workspaceManager.getRoundDir(lastCompletedRound).resolve("all_reviews.md");
log.info("Cross-pollination complete! Final reviews: {}", finalAllReviews);

// Select synthesizer agent
String synthesizerAgent;
try {
    synthesizerAgent = executor.selectSynthesizerAgent(activeAgents);
    log.info("[SYNTHESIS] Selected synthesizer: {}", synthesizerAgent);
} catch (Exception e) {
    log.error("[SYNTHESIS] Failed to select synthesizer agent: {}", e.getMessage());
    return 4;
}

// Generate synthesis prompt
Path promptPath;
try {
    promptPath = workspaceManager.generateSynthesisPrompt(lastCompletedRound, activeAgents);
    log.info("[SYNTHESIS] Prompt generated: {}", promptPath);
} catch (Exception e) {
    log.error("[SYNTHESIS] Failed to generate synthesis prompt: {}", e.getMessage());
    return 4;
}

// Execute synthesis
Path championReviewPath = workspaceManager.getChampionReviewPath();
AgentResult synthesisResult = executor.executeSynthesis(synthesizerAgent, promptPath, championReviewPath);

if (!synthesisResult.isSuccess()) {
    log.error("[SYNTHESIS] Synthesis failed: {}", synthesisResult.failureReason());
    return 4;
}

log.info("Tournament complete! Champion review: {}", championReviewPath);

return 0;
```

---

### Step 6: Update Dry-Run Output

**File:** `src/main/java/dev/reviewarena/cli/ReviewArenaCli.java`

Update `printDryRunSummary()` to show synthesis step:

Replace the tournament flow section (lines 341-346) with:

```java
log.info("Tournament flow:");
log.info("  1. Round 0: Independent reviews (all agents)");
for (int i = 1; i <= config.maxRounds(); i++) {
    log.info("  {}. Round {}: Cross-pollination (surviving agents)", i + 1, i);
}
log.info("  {}. Final synthesis: Champion review (single agent)", config.maxRounds() + 2);
log.info("Synthesis agent: claude (preferred) or first available");
```

---

### Step 7: Create GitHub Issue for Agent Startup Validation

**Action:** Manually create GitHub issue with the following content:

**Title:** Agent availability should be validated on startup

**Body:**
```markdown
## Summary
Validate that required agents (especially Claude for synthesis) are available and responding before starting the tournament.

## Current Behavior
The arena starts the tournament without checking if agents are actually runnable. If Claude is unavailable, synthesis fails at the end after all rounds complete.

## Proposed Behavior
1. Before Round 0, check that at least `minAgents` are runnable
2. Optionally check that Claude is available (since it's preferred for synthesis)
3. Validation could be: `claude --version` or similar quick command

## Acceptance Criteria
- [ ] `review-arena --dry-run` shows which agents are available
- [ ] Tournament aborts early if fewer than `minAgents` agents respond
- [ ] Warning logged if Claude unavailable but other agents are

## Notes
- This was deferred from Milestone 4 (synthesis) to keep scope manageable
- Related to agent fallback logic in synthesis step
```

---

## File Changes Summary

### Production Code

| File | Package | Changes |
|------|---------|---------|
| `final-synth.md` | `resources/prompts` | Add tournament metadata placeholders |
| `TemplateContext.java` | `dev.reviewarena.io` | Add `roundCount`, `crossPollinationRounds`, `participatingAgents` fields; add `forSynthesis()` factory |
| `WorkspaceManager.java` | `dev.reviewarena.io` | Add `generateSynthesisPrompt()`, `getChampionReviewPath()` |
| `AgentExecutor.java` | `dev.reviewarena.agent` | Add `executeSynthesis()`, `selectSynthesizerAgent()` |
| `ReviewArenaCli.java` | `dev.reviewarena.cli` | Replace TODO with synthesis logic, update dry-run output |

### Test Code

| File | Package | Changes |
|------|---------|---------|
| `TemplateContextTest.java` | `dev.reviewarena.io` | Add tests for `forSynthesis()` |
| `WorkspaceManagerTest.java` | `dev.reviewarena.io` | Add tests for `generateSynthesisPrompt()` |
| `AgentExecutorTest.java` | `dev.reviewarena.agent` | Add tests for `executeSynthesis()`, `selectSynthesizerAgent()` |
| `ReviewArenaCliIT.java` | `dev.reviewarena.cli` | Add integration tests for full tournament with synthesis |

---

## Testing Strategy

### Unit Tests

1. **TemplateContextTest.java:**
   - `testForSynthesis_createsValidContext`
   - `testForSynthesis_toDataModel_includesAllFields`
   - `testForTask_nullSynthesisFields`
   - `testForRound_nullSynthesisFields`

2. **WorkspaceManagerTest.java:**
   - `testGenerateSynthesisPrompt_createsPromptFile`
   - `testGenerateSynthesisPrompt_includesTaskContent`
   - `testGenerateSynthesisPrompt_includesMetadata`
   - `testGetChampionReviewPath_correctLocation`

3. **AgentExecutorTest.java:**
   - `testSelectSynthesizerAgent_prefersClaudeWhenAvailable`
   - `testSelectSynthesizerAgent_fallsBackToFirstEnabled`
   - `testSelectSynthesizerAgent_respectsPreferredSet`
   - `testSelectSynthesizerAgent_throwsWhenNoAgentsAvailable`
   - `testExecuteSynthesis_successfulExecution`
   - `testExecuteSynthesis_logsWithSynthesisPrefix`

### Integration Tests

1. **Full tournament flow:**
   - `testFullTournament_withSynthesis_producesChampionReview`
   - `testFullTournament_synthesisFailure_returnsExitCode4`
   - `testFullTournament_noClaudeAvailable_usesOtherAgent`

2. **Edge cases:**
   - `testSynthesis_allAgentsFailed_selectsFromConfig`
   - `testSynthesis_singleAgentRemaining_usesForSynthesis`

---

## Exit Code Mapping

| Scenario | Exit Code | Log Prefix | Message |
|----------|-----------|------------|---------|
| Success (synthesis complete) | 0 | - | "Tournament complete! Champion review: ..." |
| Synthesizer selection failed | 4 | [SYNTHESIS] | "Failed to select synthesizer agent: ..." |
| Synthesis prompt generation failed | 4 | [SYNTHESIS] | "Failed to generate synthesis prompt: ..." |
| Synthesis execution failed | 4 | [SYNTHESIS] | "Synthesis failed: <reason>" |

---

## Acceptance Criteria

The feature is complete when:

- [ ] `final-synth.md` template includes tournament metadata placeholders
- [ ] `TemplateContext.forSynthesis()` creates valid context with metadata
- [ ] `WorkspaceManager.generateSynthesisPrompt()` writes to `.arena/rounds/final/prompt.md`
- [ ] `AgentExecutor.selectSynthesizerAgent()` prefers Claude, falls back to other agents
- [ ] `AgentExecutor.executeSynthesis()` executes synthesizer and produces output
- [ ] `ReviewArenaCli` integrates synthesis after cross-pollination
- [ ] `champion_review.md` is written to `.arena/rounds/final/`
- [ ] Synthesis logs use `[SYNTHESIS]` prefix
- [ ] Dry-run shows complete tournament flow including synthesis
- [ ] Exit code 4 on synthesis failure
- [ ] All tests pass (unit + integration)
- [ ] GitHub issue created for agent startup validation

---

## Implementation Order

1. Update `final-synth.md` template with metadata placeholders
2. Update `TemplateContext` with new fields and `forSynthesis()` factory
3. Add `generateSynthesisPrompt()` and `getChampionReviewPath()` to `WorkspaceManager`
4. Add `selectSynthesizerAgent()` and `executeSynthesis()` to `AgentExecutor`
5. Integrate synthesis into `ReviewArenaCli.call()`
6. Update dry-run output
7. Write unit tests
8. Write integration tests
9. Create GitHub issue for agent validation

---

## Notes

### Prompt Persistence Rationale

Writing the synthesis prompt to `.arena/rounds/final/prompt.md` provides:
- **Debugging**: Easy to inspect what prompt was sent to the synthesizer
- **Audit trail**: Complete record of tournament inputs/outputs
- **Reproducibility**: Can manually re-run synthesis with the saved prompt

### Synthesizer Selection Logic

The selection priority is:
1. **Claude** if enabled and in the set of agents that completed all rounds
2. **First alphabetically** from agents that completed all rounds
3. **Any enabled agent** as last resort

This ensures:
- Claude's synthesis quality is preferred when available
- Agents that participated in the tournament have context advantage
- Tournament never fails due to lack of synthesizer

### No ProcessType Enum Needed

Originally the milestones suggested adding a `ProcessType` enum. After analysis, this is unnecessary because:
- `AgentProcess` already handles round=-1 gracefully
- Logging prefix `[SYNTHESIS]` provides sufficient differentiation
- No code paths need to switch on process type
