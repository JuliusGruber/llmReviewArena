# Prompt Templating Implementation Plan

## Overview

Implement `TemplateLoader` using **Freemarker** to resolve placeholders in prompt templates (`.md` files).

## Technology Choice

| Aspect | Decision |
|--------|----------|
| Templating Engine | Freemarker 2.3.32 |
| Placeholder Syntax | `${variableName}` (Freemarker default) |
| Template Location | `src/main/resources/prompts/` |
| File Format | Markdown (`.md`) |

## Maven Dependency

```xml
<dependency>
    <groupId>org.freemarker</groupId>
    <artifactId>freemarker</artifactId>
    <version>2.3.32</version>
</dependency>
```

---

## Component Design

### TemplateLoader Class

**Location:** `src/main/java/dev/reviewarena/io/TemplateLoader.java`

**Single public method:**

```java
public class TemplateLoader {

    /**
     * Loads a template from classpath and resolves all placeholders.
     *
     * @param templateName template file name (e.g., "task.md", "round-0.md")
     * @param context the template context containing placeholder values
     * @return the resolved template content
     * @throws TemplateException if template loading or processing fails
     */
    public String render(String templateName, TemplateContext context);
}
```

### TemplateContext Record

**Location:** `src/main/java/dev/reviewarena/io/TemplateContext.java`

```java
public record TemplateContext(
    String reviewTarget,    // "abc1234", "abc1234..def5678", or "--staged"
    int fileCount,          // Number of files changed
    int roundNumber,        // 0, 1, 2, ... N
    String agentName,       // "claude", "codex", "gemini"
    String outputPath,      // ".arena/rounds/round-0/claude/review.md"
    String allReviewsPath   // ".arena/rounds/round-0/all_reviews.md" (null for round 0)
) {
    /**
     * Creates a context for task.md generation (workspace setup).
     */
    public static TemplateContext forTask(String reviewTarget, int fileCount) {
        return new TemplateContext(reviewTarget, fileCount, -1, null, null, null);
    }

    /**
     * Creates a context for round prompt generation.
     */
    public static TemplateContext forRound(
            String reviewTarget,
            int fileCount,
            int roundNumber,
            String agentName,
            String outputPath,
            String allReviewsPath) {
        return new TemplateContext(reviewTarget, fileCount, roundNumber,
                                   agentName, outputPath, allReviewsPath);
    }
}
```

### TemplateException

**Location:** `src/main/java/dev/reviewarena/io/TemplateException.java`

```java
public class TemplateException extends RuntimeException {
    public TemplateException(String message) { super(message); }
    public TemplateException(String message, Throwable cause) { super(message, cause); }
}
```

---

## Template Updates

### task.md

Update to include placeholders:

```markdown
# Code Review Arena - Task

You are participating in a multi-round code review arena.

## Review Target
`${reviewTarget}`

## Files Changed
${fileCount} file(s) changed

## Goal
Produce the highest-quality, most useful code review possible.
...
```

### round-0.md through round-5.md

Add output path placeholder:

```markdown
You are an expert software engineer performing a rigorous code review.

This is Round ${roundNumber}.
...

Write your output to `${outputPath}` following the required structure.
```

### round-1.md and later

Add reference to previous reviews:

```markdown
This is Round ${roundNumber} of a multi-round code review arena.

You are given:
- the original code under review
- a file containing reviews from other agents: `${allReviewsPath}`
...

Write a complete, standalone review to `${outputPath}`
```

---

## Implementation Details

### Freemarker Configuration

```java
public class TemplateLoader {
    private static final String TEMPLATE_PATH = "prompts";
    private final Configuration freemarkerConfig;

    public TemplateLoader() {
        freemarkerConfig = new Configuration(Configuration.VERSION_2_3_32);
        freemarkerConfig.setClassLoaderForTemplateLoading(
            getClass().getClassLoader(), TEMPLATE_PATH);
        freemarkerConfig.setDefaultEncoding("UTF-8");
        freemarkerConfig.setTemplateExceptionHandler(
            TemplateExceptionHandler.RETHROW_HANDLER);
    }
}
```

### Render Method Implementation

```java
public String render(String templateName, TemplateContext context) {
    try {
        Template template = freemarkerConfig.getTemplate(templateName);
        Map<String, Object> dataModel = buildDataModel(context);

        StringWriter writer = new StringWriter();
        template.process(dataModel, writer);
        return writer.toString();
    } catch (IOException e) {
        throw new TemplateException("Failed to load template: " + templateName, e);
    } catch (freemarker.template.TemplateException e) {
        throw new TemplateException("Failed to process template: " + templateName, e);
    }
}

private Map<String, Object> buildDataModel(TemplateContext context) {
    Map<String, Object> model = new HashMap<>();
    model.put("reviewTarget", context.reviewTarget());
    model.put("fileCount", context.fileCount());

    if (context.roundNumber() >= 0) {
        model.put("roundNumber", context.roundNumber());
    }
    if (context.agentName() != null) {
        model.put("agentName", context.agentName());
    }
    if (context.outputPath() != null) {
        model.put("outputPath", context.outputPath());
    }
    if (context.allReviewsPath() != null) {
        model.put("allReviewsPath", context.allReviewsPath());
    }
    return model;
}
```

