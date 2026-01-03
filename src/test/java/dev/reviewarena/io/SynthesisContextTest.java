package dev.reviewarena.io;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SynthesisContextTest {

    @Test
    void constructor_validInputs_succeeds() {
        SynthesisContext ctx = new SynthesisContext(
            ".arena/rounds/final/champion_review.md",
            ".arena/rounds/round-2/all_reviews.md",
            3, 2, "claude, codex, gemini"
        );

        assertEquals(".arena/rounds/final/champion_review.md", ctx.outputPath());
        assertEquals(".arena/rounds/round-2/all_reviews.md", ctx.allReviewsPath());
        assertEquals(3, ctx.roundCount());
        assertEquals(2, ctx.crossPollinationRounds());
        assertEquals("claude, codex, gemini", ctx.participatingAgents());
    }

    @Test
    void constructor_nullOutputPath_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(null, ".arena/rounds/round-1/all_reviews.md", 2, 1, "claude"));
        assertTrue(ex.getMessage().contains("outputPath"));
    }

    @Test
    void constructor_blankOutputPath_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext("   ", ".arena/rounds/round-1/all_reviews.md", 2, 1, "claude"));
        assertTrue(ex.getMessage().contains("outputPath"));
    }

    @Test
    void constructor_nullAllReviewsPath_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(".arena/rounds/final/champion_review.md", null, 2, 1, "claude"));
        assertTrue(ex.getMessage().contains("allReviewsPath"));
    }

    @Test
    void constructor_blankAllReviewsPath_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(".arena/rounds/final/champion_review.md", "", 2, 1, "claude"));
        assertTrue(ex.getMessage().contains("allReviewsPath"));
    }

    @Test
    void constructor_invalidRoundCount_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(".arena/out.md", ".arena/in.md", 0, -1, "claude"));
        assertTrue(ex.getMessage().contains("roundCount"));
    }

    @Test
    void constructor_negativeCrossPollinationRounds_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(".arena/out.md", ".arena/in.md", 1, -1, "claude"));
        assertTrue(ex.getMessage().contains("crossPollinationRounds"));
    }

    @Test
    void constructor_roundCountMismatch_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(".arena/out.md", ".arena/in.md", 3, 1, "claude"));
        assertTrue(ex.getMessage().contains("roundCount must equal crossPollinationRounds + 1"));
    }

    @Test
    void constructor_nullParticipatingAgents_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(".arena/out.md", ".arena/in.md", 2, 1, null));
        assertTrue(ex.getMessage().contains("participatingAgents"));
    }

    @Test
    void constructor_blankParticipatingAgents_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisContext(".arena/out.md", ".arena/in.md", 2, 1, "  "));
        assertTrue(ex.getMessage().contains("participatingAgents"));
    }

    @Test
    void toDataModel_includesAllFields() {
        SynthesisContext ctx = new SynthesisContext(
            ".arena/rounds/final/champion_review.md",
            ".arena/rounds/round-5/all_reviews.md",
            6, 5, "claude, gemini"
        );

        Map<String, Object> model = ctx.toDataModel();

        assertEquals(".arena/rounds/final/champion_review.md", model.get("outputPath"));
        assertEquals(".arena/rounds/round-5/all_reviews.md", model.get("allReviewsPath"));
        assertEquals(6, model.get("roundCount"));
        assertEquals(5, model.get("crossPollinationRounds"));
        assertEquals("claude, gemini", model.get("participatingAgents"));
        assertEquals(5, model.size());
    }
}
