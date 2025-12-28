package dev.reviewarena.io;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private ArenaConfig defaultConfig;

    @BeforeEach
    void setUp() {
        // Create a config with 3 enabled agents and 2 rounds
        Map<String, AgentConfig> agents = Map.of(
            "claude", new AgentConfig("claude", List.of("claude", "-p", "@prompt.md"), Map.of(), true),
            "codex", new AgentConfig("codex", List.of("codex", "exec", "@prompt.md"), Map.of(), true),
            "gemini", new AgentConfig("gemini", List.of("gemini", "-p", "@prompt.md"), Map.of(), true)
        );

        defaultConfig = new ArenaConfig(
            2,  // maxRounds (rounds 0, 1, 2)
            ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB,
            ArenaConfig.DEFAULT_MAX_CONCURRENT,
            ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS,
            ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS,
            ArenaConfig.DEFAULT_GRACE_PERIOD_MS,
            ArenaConfig.DEFAULT_ON_TIMEOUT,
            ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT,
            ArenaConfig.DEFAULT_MIN_AGENTS,
            Path.of(".arena"),
            agents
        );
    }

    // ===== Directory structure creation =====

    @Test
    void testInitialize_createsArenaDirectory() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        Path arenaDir = manager.initialize();

        assertTrue(Files.isDirectory(arenaDir));
        assertEquals(tempDir.resolve(".arena"), arenaDir);
    }

    @Test
    void testInitialize_createsRoundsDirectory() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        assertTrue(Files.isDirectory(tempDir.resolve(".arena/rounds")));
    }

    @Test
    void testInitialize_createsRoundDirectories() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        // Should create round-0, round-1, round-2 (maxRounds=2)
        assertTrue(Files.isDirectory(tempDir.resolve(".arena/rounds/round-0")));
        assertTrue(Files.isDirectory(tempDir.resolve(".arena/rounds/round-1")));
        assertTrue(Files.isDirectory(tempDir.resolve(".arena/rounds/round-2")));
    }

    @Test
    void testInitialize_createsAgentDirectories() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        // Each round should have agent subdirectories
        for (int round = 0; round <= 2; round++) {
            Path roundDir = tempDir.resolve(".arena/rounds/round-" + round);
            assertTrue(Files.isDirectory(roundDir.resolve("claude")));
            assertTrue(Files.isDirectory(roundDir.resolve("codex")));
            assertTrue(Files.isDirectory(roundDir.resolve("gemini")));
        }
    }

    @Test
    void testInitialize_createsFinalDirectory() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        assertTrue(Files.isDirectory(tempDir.resolve(".arena/rounds/final")));
    }

    @Test
    void testInitialize_createsTaskMd() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        Path taskMd = tempDir.resolve(".arena/prompts/task.md");
        assertTrue(Files.exists(taskMd));
        assertTrue(Files.isRegularFile(taskMd));
    }

    @Test
    void testInitialize_taskMdContainsContent() throws IOException {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        String content = Files.readString(tempDir.resolve(".arena/prompts/task.md"));
        assertFalse(content.isBlank());
        assertTrue(content.contains("Code Review Arena"));
    }

    // ===== Pre-generated prompts =====

    @Test
    void testInitialize_createsPromptsDirectory() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        assertTrue(Files.isDirectory(tempDir.resolve(".arena/prompts")));
    }

    @Test
    void testInitialize_createsRoundPromptFiles() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        // Should create per-agent prompt files for each round (maxRounds=2)
        assertTrue(Files.exists(tempDir.resolve(".arena/prompts/round-0-claude.md")));
        assertTrue(Files.exists(tempDir.resolve(".arena/prompts/round-0-codex.md")));
        assertTrue(Files.exists(tempDir.resolve(".arena/prompts/round-0-gemini.md")));
        assertTrue(Files.exists(tempDir.resolve(".arena/prompts/round-1-claude.md")));
        assertTrue(Files.exists(tempDir.resolve(".arena/prompts/round-2-claude.md")));
    }

    @Test
    void testInitialize_roundPromptContainsTaskAndRoundContent() throws IOException {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        String content = Files.readString(tempDir.resolve(".arena/prompts/round-0-claude.md"));
        // Should contain task.md content
        assertTrue(content.contains("Code Review Arena"));
        // Should contain round-0.md content
        assertTrue(content.contains("Round 0"));
    }

    @Test
    void testInitialize_laterRoundPromptsContainAllReviewsPath() throws IOException {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        String content = Files.readString(tempDir.resolve(".arena/prompts/round-1-claude.md"));
        assertTrue(content.contains(".arena/rounds/round-0/all_reviews.md"));
    }

    @Test
    void testInitialize_roundPromptsContainOutputPath() throws IOException {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        String round0Claude = Files.readString(tempDir.resolve(".arena/prompts/round-0-claude.md"));
        assertTrue(round0Claude.contains(".arena/rounds/round-0/claude/review.md"));

        String round0Codex = Files.readString(tempDir.resolve(".arena/prompts/round-0-codex.md"));
        assertTrue(round0Codex.contains(".arena/rounds/round-0/codex/review.md"));

        String round1Claude = Files.readString(tempDir.resolve(".arena/prompts/round-1-claude.md"));
        assertTrue(round1Claude.contains(".arena/rounds/round-1/claude/review.md"));
    }

    @Test
    void testGetPromptsDir_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena/prompts"), manager.getPromptsDir());
    }

    @Test
    void testGetRoundPromptPath_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena/prompts/round-0-claude.md"), manager.getRoundPromptPath(0, "claude"));
        assertEquals(tempDir.resolve(".arena/prompts/round-3-gemini.md"), manager.getRoundPromptPath(3, "gemini"));
    }

    // ===== Clearing existing directory =====

    @Test
    void testInitialize_clearsExistingArenaDir() throws IOException {
        // Create an existing .arena directory with some content
        Path existingArena = tempDir.resolve(".arena");
        Files.createDirectories(existingArena);
        Path oldFile = existingArena.resolve("old-file.txt");
        Files.writeString(oldFile, "old content");

        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        // Old file should be gone
        assertFalse(Files.exists(oldFile));
        // New structure should exist
        assertTrue(Files.isDirectory(tempDir.resolve(".arena/rounds")));
    }

    @Test
    void testInitialize_clearsNestedExistingContent() throws IOException {
        // Create nested directories and files
        Path existingRounds = tempDir.resolve(".arena/rounds/round-0/old-agent");
        Files.createDirectories(existingRounds);
        Files.writeString(existingRounds.resolve("old-review.md"), "old review");

        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        manager.initialize();

        // Old nested content should be gone
        assertFalse(Files.exists(tempDir.resolve(".arena/rounds/round-0/old-agent")));
    }

    // ===== Only enabled agents =====

    @Test
    void testInitialize_onlyCreatesEnabledAgentDirs() {
        // Create config with one disabled agent
        Map<String, AgentConfig> agents = Map.of(
            "claude", new AgentConfig("claude", List.of("claude"), Map.of(), true),
            "codex", new AgentConfig("codex", List.of("codex"), Map.of(), false),  // disabled
            "gemini", new AgentConfig("gemini", List.of("gemini"), Map.of(), true)
        );

        ArenaConfig config = new ArenaConfig(
            1, ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB, ArenaConfig.DEFAULT_MAX_CONCURRENT,
            ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS, ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS,
            ArenaConfig.DEFAULT_GRACE_PERIOD_MS, ArenaConfig.DEFAULT_ON_TIMEOUT,
            ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT, ArenaConfig.DEFAULT_MIN_AGENTS,
            Path.of(".arena"), agents
        );

        WorkspaceManager manager = new WorkspaceManager(tempDir, config);

        manager.initialize();

        Path round0 = tempDir.resolve(".arena/rounds/round-0");
        assertTrue(Files.isDirectory(round0.resolve("claude")));
        assertTrue(Files.isDirectory(round0.resolve("gemini")));
        assertFalse(Files.exists(round0.resolve("codex"))); // disabled agent
    }

    // ===== Custom output directory =====

    @Test
    void testInitialize_usesCustomOutputDir() {
        ArenaConfig config = new ArenaConfig(
            1, ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB, ArenaConfig.DEFAULT_MAX_CONCURRENT,
            ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS, ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS,
            ArenaConfig.DEFAULT_GRACE_PERIOD_MS, ArenaConfig.DEFAULT_ON_TIMEOUT,
            ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT, ArenaConfig.DEFAULT_MIN_AGENTS,
            Path.of("custom-output"),  // custom output dir
            Map.of("agent1", new AgentConfig("agent1", List.of("cmd"), Map.of(), true))
        );

        WorkspaceManager manager = new WorkspaceManager(tempDir, config);

        Path arenaDir = manager.initialize();

        assertEquals(tempDir.resolve("custom-output"), arenaDir);
        assertTrue(Files.isDirectory(arenaDir));
    }

    // ===== Path helper methods =====

    @Test
    void testGetArenaDir_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena"), manager.getArenaDir());
    }

    @Test
    void testGetRoundsDir_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena/rounds"), manager.getRoundsDir());
    }

    @Test
    void testGetRoundDir_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena/rounds/round-0"), manager.getRoundDir(0));
        assertEquals(tempDir.resolve(".arena/rounds/round-3"), manager.getRoundDir(3));
    }

    @Test
    void testGetAgentDir_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena/rounds/round-1/claude"), manager.getAgentDir(1, "claude"));
    }

    @Test
    void testGetFinalDir_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena/rounds/final"), manager.getFinalDir());
    }

    @Test
    void testGetTaskMdPath_returnsCorrectPath() {
        WorkspaceManager manager = new WorkspaceManager(tempDir, defaultConfig);

        assertEquals(tempDir.resolve(".arena/prompts/task.md"), manager.getTaskMdPath());
    }

    // ===== Edge cases =====

    @Test
    void testInitialize_zeroRounds_createsOnlyRound0() {
        ArenaConfig config = new ArenaConfig(
            0,  // maxRounds = 0 (only round-0)
            ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB, ArenaConfig.DEFAULT_MAX_CONCURRENT,
            ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS, ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS,
            ArenaConfig.DEFAULT_GRACE_PERIOD_MS, ArenaConfig.DEFAULT_ON_TIMEOUT,
            ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT, ArenaConfig.DEFAULT_MIN_AGENTS,
            Path.of(".arena"),
            Map.of("agent1", new AgentConfig("agent1", List.of("cmd"), Map.of(), true))
        );

        WorkspaceManager manager = new WorkspaceManager(tempDir, config);

        manager.initialize();

        assertTrue(Files.isDirectory(tempDir.resolve(".arena/rounds/round-0")));
        assertFalse(Files.exists(tempDir.resolve(".arena/rounds/round-1")));
    }

    @Test
    void testInitialize_noEnabledAgents_createsEmptyRoundDirs() {
        // All agents disabled
        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1", List.of("cmd"), Map.of(), false)
        );

        ArenaConfig config = new ArenaConfig(
            1, ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB, ArenaConfig.DEFAULT_MAX_CONCURRENT,
            ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS, ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS,
            ArenaConfig.DEFAULT_GRACE_PERIOD_MS, ArenaConfig.DEFAULT_ON_TIMEOUT,
            ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT, ArenaConfig.DEFAULT_MIN_AGENTS,
            Path.of(".arena"), agents
        );

        WorkspaceManager manager = new WorkspaceManager(tempDir, config);

        manager.initialize();

        // Round directories should exist but be empty (no agent subdirs)
        Path round0 = tempDir.resolve(".arena/rounds/round-0");
        assertTrue(Files.isDirectory(round0));
        // No subdirectories for disabled agents
        assertFalse(Files.exists(round0.resolve("agent1")));
    }
}
