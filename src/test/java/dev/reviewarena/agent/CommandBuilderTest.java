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
        AgentConfig config = new AgentConfig("test",
            List.of("agent", "-p", "@prompt.md"), Map.of(), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains(promptFile.toAbsolutePath().toString()));
        assertFalse(command.contains("@prompt.md"));
    }

    @Test
    void build_replacesOutputPlaceholder() {
        AgentConfig config = new AgentConfig("test",
            List.of("agent", "-p", "@prompt.md", "-o", "@output"), Map.of(), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains(outputFile.toAbsolutePath().toString()));
        assertFalse(command.contains("@output"));
    }

    @Test
    void build_claude_addsAutoApproveFlag() {
        AgentConfig config = new AgentConfig("claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--dangerously-skip-permissions"));
    }

    @Test
    void build_codex_addsAutoApproveFlag() {
        AgentConfig config = new AgentConfig("codex",
            List.of("codex", "exec", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--full-auto"));
    }

    @Test
    void build_gemini_addsAutoApproveFlag() {
        AgentConfig config = new AgentConfig("gemini",
            List.of("gemini", "-p", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--yolo"));
    }

    @Test
    void build_claude_addsAllowedTools() {
        AgentConfig config = new AgentConfig("claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("allowed-tools", List.of("Read", "Write", "Edit")), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--allowedTools"));
        int idx = command.indexOf("--allowedTools");
        assertEquals("Read,Write,Edit", command.get(idx + 1));
    }

    @Test
    void build_nonClaude_ignoresAllowedTools() {
        AgentConfig config = new AgentConfig("codex",
            List.of("codex", "exec", "@prompt.md"),
            Map.of("allowed-tools", List.of("Read", "Write")), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertFalse(command.contains("--allowedTools"));
    }

    @Test
    void build_noAutoApprove_doesNotAddFlag() {
        AgentConfig config = new AgentConfig("claude",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", false), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertFalse(command.contains("--dangerously-skip-permissions"));
    }

    @Test
    void build_unknownAgent_warnsAndContinues() {
        AgentConfig config = new AgentConfig("custom-agent",
            List.of("custom", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        // No CLI-specific flags added for unknown agents (warning logged)
        assertFalse(command.contains("--dangerously-skip-permissions"));
        assertFalse(command.contains("--full-auto"));
        assertFalse(command.contains("--yolo"));
        // Command still builds successfully - just warns
        assertTrue(command.contains("custom"));
    }

    @Test
    void build_returnsImmutableList() {
        AgentConfig config = new AgentConfig("test",
            List.of("test", "@prompt.md"), Map.of(), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertThrows(UnsupportedOperationException.class, () -> command.add("extra"));
    }

    @Test
    void build_caseInsensitiveAgentName() {
        AgentConfig config = new AgentConfig("CLAUDE",
            List.of("claude", "-p", "@prompt.md"),
            Map.of("auto-approve", true), true);

        List<String> command = builder.build(config, promptFile, outputFile);

        assertTrue(command.contains("--dangerously-skip-permissions"));
    }
}
