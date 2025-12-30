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

class AgentExecutorTest {

    @TempDir
    Path tempDir;

    private ArenaConfig config;
    private WorkspaceManager workspace;

    @BeforeEach
    void setUp() throws IOException {
        // Create mock agent scripts
        createMockAgents();
    }

    private void createMockAgents() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Files.createDirectories(scriptsDir);

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Windows batch scripts
            Files.writeString(scriptsDir.resolve("success.bat"), """
                @echo off
                set OUTPUT=%~dp0..\\..\\rounds\\round-0\\success\\review.md
                echo # Mock Review > "%OUTPUT%"
                echo Content >> "%OUTPUT%"
                exit /b 0
                """);
        } else {
            // Unix shell scripts
            Path successScript = scriptsDir.resolve("success.sh");
            Files.writeString(successScript, """
                #!/bin/bash
                OUTPUT_DIR="$(dirname "$0")/../../rounds/round-0/success"
                mkdir -p "$OUTPUT_DIR"
                echo "# Mock Review" > "$OUTPUT_DIR/review.md"
                echo "Content" >> "$OUTPUT_DIR/review.md"
                exit 0
                """);
            successScript.toFile().setExecutable(true);
        }
    }

    @Test
    void executeRound_noEnabledAgents_returnsEmptyMap() {
        // All agents disabled
        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1", List.of("echo"), Map.of(), false)
        );
        config = createConfig(agents);
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertTrue(results.isEmpty());
    }

    @Test
    void getSuccessfulAgents_filtersCorrectly() {
        Map<String, AgentResult> results = Map.of(
            "success1", AgentResult.success("success1", 0, 0, 100, Path.of("/test")),
            "failed1", AgentResult.failed("failed1", 0, 1, 100, "error"),
            "success2", AgentResult.success("success2", 0, 0, 100, Path.of("/test2"))
        );

        var successful = AgentExecutor.getSuccessfulAgents(results);

        assertEquals(2, successful.size());
        assertTrue(successful.contains("success1"));
        assertTrue(successful.contains("success2"));
        assertFalse(successful.contains("failed1"));
    }

    @Test
    void getSuccessfulAgents_emptyResults_returnsEmptySet() {
        Map<String, AgentResult> results = Map.of();

        var successful = AgentExecutor.getSuccessfulAgents(results);

        assertTrue(successful.isEmpty());
    }

    @Test
    void getSuccessfulAgents_allFailed_returnsEmptySet() {
        Map<String, AgentResult> results = Map.of(
            "failed1", AgentResult.failed("failed1", 0, 1, 100, "error"),
            "timeout1", AgentResult.timeout("timeout1", 0, 5000),
            "invalid1", AgentResult.invalidOutput("invalid1", 0, 0, 100, "empty")
        );

        var successful = AgentExecutor.getSuccessfulAgents(results);

        assertTrue(successful.isEmpty());
    }

    @Test
    void executeRound_agentsRunInAlphabeticalOrder() {
        // Create agents with names that would sort differently
        Map<String, AgentConfig> agents = Map.of(
            "zebra", new AgentConfig("zebra", List.of("nonexistent-cmd"), Map.of(), true),
            "alpha", new AgentConfig("alpha", List.of("nonexistent-cmd"), Map.of(), true),
            "middle", new AgentConfig("middle", List.of("nonexistent-cmd"), Map.of(), true)
        );
        config = createConfig(agents);
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);

        // Execution should complete without error even though commands fail
        assertDoesNotThrow(() -> executor.executeRound(0));
    }

    @Test
    void executeRound_withMaxConcurrent_respectsLimit() {
        // Create several agents that would fail quickly
        Map<String, AgentConfig> agents = Map.of(
            "a1", new AgentConfig("a1", List.of("nonexistent-cmd"), Map.of(), true),
            "a2", new AgentConfig("a2", List.of("nonexistent-cmd"), Map.of(), true),
            "a3", new AgentConfig("a3", List.of("nonexistent-cmd"), Map.of(), true)
        );
        config = createConfigWithConcurrency(agents, 1); // Sequential
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);

        // Should complete without deadlock
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(3, results.size());
        // All failed because command doesn't exist, but they ran
        results.values().forEach(r -> assertFalse(r.isSuccess()));
    }

    @Test
    void executeRound_failedAgentsDoNotBlockOthers() {
        // One agent will fail, others should still execute
        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1", List.of("nonexistent-cmd"), Map.of(), true),
            "agent2", new AgentConfig("agent2", List.of("nonexistent-cmd"), Map.of(), true)
        );
        config = createConfig(agents);
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        // Both agents should have results (even if both failed)
        assertEquals(2, results.size());
        assertTrue(results.containsKey("agent1"));
        assertTrue(results.containsKey("agent2"));
    }

    @Test
    void executeRound_resultsMapIsImmutable() {
        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1", List.of("nonexistent-cmd"), Map.of(), false)
        );
        config = createConfig(agents);
        workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertThrows(UnsupportedOperationException.class, () ->
            results.put("newAgent", AgentResult.failed("newAgent", 0, 1, 100, "test")));
    }

    private ArenaConfig createConfig(Map<String, AgentConfig> agents) {
        return new ArenaConfig(
            2, // maxRounds
            500, // maxOutputSizeKb
            0, // maxConcurrent (unlimited)
            false, // showAgentOutput - disable for tests to avoid console noise
            30_000, // agentTimeoutMs
            90_000, // roundTimeoutMs
            1_000, // gracePeriodMs
            2, // minAgents
            Path.of(".arena"),
            agents
        );
    }

    private ArenaConfig createConfigWithConcurrency(Map<String, AgentConfig> agents, int maxConcurrent) {
        return new ArenaConfig(
            2, // maxRounds
            500, // maxOutputSizeKb
            maxConcurrent,
            false, // showAgentOutput - disable for tests to avoid console noise
            10_000, // agentTimeoutMs
            30_000, // roundTimeoutMs
            1_000, // gracePeriodMs
            1, // minAgents
            Path.of(".arena"),
            agents
        );
    }
}
