# Prompt Pre-generation Plan

## Overview

Change the templating approach so **all round prompts are pre-generated at startup**, before agents begin working. One prompt file per round, shared by all agents.

## Current Approach (from templating impl plan)

- `task.md` generated at workspace init
- Round prompts generated on-demand during tournament execution
- Per-agent prompt generation

## New Approach

- **All prompts for all rounds** generated at workspace initialization
- **One prompt per round** shared by all agents
- Agents receive pre-rendered prompt files, not templates

---

## Directory Structure

```
.arena/
├── task.md                    # General task description
├── diff.patch                 # Code under review
├── prompts/                   # NEW - pre-rendered prompts
│   ├── round-0.md
│   ├── round-1.md
│   ├── round-2.md
│   ├── round-3.md
│   ├── round-4.md
│   └── round-5.md
└── rounds/                    # Agent outputs (unchanged)
    └── round-N/
        ├── {agent}/review.md
        └── all_reviews.md
```

---

## Prompt Content Strategy

Each pre-generated `round-N.md` contains a **complete, standalone prompt**:

```markdown
{task.md content - review target, file count, goals}

---

{round-N template content with placeholders resolved}
```

### Placeholders Resolved at Startup

| Placeholder | Value Source |
|-------------|--------------|
| `${reviewTarget}` | CLI arg |
| `${fileCount}` | GitService |
| `${roundNumber}` | Loop counter (0-5) |
| `${allReviewsPath}` | `.arena/rounds/round-{N-1}/all_reviews.md` (null for round 0) |

### Removed Placeholders

| Placeholder | Reason |
|-------------|--------|
| `${agentName}` | Not needed - prompt is agent-agnostic |
| `${outputPath}` | Orchestrator tells agent where to write, not the prompt |

---

## Implementation Changes

### 1. TemplateContext Simplification

```java
public record TemplateContext(
    String reviewTarget,
    int fileCount,
    int roundNumber,
    String allReviewsPath  // null for round 0
) {
    public static TemplateContext forTask(String reviewTarget, int fileCount) {
        return new TemplateContext(reviewTarget, fileCount, -1, null);
    }

    public static TemplateContext forRound(
            String reviewTarget,
            int fileCount,
            int roundNumber,
            String allReviewsPath) {
        return new TemplateContext(reviewTarget, fileCount, roundNumber, allReviewsPath);
    }
}
```

### 2. WorkspaceManager Changes

Add method to generate all prompts:

```java
private void generateAllRoundPrompts(Path arenaDir, String reviewTarget, int fileCount) {
    Path promptsDir = arenaDir.resolve("prompts");
    Files.createDirectories(promptsDir);

    String taskContent = templateLoader.render("task.md",
        TemplateContext.forTask(reviewTarget, fileCount));

    int maxRounds = config.getMaxRounds(); // e.g., 6

    for (int round = 0; round < maxRounds; round++) {
        String allReviewsPath = (round == 0) ? null
            : ".arena/rounds/round-" + (round - 1) + "/all_reviews.md";

        TemplateContext ctx = TemplateContext.forRound(
            reviewTarget, fileCount, round, allReviewsPath);

        String roundContent = templateLoader.render("round-" + round + ".md", ctx);

        String fullPrompt = taskContent + "\n\n---\n\n" + roundContent;

        Files.writeString(promptsDir.resolve("round-" + round + ".md"),
            fullPrompt, StandardCharsets.UTF_8);
    }
}
```

Update `initialize()`:

```java
public Path initialize(String reviewTarget, int fileCount) throws IOException {
    Path arenaDir = projectRoot.resolve(ARENA_DIR);
    Files.createDirectories(arenaDir);

    generateTaskMd(arenaDir, reviewTarget, fileCount);
    generateDiffPatch(arenaDir, reviewTarget);
    generateAllRoundPrompts(arenaDir, reviewTarget, fileCount);  // NEW

    return arenaDir;
}
```

### 3. TournamentOrchestrator Simplification

Reading prompts becomes trivial:

```java
public String getPromptForRound(int roundNumber) throws IOException {
    Path promptFile = arenaDir.resolve("prompts/round-" + roundNumber + ".md");
    return Files.readString(promptFile);
}
```

No template rendering at tournament time - just file reads.

### 4. Template File Updates

Remove `${agentName}` and `${outputPath}` from round templates. Keep:
- `${roundNumber}`
- `${allReviewsPath}` (for rounds 1+)

---

## Implementation Order

| Step | Task | Files |
|------|------|-------|
| 1 | Simplify TemplateContext (remove agentName, outputPath) | `TemplateContext.java` |
| 2 | Update round templates (remove agent-specific placeholders) | `prompts/round-*.md` |
| 3 | Add `generateAllRoundPrompts()` to WorkspaceManager | `WorkspaceManager.java` |
| 4 | Update `initialize()` to call prompt generation | `WorkspaceManager.java` |
| 5 | Update tests | `WorkspaceManagerTest.java`, `TemplateLoaderTest.java` |
| 6 | Run all tests | `mvn verify` |

---

## Benefits

- **Simpler**: One prompt per round, not per agent
- **Predictable**: All prompts visible before tournament starts
- **Debuggable**: User can inspect/edit `.arena/prompts/` before running
- **Simpler orchestrator**: Just reads files, no template logic
- **Atomic**: Workspace is fully ready or fails completely at init

---

## Notes

- Output path handling moves to orchestrator (tells agent where to write via different mechanism)
- Agent name not needed in prompt - all agents get same instructions
- `task.md` in root remains as standalone reference; combined version in `prompts/`
