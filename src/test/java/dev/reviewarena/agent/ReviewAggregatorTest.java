package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.io.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewAggregatorTest {

    @TempDir
    Path tempDir;

    private WorkspaceManager workspace;
    private ReviewAggregator aggregator;

    @BeforeEach
    void setUp() {
        Map<String, AgentConfig> agents = Map.of(
            "claude", new AgentConfig("claude", List.of("claude"), Map.of(), true),
            "codex", new AgentConfig("codex", List.of("codex"), Map.of(), true)
        );
        ArenaConfig config = new ArenaConfig(
            2, 500, 0, 30_000, 90_000, 1_000, 2,
            Path.of(".arena"), agents
        );
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("abc123", "", "");
        aggregator = new ReviewAggregator(workspace);
    }

    @Test
    void aggregateRound_combinesSuccessfulReviews() throws IOException {
        // Create mock review files
        Path claudeReview = workspace.getAgentDir(0, "claude").resolve("review.md");
        Path codexReview = workspace.getAgentDir(0, "codex").resolve("review.md");
        Files.writeString(claudeReview, "# Summary\nClaude's review content.");
        Files.writeString(codexReview, "# Summary\nCodex's review content.");

        Map<String, AgentResult> results = Map.of(
            "claude", AgentResult.success("claude", 0, 0, 100, claudeReview),
            "codex", AgentResult.success("codex", 0, 0, 100, codexReview)
        );

        Path allReviews = aggregator.aggregateRound(0, results);

        assertTrue(Files.exists(allReviews));
        String content = Files.readString(allReviews);
        assertTrue(content.contains("# Claude"));
        assertTrue(content.contains("Claude's review content"));
        assertTrue(content.contains("# Codex"));
        assertTrue(content.contains("Codex's review content"));
    }

    @Test
    void aggregateRound_excludesFailedAgents() throws IOException {
        Path claudeReview = workspace.getAgentDir(0, "claude").resolve("review.md");
        Files.writeString(claudeReview, "Claude's content.");

        Map<String, AgentResult> results = Map.of(
            "claude", AgentResult.success("claude", 0, 0, 100, claudeReview),
            "codex", AgentResult.failed("codex", 0, 1, 100, "crashed")
        );

        Path allReviews = aggregator.aggregateRound(0, results);

        String content = Files.readString(allReviews);
        assertTrue(content.contains("# Claude"));
        assertFalse(content.contains("# Codex"));
    }

    @Test
    void aggregateRound_alphabeticalOrder() throws IOException {
        Path claudeReview = workspace.getAgentDir(0, "claude").resolve("review.md");
        Path codexReview = workspace.getAgentDir(0, "codex").resolve("review.md");
        Files.writeString(claudeReview, "Claude content");
        Files.writeString(codexReview, "Codex content");

        Map<String, AgentResult> results = Map.of(
            "codex", AgentResult.success("codex", 0, 0, 100, codexReview),
            "claude", AgentResult.success("claude", 0, 0, 100, claudeReview)
        );

        String content = Files.readString(aggregator.aggregateRound(0, results));

        // Claude should appear before Codex (alphabetical)
        int claudeIdx = content.indexOf("# Claude");
        int codexIdx = content.indexOf("# Codex");
        assertTrue(claudeIdx < codexIdx, "Claude should appear before Codex");
    }

    @Test
    void aggregateRound_noSuccessfulReviews_throws() {
        Map<String, AgentResult> results = Map.of(
            "claude", AgentResult.failed("claude", 0, 1, 100, "crashed"),
            "codex", AgentResult.timeout("codex", 0, 5000)
        );

        assertThrows(AgentException.class, () -> aggregator.aggregateRound(0, results));
    }

    @Test
    void getAllReviewsPath_returnsCorrectPath() {
        Path expected = workspace.getRoundDir(0).resolve("all_reviews.md");
        assertEquals(expected, aggregator.getAllReviewsPath(0));
    }

    @Test
    void aggregateRound_stripsWhitespace() throws IOException {
        Path claudeReview = workspace.getAgentDir(0, "claude").resolve("review.md");
        Files.writeString(claudeReview, "  \n\nClaude content with whitespace\n\n  ");

        Map<String, AgentResult> results = Map.of(
            "claude", AgentResult.success("claude", 0, 0, 100, claudeReview)
        );

        String content = Files.readString(aggregator.aggregateRound(0, results));

        // Content should be stripped
        assertTrue(content.contains("Claude content with whitespace"));
        assertFalse(content.startsWith(" "));
        assertTrue(content.endsWith("\n"));
    }

    @Test
    void aggregateRound_capitalizesAgentNames() throws IOException {
        Path claudeReview = workspace.getAgentDir(0, "claude").resolve("review.md");
        Files.writeString(claudeReview, "Content");

        Map<String, AgentResult> results = Map.of(
            "claude", AgentResult.success("claude", 0, 0, 100, claudeReview)
        );

        String content = Files.readString(aggregator.aggregateRound(0, results));

        assertTrue(content.contains("# Claude"));
        assertFalse(content.contains("# claude"));
    }

    @Test
    void aggregateRound_excludesTimedOutAgents() throws IOException {
        Path claudeReview = workspace.getAgentDir(0, "claude").resolve("review.md");
        Files.writeString(claudeReview, "Claude's content.");

        Map<String, AgentResult> results = Map.of(
            "claude", AgentResult.success("claude", 0, 0, 100, claudeReview),
            "codex", AgentResult.timeout("codex", 0, 30000)
        );

        Path allReviews = aggregator.aggregateRound(0, results);

        String content = Files.readString(allReviews);
        assertTrue(content.contains("# Claude"));
        assertFalse(content.contains("# Codex"));
    }

    @Test
    void aggregateRound_excludesInvalidOutputAgents() throws IOException {
        Path claudeReview = workspace.getAgentDir(0, "claude").resolve("review.md");
        Files.writeString(claudeReview, "Claude's content.");

        Map<String, AgentResult> results = Map.of(
            "claude", AgentResult.success("claude", 0, 0, 100, claudeReview),
            "codex", AgentResult.invalidOutput("codex", 0, 0, 100, "empty file")
        );

        Path allReviews = aggregator.aggregateRound(0, results);

        String content = Files.readString(allReviews);
        assertTrue(content.contains("# Claude"));
        assertFalse(content.contains("# Codex"));
    }

    @Test
    void aggregateRound_handlesEmptyResultsMap() {
        Map<String, AgentResult> results = Map.of();

        assertThrows(AgentException.class, () -> aggregator.aggregateRound(0, results));
    }

    @Test
    void getAllReviewsPath_worksForDifferentRounds() {
        Path round0 = aggregator.getAllReviewsPath(0);
        Path round1 = aggregator.getAllReviewsPath(1);
        Path round2 = aggregator.getAllReviewsPath(2);

        assertTrue(round0.toString().contains("round-0"));
        assertTrue(round1.toString().contains("round-1"));
        assertTrue(round2.toString().contains("round-2"));
        assertNotEquals(round0, round1);
        assertNotEquals(round1, round2);
    }
}
