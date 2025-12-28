package dev.reviewarena.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TemplateLoader}.
 */
class TemplateLoaderTest {

    private TemplateLoader templateLoader;

    @BeforeEach
    void setUp() {
        templateLoader = new TemplateLoader();
    }

    @Test
    void render_taskTemplate_returnsContent() {
        TemplateContext context = TemplateContext.forTask();

        String result = templateLoader.render("task.md", context);

        assertTrue(result.contains("Code Review Arena"),
                "Should contain title");
    }

    @Test
    void render_roundZeroTemplate_resolvesPlaceholders() {
        TemplateContext context = TemplateContext.forRound(
                0, ".arena/rounds/round-0/review.md", null, "", "", "");

        String result = templateLoader.render("round-0.md", context);

        assertTrue(result.contains("Round 0"),
                "Should contain round number");
        assertTrue(result.contains(".arena/rounds/round-0/review.md"),
                "Should contain output path");
    }

    @Test
    void render_roundOneTemplate_includesAllReviewsPath() {
        TemplateContext context = TemplateContext.forRound(
                1,
                ".arena/rounds/round-1/review.md",
                ".arena/rounds/round-0/all_reviews.md",
                "", "", "");

        String result = templateLoader.render("round-1.md", context);

        assertTrue(result.contains("Round 1"),
                "Should contain round number");
        assertTrue(result.contains(".arena/rounds/round-0/all_reviews.md"),
                "Should contain all reviews path");
        assertTrue(result.contains(".arena/rounds/round-1/review.md"),
                "Should contain output path");
    }

    @Test
    void render_missingTemplate_throwsTemplateException() {
        TemplateContext context = TemplateContext.forTask();

        TemplateException exception = assertThrows(TemplateException.class,
                () -> templateLoader.render("nonexistent.md", context));

        assertTrue(exception.getMessage().contains("nonexistent.md"),
                "Exception should mention the template name");
    }

    @Test
    void render_templateContextForTask_hasCorrectValues() {
        TemplateContext context = TemplateContext.forTask();

        assertEquals(-1, context.roundNumber());
        assertNull(context.outputPath());
        assertNull(context.allReviewsPath());
    }

    @Test
    void render_templateContextForRound_hasCorrectValues() {
        TemplateContext context = TemplateContext.forRound(
                2,
                ".arena/rounds/round-2/review.md",
                ".arena/rounds/round-1/all_reviews.md",
                "abc123", "def456", "");

        assertEquals(2, context.roundNumber());
        assertEquals(".arena/rounds/round-2/review.md", context.outputPath());
        assertEquals(".arena/rounds/round-1/all_reviews.md", context.allReviewsPath());
        assertEquals("abc123", context.commit1());
        assertEquals("def456", context.commit2());
    }

    @Test
    void toDataModel_forTaskContext_excludesRoundSpecificFields() {
        TemplateContext context = TemplateContext.forTask();

        var model = context.toDataModel();

        assertFalse(model.containsKey("roundNumber"),
                "Should not include roundNumber when -1");
        assertFalse(model.containsKey("outputPath"),
                "Should not include null outputPath");
        assertFalse(model.containsKey("allReviewsPath"),
                "Should not include null allReviewsPath");
    }

    @Test
    void toDataModel_forRoundContext_includesAllFields() {
        TemplateContext context = TemplateContext.forRound(
                1,
                ".arena/rounds/round-1/review.md",
                ".arena/rounds/round-0/all_reviews.md",
                "commit1", "commit2", "--staged");

        var model = context.toDataModel();

        assertEquals(1, model.get("roundNumber"));
        assertEquals(".arena/rounds/round-1/review.md", model.get("outputPath"));
        assertEquals(".arena/rounds/round-0/all_reviews.md", model.get("allReviewsPath"));
        assertEquals("commit1", model.get("commit1"));
        assertEquals("commit2", model.get("commit2"));
        assertEquals("--staged", model.get("stagedFlag"));
    }
}