---

## Integration Points

### 1. WorkspaceManager

Update `generateTaskMd()` to use TemplateLoader:

```java
public class WorkspaceManager {
    private final TemplateLoader templateLoader;

    public WorkspaceManager(Path projectRoot, ArenaConfig config) {
        this.templateLoader = new TemplateLoader();
        // ...
    }

    private void generateTaskMd(Path arenaDir, String reviewTarget, int fileCount) {
        TemplateContext context = TemplateContext.forTask(reviewTarget, fileCount);
        String content = templateLoader.render("task.md", context);
        Files.writeString(arenaDir.resolve("task.md"), content, StandardCharsets.UTF_8);
    }
}
```

**Note:** WorkspaceManager.initialize() signature changes to accept `fileCount`:
```java
public Path initialize(String reviewTarget, int fileCount)
```

### 2. TournamentOrchestrator (Future)

Will use TemplateLoader to build agent prompts:

```java
TemplateContext context = TemplateContext.forRound(
    reviewTarget, fileCount, roundNumber, agentName, outputPath, allReviewsPath);

String taskContent = templateLoader.render("task.md", context);
String roundContent = templateLoader.render("round-" + roundNumber + ".md", context);

String fullPrompt = taskContent + "\n\n---\n\n" + roundContent;
```

---

## File Count Retrieval

The `fileCount` placeholder requires git diff information. Options:

### Option A: GitService provides file count

Add method to GitService:

```java
public int getChangedFileCount(String ref1, String ref2) {
    // Use JGit to count files in diff
}

public int getStagedFileCount() {
    // Use JGit to count staged files
}
```

### Option B: Pass through CLI

CLI calls GitService, passes count to WorkspaceManager:

```java
// In ReviewArenaCli.call()
int fileCount = gitService.getChangedFileCount(ref1, ref2);
workspaceManager.initialize(reviewTarget, fileCount);
```

**Recommendation:** Option B - keeps WorkspaceManager decoupled from GitService.

---

## Testing Strategy

### TemplateLoaderTest

```java
class TemplateLoaderTest {

    @Test
    void render_taskTemplate_resolvesPlaceholders() {
        TemplateLoader loader = new TemplateLoader();
        TemplateContext context = TemplateContext.forTask("abc1234", 5);

        String result = loader.render("task.md", context);

        assertThat(result).contains("`abc1234`");
        assertThat(result).contains("5 file(s) changed");
    }

    @Test
    void render_roundTemplate_resolvesAllPlaceholders() {
        TemplateLoader loader = new TemplateLoader();
        TemplateContext context = TemplateContext.forRound(
            "abc1234", 5, 1, "claude",
            ".arena/rounds/round-1/claude/review.md",
            ".arena/rounds/round-0/all_reviews.md");

        String result = loader.render("round-1.md", context);

        assertThat(result).contains("Round 1");
        assertThat(result).contains(".arena/rounds/round-1/claude/review.md");
    }

    @Test
    void render_missingTemplate_throwsException() {
        TemplateLoader loader = new TemplateLoader();
        TemplateContext context = TemplateContext.forTask("abc", 1);

        assertThrows(TemplateException.class,
            () -> loader.render("nonexistent.md", context));
    }
}
```

---

## Implementation Order

| Step | Task | Files |
|------|------|-------|
| 1 | Add Freemarker dependency | `pom.xml` |
| 2 | Create TemplateException | `TemplateException.java` |
| 3 | Create TemplateContext record | `TemplateContext.java` |
| 4 | Implement TemplateLoader | `TemplateLoader.java` |
| 5 | Write TemplateLoader tests | `TemplateLoaderTest.java` |
| 6 | Update prompt templates with placeholders | `prompts/*.md` |
| 7 | Add file count method to GitService | `GitService.java` |
| 8 | Update WorkspaceManager integration | `WorkspaceManager.java` |
| 9 | Update CLI to pass file count | `ReviewArenaCli.java` |
| 10 | Run all tests | `mvn verify` |

---

## Placeholder Reference

| Placeholder | Type | Used In | Description |
|-------------|------|---------|-------------|
| `${reviewTarget}` | String | task.md | Git ref or `--staged` |
| `${fileCount}` | int | task.md | Number of changed files |
| `${roundNumber}` | int | round-N.md | Current round (0-indexed) |
| `${agentName}` | String | (future) | Agent identifier |
| `${outputPath}` | String | round-N.md | Where agent writes review |
| `${allReviewsPath}` | String | round-1+.md | Path to combined reviews |

---

## Notes

- Freemarker's `${var}` syntax is cleaner than `{{var}}` and has better tooling support
- Templates remain valid Markdown - placeholders render as text
- Freemarker provides good error messages for missing variables
- Can add conditionals later if needed: `<#if roundNumber == 0>...</#if>`
