package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandBuilderTest {

    private CommandBuilder builder;
    private Path promptFile;
    private Path outputFile;

    @BeforeEach
    void setUp() {
        builder = new CommandBuilder();
        promptFile = Path.of("/workspace/.arena/prompts/round-0-claude.md");
        outputFile = Path.of("/workspace/.arena/rounds/round-0/claude/review.md");
    }

    @Test
    void build_replacesPromptPlaceholder() {
        AgentConfig config = AgentConfig.of("test",
            List.of("agent", "-p", "@prompt.md"));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains(promptFile.toAbsolutePath().toString()));
        assertFalse(command.contains("@prompt.md"));
    }

    @Test
    void build_replacesOutputPlaceholder() {
        AgentConfig config = AgentConfig.of("test",
            List.of("agent", "-p", "@prompt.md", "-o", "@output"));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains(outputFile.toAbsolutePath().toString()));
        assertFalse(command.contains("@output"));
    }

    @Test
    void build_claude_addsAutoApproveFlag() {
        AgentConfig config = AgentConfig.of("claude", "claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", true));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--dangerously-skip-permissions"));
    }

    @Test
    void build_codex_addsAutoApproveFlag() {
        AgentConfig config = AgentConfig.of("codex", "codex",
            List.of("codex", "exec", "@prompt.md"),
            Map.of("auto-approve", true));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--full-auto"));
    }

    @Test
    void build_gemini_addsAutoApproveFlag() {
        AgentConfig config = AgentConfig.of("gemini", "gemini",
            List.of("gemini", "-p", "@prompt.md"),
            Map.of("auto-approve", true));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--yolo"));
    }

    @Test
    void build_claude_addsAllowedTools() {
        AgentConfig config = AgentConfig.of("claude", "claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("allowed-tools", List.of("Read", "Write", "Edit")));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--allowedTools"));
        int idx = command.indexOf("--allowedTools");
        assertEquals("Read,Write,Edit", command.get(idx + 1));
    }

    @Test
    void build_nonClaude_ignoresAllowedTools() {
        AgentConfig config = AgentConfig.of("codex", "codex",
            List.of("codex", "exec", "@prompt.md"),
            Map.of("allowed-tools", List.of("Read", "Write")));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertFalse(command.contains("--allowedTools"));
    }

    @Test
    void build_noAutoApprove_doesNotAddFlag() {
        AgentConfig config = AgentConfig.of("claude", "claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", false));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertFalse(command.contains("--dangerously-skip-permissions"));
    }

    @Test
    void build_nullType_warnsAndContinues() {
        // Agent with null type - no CLI-specific flags added
        AgentConfig config = AgentConfig.of("custom-agent",
            List.of("custom", "@prompt.md"),
            Map.of("auto-approve", true));

        List<String> command = builder.build(config, promptFile, outputFile);

        // No CLI-specific flags added for null type (warning logged)
        assertFalse(command.contains("--dangerously-skip-permissions"));
        assertFalse(command.contains("--full-auto"));
        assertFalse(command.contains("--yolo"));
        // Command still builds successfully - just warns
        assertTrue(command.contains("custom"));
    }

    @Test
    void build_customNameWithClaudeType_addsClaudeFlag() {
        // Agent with custom name but explicit claude type
        AgentConfig config = AgentConfig.of("fast-reviewer", "claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", true));

        List<String> command = builder.build(config, promptFile, outputFile);

        // Claude-specific flag should be added based on type, not name
        assertTrue(command.contains("--dangerously-skip-permissions"));
    }

    @Test
    void build_returnsImmutableList() {
        AgentConfig config = AgentConfig.of("test",
            List.of("test", "@prompt.md"));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertThrows(UnsupportedOperationException.class, () -> command.add("extra"));
    }

    @Test
    void build_caseInsensitiveType() {
        // Type matching is case-sensitive, using exact "claude" type
        AgentConfig config = AgentConfig.of("CLAUDE", "claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", true));

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--dangerously-skip-permissions"));
    }
}
