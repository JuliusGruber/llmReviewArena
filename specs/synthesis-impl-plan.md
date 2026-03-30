# Final Synthesis Implementation Plan

## Overview

This document describes the implementation plan for the Final Synthesis step (Milestone 4), where a single agent produces the `champion_review.md` by merging all final reviews into one authoritative document.

**Feature Location:** `ReviewArenaCli.call()` method (after cross-pollination loop)

**Note:** This is **Milestone 4**. Cross-pollination (Milestone 3) is complete.

## Review Status

| Aspect | Status | Notes |
|--------|--------|-------|
| Spec alignment | ✅ Verified | `spec.md` updated to match plan |
| Flow diagram | ✅ Updated | Synthesis step now shown explicitly |
| Implementation decisions | ✅ Updated | Synthesis decisions added |
| Edge cases | ✅ Documented | See [Edge Cases](#edge-cases) section |
| Test coverage | ✅ Planned | Unit + integration tests specified |
| Template file sync | ✅ Fixed | `final-synth.md` now matches spec |
| **Ready for implementation** | ✅ **YES** | All prerequisites complete |

### Review History

| Date | Reviewer | Changes |
|------|----------|---------|
| 2025-12-29 | Claude Opus 4.5 | Fixed critical sync issue: updated `final-synth.md` template to include tournament metadata placeholders. Fixed test method naming inconsistency. Added import note for WorkspaceManager. |
| 2025-12-29 | Claude Opus 4.5 | Ultra-thorough review: Fixed milestones.md sync (removed "fallback" language). Fixed hardcoded `.arena` paths to use `config.outputDir()`. Added `all_reviews.md` existence validation before synthesis. |
| 2026-01-03 | Claude Opus 4.5 | Implementation readiness review: Fixed TemplateContext field mismatch (added `previousReviewsContent`). Changed to separate `SynthesisContext` and `SynthesisResult` types. Clarified participating agents = final round successes. |

## Design Decisions (From User Discussion)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Agent availability validation | **Deferred** | Create GitHub issue for startup agent validation feature |
| Synthesizer agent | **Claude required (no fallback)** | Per spec: "Claude is always used for this step" - fail with exit code 4 if unavailable |
| Prompt metadata | **Include tournament context** | Include round count and participating agents for transparency |
| Prompt location | **Persisted** | Write to `.arena/rounds/final/prompt.md` for debugging/audit |
| Context design | **Separate `SynthesisContext`** | Clean separation from round-based `TemplateContext`; avoids polluting existing record |
| Result type | **Separate `SynthesisResult`** | Synthesis is not a round; `AgentResult` requires non-negative round number |
| Participating agents | **Final round successes only** | Only agents whose reviews are in `all_reviews.md` (succeeded in last round) |
| Hardcoded paths | **Fix now** | Update `regenerateRoundPrompts()` to use `config.outputDir()` as part of this milestone |

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

1. **`SynthesisContext`** - New record for synthesis prompt context (separate from `TemplateContext`)
2. **`SynthesisResult`** - New record for synthesis execution result (separate from `AgentResult`)
3. **Synthesizer prompt generation** - Generate prompt with tournament metadata at runtime
4. **Agent selection logic** - Validate Claude is available (required, no fallback)
5. **`AgentExecutor.executeSynthesis()`** - Execute synthesizer agent
6. **CLI integration** - Add synthesis step after cross-pollination loop, track final round successes
7. **Dry-run update** - Show synthesis step in dry-run output
8. **Fix hardcoded paths** - Update `regenerateRoundPrompts()` to use `config.outputDir()`
9. **GitHub issue** - For agent startup validation feature

---

## Implementation Design

### Data Flow

```
Cross-pollination complete:
  finalAllReviews = .arena/rounds/round-N/all_reviews.md
  finalRoundResults = Map<String, AgentResult> from last round
  finalRoundSuccesses = AgentExecutor.getSuccessfulAgents(finalRoundResults)

Validate synthesizer:
  executor.validateSynthesizerAvailable()  // throws if Claude unavailable

Generate prompt:
  synthContext = new SynthesisContext(
      allReviewsPath = config.outputDir() + "/rounds/round-N/all_reviews.md",
      outputPath = config.outputDir() + "/rounds/final/champion_review.md",
      roundCount = lastCompletedRound + 1,
      crossPollinationRounds = lastCompletedRound,
      participatingAgents = finalRoundSuccesses (sorted, comma-separated)
  )
  promptContent = templateLoader.render("final-synth.md", synthContext)
  Write promptContent to .arena/rounds/final/prompt.md

Execute synthesis:
  result = executor.executeSynthesis("claude", promptPath, outputPath)
  // Returns SynthesisResult (not AgentResult)

Done:
  Log champion_review.md location
  Exit 0 or 4 (synthesis failure)
```

### Key Design Decisions

1. **Agent selection**: Claude is **required** for synthesis (per spec). Fail with exit code 4 if unavailable.
2. **Prompt persistence**: Write to `.arena/rounds/final/prompt.md` for audit/debugging
3. **Tournament metadata**: Include round count and participating agents in prompt
4. **Failure semantics**: Synthesis failure returns exit code 4 with `[SYNTHESIS]` prefix
5. **Template enhancement**: ~~Update `final-synth.md` to include metadata placeholders~~ ✅ Already done
6. **Separate types**: Use dedicated `SynthesisContext` and `SynthesisResult` types (not extend existing)
7. **Participating agents**: Only agents that succeeded in the final round (their reviews are in `all_reviews.md`)

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

### Step 2: Create `SynthesisContext` Record

**File:** `src/main/java/dev/reviewarena/io/SynthesisContext.java` (NEW FILE)

Create a dedicated record for synthesis prompt context. This keeps synthesis concerns separate from round-based `TemplateContext`.

```java
package dev.reviewarena.io;

import java.util.HashMap;
import java.util.Map;

/**
 * Context for synthesis prompt template rendering.
 *
 * <p>Separate from {@link TemplateContext} because synthesis is not a tournament round.
 * Contains tournament metadata to provide context for the synthesizer agent.
 *
 * @param outputPath             path where synthesizer should write champion_review.md
 * @param allReviewsPath         path to final round's all_reviews.md
 * @param roundCount             total rounds completed (Round 0 + cross-pollination rounds)
 * @param crossPollinationRounds number of cross-pollination rounds (roundCount - 1)
 * @param participatingAgents    comma-separated list of agents that succeeded in final round
 */
public record SynthesisContext(
        String outputPath,
        String allReviewsPath,
        int roundCount,
        int crossPollinationRounds,
        String participatingAgents
) {
    /**
     * Compact constructor with validation.
     */
    public SynthesisContext {
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalArgumentException("outputPath must not be null or blank");
        }
        if (allReviewsPath == null || allReviewsPath.isBlank()) {
            throw new IllegalArgumentException("allReviewsPath must not be null or blank");
        }
        if (roundCount < 1) {
            throw new IllegalArgumentException("roundCount must be at least 1");
        }
        if (crossPollinationRounds < 0) {
            throw new IllegalArgumentException("crossPollinationRounds must be non-negative");
        }
        if (roundCount != crossPollinationRounds + 1) {
            throw new IllegalArgumentException(
                "roundCount must equal crossPollinationRounds + 1, got roundCount="
                + roundCount + ", crossPollinationRounds=" + crossPollinationRounds);
        }
        if (participatingAgents == null || participatingAgents.isBlank()) {
            throw new IllegalArgumentException("participatingAgents must not be null or blank");
        }
    }

    /**
     * Converts this context to a map suitable for FreeMarker template processing.
     *
     * @return a map of placeholder names to values
     */
    public Map<String, Object> toDataModel() {
        Map<String, Object> model = new HashMap<>();
        model.put("outputPath", outputPath);
        model.put("allReviewsPath", allReviewsPath);
        model.put("roundCount", roundCount);
        model.put("crossPollinationRounds", crossPollinationRounds);
        model.put("participatingAgents", participatingAgents);
        return model;
    }
}

---

### Step 3: Update `TemplateLoader` to Support `SynthesisContext`

**File:** `src/main/java/dev/reviewarena/io/TemplateLoader.java`

Add an overloaded `render()` method that accepts `SynthesisContext`:

```java
/**
 * Renders a template with the given synthesis context.
 *
 * @param templateName the template file name (e.g., "final-synth.md")
 * @param context      the synthesis context with placeholder values
 * @return the rendered template content
 * @throws TemplateException if rendering fails
 */
public String render(String templateName, SynthesisContext context) {
    return render(templateName, context.toDataModel());
}

/**
 * Renders a template with a raw data model map.
 *
 * @param templateName the template file name
 * @param dataModel    the placeholder values
 * @return the rendered template content
 * @throws TemplateException if rendering fails
 */
public String render(String templateName, Map<String, Object> dataModel) {
    // ... existing FreeMarker rendering logic
}
```

---

### Step 4: Add `generateSynthesisPrompt()` to WorkspaceManager

**File:** `src/main/java/dev/reviewarena/io/WorkspaceManager.java`

**Step 4a: Add constant for synthesis template:**

Add constant alongside existing TASK_TEMPLATE:
```java
private static final String TASK_TEMPLATE = "task.md";
private static final String SYNTHESIS_TEMPLATE = "final-synth.md";
```

**Note:** The `config` field (ArenaConfig) is already available in WorkspaceManager. The method uses `config.outputDir()` to build relative paths, ensuring custom output directories work correctly.

**Step 4b: Add method to generate synthesis prompt:**

```java
/**
 * Generates the synthesis prompt at runtime (after tournament completes).
 *
 * @param finalRound           the last completed round number
 * @param participatingAgents  set of agents that succeeded in the final round
 * @return the path to the generated synthesis prompt
 * @throws WorkspaceException if prompt generation fails
 */
public Path generateSynthesisPrompt(int finalRound, Set<String> participatingAgents) {
    try {
        Path finalDir = getFinalDir();
        Path promptPath = finalDir.resolve("prompt.md");
        Path allReviewsFile = getRoundDir(finalRound).resolve("all_reviews.md");

        // Validate all_reviews.md exists before synthesis
        if (!Files.exists(allReviewsFile)) {
            throw new WorkspaceException(
                "Final round reviews not found: " + allReviewsFile +
                ". Ensure cross-pollination completed successfully.");
        }

        // Build relative paths using configured output dir (not hardcoded .arena)
        String outputDir = config.outputDir();  // e.g., ".arena" or custom path
        String allReviewsRelative = outputDir + "/rounds/round-" + finalRound + "/all_reviews.md";
        String outputRelative = outputDir + "/rounds/final/champion_review.md";

        // Calculate round counts
        int roundCount = finalRound + 1;  // Round 0 + cross-pollination rounds
        int crossPollinationRounds = finalRound;  // Rounds 1 through N

        // Format participating agents (only those who succeeded in final round)
        String agentsList = participatingAgents.stream()
            .sorted()
            .collect(java.util.stream.Collectors.joining(", "));

        // Get task.md content to prepend
        String taskContent = templateLoader.render(TASK_TEMPLATE, TemplateContext.forTask());

        // Render synthesis template using SynthesisContext
        SynthesisContext ctx = new SynthesisContext(
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

### Step 5: Create `SynthesisResult` Record and Add `executeSynthesis()` to AgentExecutor

**File:** `src/main/java/dev/reviewarena/agent/SynthesisResult.java` (NEW FILE)

Create a dedicated result type for synthesis. This avoids the `AgentResult` constraint that `round >= 0`.

```java
package dev.reviewarena.agent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of synthesis execution.
 *
 * <p>Separate from {@link AgentResult} because synthesis is not a tournament round.
 * AgentResult requires a non-negative round number, which doesn't apply to synthesis.
 *
 * @param agentName     the synthesizer agent name (always "claude")
 * @param success       whether synthesis completed successfully
 * @param durationMs    execution duration in milliseconds
 * @param outputFile    path to champion_review.md (null if failed)
 * @param failureReason description of failure (null if success)
 */
public record SynthesisResult(
        String agentName,
        boolean success,
        long durationMs,
        Path outputFile,
        String failureReason
) {
    public SynthesisResult {
        Objects.requireNonNull(agentName, "agentName must not be null");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
        if (success && outputFile == null) {
            throw new IllegalArgumentException("outputFile required for successful synthesis");
        }
        if (!success && failureReason == null) {
            throw new IllegalArgumentException("failureReason required for failed synthesis");
        }
    }

    public static SynthesisResult success(String agentName, long durationMs, Path outputFile) {
        return new SynthesisResult(agentName, true, durationMs, outputFile, null);
    }

    public static SynthesisResult failed(String agentName, long durationMs, String reason) {
        return new SynthesisResult(agentName, false, durationMs, null, reason);
    }

    public static SynthesisResult timeout(String agentName, long durationMs) {
        return new SynthesisResult(agentName, false, durationMs, null, "Synthesis timed out");
    }
}
```

---

**File:** `src/main/java/dev/reviewarena/agent/AgentExecutor.java`

**Step 5a: Add synthesis execution method (returns SynthesisResult):**

```java
/**
 * Executes the synthesis step using the specified agent.
 *
 * @param agentName  the name of the agent to use for synthesis (must be "claude")
 * @param promptPath the path to the synthesis prompt
 * @param outputPath the path where champion_review.md should be written
 * @return the synthesis result
 * @throws AgentException if agent is not found or disabled
 */
public SynthesisResult executeSynthesis(String agentName, Path promptPath, Path outputPath) {
    AgentConfig agentConfig = config.agents().get(agentName);
    if (agentConfig == null || !agentConfig.enabled()) {
        throw new AgentException("Synthesizer agent '" + agentName + "' not found or disabled");
    }

    log.info("[SYNTHESIS] Starting synthesis with agent: {}", agentName);

    Path finalDir = workspace.getFinalDir();
    List<String> command = commandBuilder.build(agentConfig, promptPath, outputPath);

    long startTime = System.currentTimeMillis();

    // Use a dedicated synthesis process execution (not AgentProcess which requires round >= 0)
    try {
        ProcessBuilder pb = new ProcessBuilder(command)
            .directory(workspace.getArenaDir().getParent().toFile())
            .redirectOutput(finalDir.resolve("synthesis-stdout.log").toFile())
            .redirectError(finalDir.resolve("synthesis-stderr.log").toFile());

        Process process = pb.start();
        boolean finished = process.waitFor(config.agentTimeoutMs(), TimeUnit.MILLISECONDS);

        long durationMs = System.currentTimeMillis() - startTime;

        if (!finished) {
            process.destroyForcibly();
            log.error("[SYNTHESIS] Synthesis timed out after {}ms", durationMs);
            return SynthesisResult.timeout(agentName, durationMs);
        }

        int exitCode = process.exitValue();

        // Validate output
        if (exitCode != 0) {
            log.error("[SYNTHESIS] Synthesis failed with exit code {}", exitCode);
            return SynthesisResult.failed(agentName, durationMs, "Exit code: " + exitCode);
        }

        if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
            log.error("[SYNTHESIS] Synthesis produced no output");
            return SynthesisResult.failed(agentName, durationMs, "No output produced");
        }

        log.info("[SYNTHESIS] Synthesis completed successfully in {}ms", durationMs);
        return SynthesisResult.success(agentName, durationMs, outputPath);

    } catch (IOException | InterruptedException e) {
        long durationMs = System.currentTimeMillis() - startTime;
        log.error("[SYNTHESIS] Synthesis execution error: {}", e.getMessage());
        return SynthesisResult.failed(agentName, durationMs, e.getMessage());
    }
}

/**
 * Validates that the synthesis agent is available.
 * Per spec: "Claude is always used for this step regardless of which agents participated."
 * The synthesis agent is configured as a separate entry named "synthesis" with type: claude.
 *
 * @throws AgentException if the synthesis agent is not configured or not enabled
 */
public void validateSynthesizerAvailable() {
    AgentConfig synthesis = config.agents().get("synthesis");
    if (synthesis == null) {
        throw new AgentException(
            "Final synthesis requires Claude CLI. Ensure 'synthesis' agent is configured in arena.yaml.");
    }
    if (!synthesis.enabled()) {
        throw new AgentException(
            "Final synthesis requires Claude CLI. The 'synthesis' agent is configured but disabled.");
    }
}

/**
 * Gets the synthesizer agent name.
 * Per spec: Claude is always used for synthesis.
 * The synthesis agent is a separate config entry (type: claude) distinct from review agents.
 *
 * @return "synthesis" (the dedicated synthesizer agent)
 * @throws AgentException if the synthesis agent is not available
 */
public String getSynthesizerAgent() {
    validateSynthesizerAvailable();
    return "synthesis";
}
```

> **Note:** The `synthesis` agent uses `type: claude` and is a separate config entry from review agents.
> It is not included in the `review-agents` list and cannot be used as a review-agent shorthand.

---

### Step 6: Fix Hardcoded Paths in `regenerateRoundPrompts()`

**File:** `src/main/java/dev/reviewarena/io/WorkspaceManager.java`

Update the hardcoded `.arena` paths to use `config.outputDir()`:

```java
// In regenerateRoundPrompts() method, change:
String outputPath = ".arena/rounds/round-" + round + "/" + agentName + "/review.md";
String allReviewsPath = ".arena/rounds/round-" + (round - 1) + "/all_reviews.md";

// To:
String outputDir = config.outputDir();
String outputPath = outputDir + "/rounds/round-" + round + "/" + agentName + "/review.md";
String allReviewsPath = outputDir + "/rounds/round-" + (round - 1) + "/all_reviews.md";
```

Also update `generateAllRoundPrompts()` similarly (lines 297-299).

---

### Step 7: Integrate Synthesis into ReviewArenaCli

**File:** `src/main/java/dev/reviewarena/cli/ReviewArenaCli.java`

**Step 7a: Track final round results for synthesis metadata:**

Inside the cross-pollination loop, save the last round's results:

```java
// Add variable before the loop:
Map<String, AgentResult> lastRoundResults = null;

// At the end of the loop body, save results:
lastRoundResults = roundResults;
lastCompletedRound = round;
```

**Step 7b: Replace the TODO comments with synthesis implementation:**

Replace the "TOURNAMENT COMPLETE" section (currently lines 285-290) with:

```java
// === TOURNAMENT COMPLETE - START SYNTHESIS ===
Path finalAllReviews = workspaceManager.getRoundDir(lastCompletedRound).resolve("all_reviews.md");
log.info("Cross-pollination complete! Final reviews: {}", workspaceManager.relativize(finalAllReviews));

// Get agents that succeeded in the final round (their reviews are in all_reviews.md)
Set<String> finalRoundSuccesses = AgentExecutor.getSuccessfulAgents(lastRoundResults);

// Validate and get synthesizer agent (Claude required per spec)
String synthesizerAgent;
try {
    synthesizerAgent = executor.getSynthesizerAgent();
    log.info("[SYNTHESIS] Using synthesizer: {}", synthesizerAgent);
} catch (AgentException e) {
    log.error("[SYNTHESIS] {}", e.getMessage());
    return 4;
}

// Generate synthesis prompt (participatingAgents = final round successes)
Path promptPath;
try {
    promptPath = workspaceManager.generateSynthesisPrompt(lastCompletedRound, finalRoundSuccesses);
    log.info("[SYNTHESIS] Prompt generated: {}", workspaceManager.relativize(promptPath));
} catch (WorkspaceException e) {
    log.error("[SYNTHESIS] Failed to generate synthesis prompt: {}", e.getMessage());
    return 4;
}

// Execute synthesis (returns SynthesisResult, not AgentResult)
Path championReviewPath = workspaceManager.getChampionReviewPath();
SynthesisResult synthesisResult = executor.executeSynthesis(synthesizerAgent, promptPath, championReviewPath);

if (!synthesisResult.success()) {
    log.error("[SYNTHESIS] Synthesis failed: {}", synthesisResult.failureReason());
    return 4;
}

log.info("Tournament complete! Champion review: {}", workspaceManager.relativize(championReviewPath));

return 0;
```

**Step 7c: Add necessary imports:**

```java
import dev.reviewarena.agent.SynthesisResult;
import dev.reviewarena.io.WorkspaceException;
```

---

### Step 8: Update Dry-Run Output

**File:** `src/main/java/dev/reviewarena/cli/ReviewArenaCli.java`

Update `printDryRunSummary()` to show synthesis step:

Replace the tournament flow section (lines 341-346) with:

```java
log.info("Tournament flow:");
log.info("  1. Round 0: Independent reviews (all agents)");
for (int i = 1; i <= config.maxRounds(); i++) {
    log.info("  {}. Round {}: Cross-pollination (surviving agents)", i + 1, i);
}
log.info("  {}. Final synthesis: Champion review (claude)", config.maxRounds() + 2);
log.info("Synthesis agent: claude (required)");
```

---

### Step 9: Create GitHub Issue for Agent Startup Validation

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
| `final-synth.md` | `resources/prompts` | ✅ Already done - tournament metadata placeholders |
| `SynthesisContext.java` | `dev.reviewarena.io` | **NEW** - Record for synthesis prompt context |
| `SynthesisResult.java` | `dev.reviewarena.agent` | **NEW** - Record for synthesis execution result |
| `TemplateLoader.java` | `dev.reviewarena.io` | Add overloaded `render(SynthesisContext)` method |
| `WorkspaceManager.java` | `dev.reviewarena.io` | Add `generateSynthesisPrompt()`, `getChampionReviewPath()`; fix hardcoded `.arena` paths |
| `AgentExecutor.java` | `dev.reviewarena.agent` | Add `executeSynthesis()`, `validateSynthesizerAvailable()`, `getSynthesizerAgent()` |
| `ReviewArenaCli.java` | `dev.reviewarena.cli` | Replace TODO with synthesis logic, track final round successes, update dry-run output |

### Test Code

| File | Package | Changes |
|------|---------|---------|
| `SynthesisContextTest.java` | `dev.reviewarena.io` | **NEW** - Tests for SynthesisContext validation and toDataModel() |
| `SynthesisResultTest.java` | `dev.reviewarena.agent` | **NEW** - Tests for SynthesisResult factory methods |
| `WorkspaceManagerTest.java` | `dev.reviewarena.io` | Add tests for `generateSynthesisPrompt()`, hardcoded path fix |
| `AgentExecutorTest.java` | `dev.reviewarena.agent` | Add tests for `executeSynthesis()`, `getSynthesizerAgent()`, `validateSynthesizerAvailable()` |
| `ReviewArenaCliIT.java` | `dev.reviewarena.cli` | Add integration tests for full tournament with synthesis |

---

## Testing Strategy

### Unit Tests

1. **SynthesisContextTest.java:** (NEW)
   - `testConstructor_validInputs_succeeds`
   - `testConstructor_nullOutputPath_throws`
   - `testConstructor_nullAllReviewsPath_throws`
   - `testConstructor_invalidRoundCount_throws`
   - `testConstructor_roundCountMismatch_throws`
   - `testConstructor_nullParticipatingAgents_throws`
   - `testToDataModel_includesAllFields`

2. **SynthesisResultTest.java:** (NEW)
   - `testSuccess_createsValidResult`
   - `testSuccess_nullOutputFile_throws`
   - `testFailed_createsValidResult`
   - `testFailed_nullReason_throws`
   - `testTimeout_createsValidResult`

3. **WorkspaceManagerTest.java:**
   - `testGenerateSynthesisPrompt_createsPromptFile`
   - `testGenerateSynthesisPrompt_includesTaskContent`
   - `testGenerateSynthesisPrompt_includesMetadata`
   - `testGenerateSynthesisPrompt_usesConfigOutputDir` (not hardcoded `.arena`)
   - `testGenerateSynthesisPrompt_missingAllReviews_throwsWorkspaceException`
   - `testGetChampionReviewPath_correctLocation`
   - `testRegenerateRoundPrompts_usesConfigOutputDir` (fix hardcoded paths)

4. **AgentExecutorTest.java:**
   - `testValidateSynthesizerAvailable_claudeConfiguredAndEnabled_succeeds`
   - `testValidateSynthesizerAvailable_claudeNotConfigured_throwsAgentException`
   - `testValidateSynthesizerAvailable_claudeDisabled_throwsAgentException`
   - `testGetSynthesizerAgent_returnsClaude`
   - `testExecuteSynthesis_successfulExecution_returnsSynthesisResult`
   - `testExecuteSynthesis_logsWithSynthesisPrefix`
   - `testExecuteSynthesis_timeout_returnsFailedResult`

### Integration Tests

1. **Full tournament flow:**
   - `testFullTournament_withSynthesis_producesChampionReview`
   - `testFullTournament_synthesisFailure_returnsExitCode4`
   - `testFullTournament_claudeNotConfigured_returnsExitCode4`
   - `testFullTournament_claudeDisabled_returnsExitCode4`

2. **Edge cases:**
   - `testSynthesis_singleAgentRemaining_stillUsesClaude`
   - `testSynthesis_claudeDidNotParticipate_stillUsesClaude`

---

## Exit Code Mapping

| Scenario | Exit Code | Log Prefix | Message |
|----------|-----------|------------|---------|
| Success (synthesis complete) | 0 | - | "Tournament complete! Champion review: ..." |
| Synthesis agent not configured | 4 | [SYNTHESIS] | "Final synthesis requires Claude CLI. Ensure 'synthesis' agent is configured in arena.yaml." |
| Synthesis agent disabled | 4 | [SYNTHESIS] | "Final synthesis requires Claude CLI. The 'synthesis' agent is configured but disabled." |
| `all_reviews.md` missing | 4 | [SYNTHESIS] | "Final round reviews not found: <path>. Ensure cross-pollination completed successfully." |
| Synthesis prompt generation failed | 4 | [SYNTHESIS] | "Failed to generate synthesis prompt: ..." |
| Synthesis execution failed | 4 | [SYNTHESIS] | "Synthesis failed: <reason>" |

---

## Acceptance Criteria

The feature is complete when:

- [x] `final-synth.md` template includes tournament metadata placeholders ✅
- [x] `SynthesisContext` record created with validation and `toDataModel()` ✅
- [x] `SynthesisResult` record created with factory methods ✅
- [x] `TemplateLoader.render(SynthesisContext)` overload added ✅
- [x] `WorkspaceManager.generateSynthesisPrompt()` writes to `.arena/rounds/final/prompt.md` ✅
- [x] `WorkspaceManager.generateSynthesisPrompt()` uses `config.outputDir()` (not hardcoded `.arena`) ✅
- [x] `WorkspaceManager.generateSynthesisPrompt()` validates `all_reviews.md` exists ✅
- [x] `WorkspaceManager.regenerateRoundPrompts()` fixed to use `config.outputDir()` ✅
- [x] `AgentExecutor.getSynthesizerAgent()` returns "synthesis" agent (type: claude, required per spec, no fallback) ✅
- [x] `AgentExecutor.executeSynthesis()` returns `SynthesisResult` ✅
- [x] `ReviewArenaCli` tracks final round successes for synthesis metadata ✅
- [x] `ReviewArenaCli` integrates synthesis after cross-pollination ✅
- [x] `champion_review.md` is written to `.arena/rounds/final/` ✅
- [x] Synthesis logs use `[SYNTHESIS]` prefix ✅
- [x] Dry-run shows complete tournament flow including synthesis ✅
- [x] Exit code 4 on synthesis failure ✅
- [x] All tests pass (unit + integration) ✅
- [ ] GitHub issue created for agent startup validation

---

## Implementation Order

1. ~~Update `final-synth.md` template with metadata placeholders~~ ✅ **DONE** (2025-12-29)
2. Create `SynthesisContext` record (new file)
3. Create `SynthesisResult` record (new file)
4. Add `render(SynthesisContext)` to `TemplateLoader`
5. Add `generateSynthesisPrompt()` and `getChampionReviewPath()` to `WorkspaceManager`
6. Fix hardcoded `.arena` paths in `regenerateRoundPrompts()` and `generateAllRoundPrompts()`
7. Add `getSynthesizerAgent()`, `validateSynthesizerAvailable()`, and `executeSynthesis()` to `AgentExecutor`
8. Integrate synthesis into `ReviewArenaCli.call()` (track final round successes)
9. Update dry-run output
10. Write unit tests for new types
11. Write unit tests for modified classes
12. Write integration tests
13. Create GitHub issue for agent validation

---

## Notes

### Prompt Persistence Rationale

Writing the synthesis prompt to `.arena/rounds/final/prompt.md` provides:
- **Debugging**: Easy to inspect what prompt was sent to the synthesizer
- **Audit trail**: Complete record of tournament inputs/outputs
- **Reproducibility**: Can manually re-run synthesis with the saved prompt

### Synthesizer Selection Logic

Per the spec, **Claude is required** for the synthesis step:

> "After the last round, **Claude** runs one more agent process in the dedicated synthesizer role. Claude is always used for this step regardless of which agents participated in the tournament rounds."

**Behavior:**
- If Claude is configured and enabled → use Claude for synthesis
- If Claude is not configured → fail with exit code 4
- If Claude is configured but disabled → fail with exit code 4

**Rationale:** Using a single, consistent agent for synthesis ensures deterministic output format and avoids ambiguity about which agent produces the final deliverable. Claude's synthesis quality is explicitly preferred by the spec.

### Why Separate Types (SynthesisContext, SynthesisResult)

Originally the plan extended `TemplateContext` with nullable fields. After review, separate types are cleaner:

**SynthesisContext** (instead of extending TemplateContext):
- Synthesis is not a round - different semantics
- TemplateContext has 7 fields; adding 3 more for synthesis pollutes it
- Separate record has clear validation and no nullable field complexity
- Factory methods on TemplateContext would need to pass many nulls

**SynthesisResult** (instead of using AgentResult):
- `AgentResult` requires `round >= 0` (throws on negative)
- Synthesis has no round number - it's a post-tournament step
- Cleaner to have dedicated type than special-case AgentResult validation
- Original plan's `round(-1)` would crash at runtime

### Edge Cases

| Scenario | Behavior |
|----------|----------|
| Claude is the only surviving agent | Valid - Claude synthesizes its own review (degenerate but correct) |
| Claude did not participate in rounds | Valid - Claude still used for synthesis (per spec) |
| All agents failed in final round | Tournament aborts before synthesis (min-agents check) |
| Empty `all_reviews.md` | Should not happen if min-agents >= 1; synthesis would produce minimal output |
| `all_reviews.md` missing | Fail with WorkspaceException before synthesis starts (defensive check) |
| Custom output dir (`--output /custom`) | Paths in prompt use `config.outputDir()`, not hardcoded `.arena` |

### Participating Agents Definition

**"Participating agents"** in the synthesis prompt means **agents that succeeded in the final cross-pollination round**:
- These are the agents whose reviews are actually in `all_reviews.md`
- Agents that failed/timed out in the final round are excluded
- This provides accurate metadata to the synthesizer

This is tracked in the CLI via:
```java
Set<String> finalRoundSuccesses = AgentExecutor.getSuccessfulAgents(lastRoundResults);
```
