# Prompt Pre-generation Plan

## Overview

Change the templating approach so **all round prompts are pre-generated at startup**, before agents begin working. One prompt file per round, shared by all agents.

## Current Approach (from templating impl plan)

- `task.md` generated at workspace init
- Round prompts generated on-demand during tournament execution
- Per-agent prompt generation

## New Approach

- **All prompts for all rounds** generated at workspace initialization
- **Per-agent prompts** (`round-{n}-{name}.md`) for each round
- For rounds > 0, prompts are regenerated before execution with embedded previous review content
- Agents receive pre-rendered prompt files, not templates

---

## Directory Structure

```
.arena/
├── task.md                    # General task description
├── diff.patch                 # Code under review
├── prompts/                   # NEW - pre-rendered prompts (per agent)
│   ├── task.md
│   ├── round-0-claude-1.md
│   ├── round-0-claude-2.md
│   ├── round-0-claude-3.md
│   ├── round-1-claude-1.md
│   └── ...
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
| `${roundNumber}` | Loop counter (0-5) |
| `${outputPath}` | Computed per agent: `.arena/rounds/round-{N}/{agent}/review.md` |
| `${allReviewsPath}` | `.arena/rounds/round-{N-1}/all_reviews.md` (null for round 0) |
| `${previousReviewsContent}` | Embedded content of previous all_reviews.md (null for round 0) |
| `${commit1}` | CLI arg - first commit hash (empty if --staged) |
| `${commit2}` | CLI arg - second commit hash (empty for single/staged) |
| `${stagedFlag}` | CLI arg - "--staged" or empty string |

### Removed Placeholders

| Placeholder | Reason |
|-------------|--------|
| `${agentName}` | Not needed - agent identity is encoded in the filename |
| `${reviewTarget}` | Replaced by `${commit1}`, `${commit2}`, `${stagedFlag}` |
| `${fileCount}` | Replaced by `${commit1}`, `${commit2}`, `${stagedFlag}` |

---

## Implementation Changes

### 1. TemplateContext Simplification

```java
public record TemplateContext(
    int roundNumber,        // 0, 1, 2, ... N (-1 for task-only context)
    String outputPath,      // ".arena/rounds/round-0/claude-1/review.md"
    String allReviewsPath,  // ".arena/rounds/round-0/all_reviews.md" (null for round 0)
    String previousReviewsContent, // Embedded all_reviews.md content (null for round 0)
    String commit1,         // First commit hash (empty if --staged)
    String commit2,         // Second commit hash (empty for single commit or --staged)
    String stagedFlag       // "--staged" or empty string
) {
    public static TemplateContext forTask() {
        return new TemplateContext(-1, null, null, null, "", "", "");
    }

    public static TemplateContext forRound(
            int roundNumber,
            String outputPath,
            String allReviewsPath,
            String previousReviewsContent,
            String commit1,
            String commit2,
            String stagedFlag) {
        return new TemplateContext(roundNumber, outputPath, allReviewsPath,
                                   previousReviewsContent, commit1, commit2, stagedFlag);
    }
}
```

### 2. WorkspaceManager Changes

Add method to generate all prompts:

```java
private void generateAllRoundPrompts(Path arenaDir, String commit1, String commit2, String stagedFlag) {
    Path promptsDir = arenaDir.resolve("prompts");
    Files.createDirectories(promptsDir);

    String taskContent = templateLoader.render("task.md",
        TemplateContext.forTask());

    int maxRounds = config.getMaxRounds(); // e.g., 6

    for (int round = 0; round < maxRounds; round++) {
        for (String agentName : config.reviewAgents()) {
            String outputPath = ".arena/rounds/round-" + round + "/" + agentName + "/review.md";
            String allReviewsPath = (round == 0) ? null
                : ".arena/rounds/round-" + (round - 1) + "/all_reviews.md";
            String previousReviewsContent = null; // Populated at regeneration time for rounds > 0

            TemplateContext ctx = TemplateContext.forRound(
                round, outputPath, allReviewsPath, previousReviewsContent,
                commit1, commit2, stagedFlag);

            String roundContent = templateLoader.render("round-" + round + ".md", ctx);

            String fullPrompt = taskContent + "\n\n---\n\n" + roundContent;

            Files.writeString(promptsDir.resolve("round-" + round + "-" + agentName + ".md"),
                fullPrompt, StandardCharsets.UTF_8);
        }
    }
}
```

Update `initialize()`:

```java
public Path initialize(String commit1, String commit2, String stagedFlag) throws IOException {
    Path arenaDir = projectRoot.resolve(ARENA_DIR);
    Files.createDirectories(arenaDir);

    generateTaskMd(arenaDir, commit1, commit2, stagedFlag);
    generateDiffPatch(arenaDir, commit1, commit2, stagedFlag);
    generateAllRoundPrompts(arenaDir, commit1, commit2, stagedFlag);  // NEW

    return arenaDir;
}
```

### 3. TournamentOrchestrator Simplification

Reading prompts becomes trivial:

```java
public String getPromptForRound(int roundNumber, String agentName) throws IOException {
    Path promptFile = arenaDir.resolve("prompts/round-" + roundNumber + "-" + agentName + ".md");
    return Files.readString(promptFile);
}
```

No template rendering at tournament time - just file reads. For rounds > 0, prompts are regenerated before execution to embed `${previousReviewsContent}`.

### 4. Template File Updates

Remove `${agentName}` from round templates. Keep:
- `${roundNumber}`
- `${outputPath}`
- `${allReviewsPath}` (for rounds 1+)
- `${previousReviewsContent}` (for rounds 1+)
- `${commit1}`, `${commit2}`, `${stagedFlag}`

---

## Implementation Order

| Step | Task | Files |
|------|------|-------|
| 1 | Simplify TemplateContext (remove agentName, reviewTarget, fileCount; add commit1, commit2, stagedFlag, previousReviewsContent) | `TemplateContext.java` |
| 2 | Update round templates (remove agent-specific placeholders) | `prompts/round-*.md` |
| 3 | Add `generateAllRoundPrompts()` to WorkspaceManager | `WorkspaceManager.java` |
| 4 | Update `initialize()` to call prompt generation | `WorkspaceManager.java` |
| 5 | Update tests | `WorkspaceManagerTest.java`, `TemplateLoaderTest.java` |
| 6 | Run all tests | `mvn verify` |

---

## Benefits

- **Simpler**: Pre-rendered per-agent prompts, no runtime template logic
- **Predictable**: All prompts visible before tournament starts
- **Debuggable**: User can inspect/edit `.arena/prompts/` before running
- **Simpler orchestrator**: Just reads files, no template logic
- **Atomic**: Workspace is fully ready or fails completely at init

---

## Notes

- Output path handling moves to orchestrator (tells agent where to write via different mechanism)
- Agent name not needed in prompt - all agents get same instructions
- `task.md` in root remains as standalone reference; combined version in `prompts/`
