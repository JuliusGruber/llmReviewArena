package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.io.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AgentExecutor with real process execution.
 *
 * <p>These tests verify end-to-end behavior with actual subprocess spawning,
 * file I/O, and concurrency control.
 */
class AgentExecutorIT {

    @TempDir
    Path tempDir;

    private Path scriptsDir;

    @BeforeEach
    void setUp() throws IOException {
        scriptsDir = tempDir.resolve("scripts");
        Files.createDirectories(scriptsDir);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executeRound_multipleAgents_unix() throws IOException {
        // Create a script that writes to the output file specified via @output placeholder
        Path successScript = scriptsDir.resolve("success.sh");
        Files.writeString(successScript, """
            #!/bin/bash
            OUTPUT_FILE="$1"
            mkdir -p "$(dirname "$OUTPUT_FILE")"
            echo "# Review from test" > "$OUTPUT_FILE"
            echo "Content" >> "$OUTPUT_FILE"
            exit 0
            """);
        successScript.toFile().setExecutable(true);

        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1",
                List.of("bash", successScript.toString(), "@output"), Map.of(), true),
            "agent2", new AgentConfig("agent2",
                List.of("bash", successScript.toString(), "@output"), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 0); // unlimited concurrency
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(2, results.size());
        assertTrue(results.containsKey("agent1"));
        assertTrue(results.containsKey("agent2"));

        // Both should succeed
        assertTrue(results.get("agent1").isSuccess(), "agent1 should succeed");
        assertTrue(results.get("agent2").isSuccess(), "agent2 should succeed");

        // Verify output files exist
        Path agent1Output = workspace.getAgentDir(0, "agent1").resolve("review.md");
        Path agent2Output = workspace.getAgentDir(0, "agent2").resolve("review.md");
        assertTrue(Files.exists(agent1Output), "agent1 output should exist");
        assertTrue(Files.exists(agent2Output), "agent2 output should exist");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executeRound_multipleAgents_windows() throws IOException {
        // Create a batch script that writes to the output file
        Path successScript = scriptsDir.resolve("success.bat");
        Files.writeString(successScript, """
            @echo off
            setlocal
            set OUTPUT_FILE=%~1
            for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul
            echo # Review from test > "%OUTPUT_FILE%"
            echo Content >> "%OUTPUT_FILE%"
            exit /b 0
            """);

        Map<String, AgentConfig> agents = Map.of(
            "agent1", new AgentConfig("agent1",
                List.of("cmd", "/c", successScript.toString(), "@output"), Map.of(), true),
            "agent2", new AgentConfig("agent2",
                List.of("cmd", "/c", successScript.toString(), "@output"), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 0);
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(2, results.size());
        assertTrue(results.containsKey("agent1"));
        assertTrue(results.containsKey("agent2"));

        // Both should succeed
        assertTrue(results.get("agent1").isSuccess(), "agent1 should succeed");
        assertTrue(results.get("agent2").isSuccess(), "agent2 should succeed");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executeRound_respectsMaxConcurrent() {
        // Configure 3 agents with non-existent commands (will fail quickly)
        Map<String, AgentConfig> agents = Map.of(
            "a1", new AgentConfig("a1", List.of("nonexistent-cmd-12345"), Map.of(), true),
            "a2", new AgentConfig("a2", List.of("nonexistent-cmd-12345"), Map.of(), true),
            "a3", new AgentConfig("a3", List.of("nonexistent-cmd-12345"), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 1); // Sequential (max 1 concurrent)
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);

        // Should complete without deadlock
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(3, results.size());
        // All should fail because command doesn't exist
        results.values().forEach(r -> {
            assertFalse(r.isSuccess());
            assertEquals(AgentResult.Status.FAILED, r.status());
        });
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executeRound_mixedSuccessAndFailure_unix() throws IOException {
        // Success script
        Path successScript = scriptsDir.resolve("success.sh");
        Files.writeString(successScript, """
            #!/bin/bash
            OUTPUT_FILE="$1"
            mkdir -p "$(dirname "$OUTPUT_FILE")"
            echo "# Review" > "$OUTPUT_FILE"
            exit 0
            """);
        successScript.toFile().setExecutable(true);

        // Failure script
        Path failScript = scriptsDir.resolve("fail.sh");
        Files.writeString(failScript, """
            #!/bin/bash
            exit 1
            """);
        failScript.toFile().setExecutable(true);

        Map<String, AgentConfig> agents = Map.of(
            "success-agent", new AgentConfig("success-agent",
                List.of("bash", successScript.toString(), "@output"), Map.of(), true),
            "fail-agent", new AgentConfig("fail-agent",
                List.of("bash", failScript.toString()), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 0);
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(2, results.size());

        // One success, one failure
        assertTrue(results.get("success-agent").isSuccess());
        assertFalse(results.get("fail-agent").isSuccess());
        assertEquals(AgentResult.Status.FAILED, results.get("fail-agent").status());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executeRound_mixedSuccessAndFailure_windows() throws IOException {
        // Success script
        Path successScript = scriptsDir.resolve("success.bat");
        Files.writeString(successScript, """
            @echo off
            setlocal
            set OUTPUT_FILE=%~1
            for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul
            echo # Review > "%OUTPUT_FILE%"
            exit /b 0
            """);

        Map<String, AgentConfig> agents = Map.of(
            "success-agent", new AgentConfig("success-agent",
                List.of("cmd", "/c", successScript.toString(), "@output"), Map.of(), true),
            "fail-agent", new AgentConfig("fail-agent",
                List.of("cmd", "/c", "exit", "1"), Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 0);
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(2, results.size());

        // One success, one failure
        assertTrue(results.get("success-agent").isSuccess());
        assertFalse(results.get("fail-agent").isSuccess());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void executeRound_agentTimeout_unix() throws IOException {
        // Script that sleeps longer than timeout
        Path slowScript = scriptsDir.resolve("slow.sh");
        Files.writeString(slowScript, """
            #!/bin/bash
            sleep 30
            exit 0
            """);
        slowScript.toFile().setExecutable(true);

        Map<String, AgentConfig> agents = Map.of(
            "slow-agent", new AgentConfig("slow-agent",
                List.of("bash", slowScript.toString()), Map.of(), true)
        );

        // Very short timeout
        ArenaConfig config = new ArenaConfig(
            1, 500, 0,
            500, // 500ms agent timeout
            5_000, // 5s round timeout
            100, // 100ms grace period
            1,
            Path.of(".arena"),
            agents
        );
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(1, results.size());
        AgentResult result = results.get("slow-agent");
        assertEquals(AgentResult.Status.TIMEOUT, result.status());
        assertFalse(result.isSuccess());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void executeRound_agentTimeout_windows() {
        Map<String, AgentConfig> agents = Map.of(
            "slow-agent", new AgentConfig("slow-agent",
                List.of("powershell", "-Command", "Start-Sleep -Seconds 30"), Map.of(), true)
        );

        // Very short timeout
        ArenaConfig config = new ArenaConfig(
            1, 500, 0,
            500, // 500ms agent timeout
            5_000, // 5s round timeout
            100, // 100ms grace period
            1,
            Path.of(".arena"),
            agents
        );
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertEquals(1, results.size());
        AgentResult result = results.get("slow-agent");
        assertEquals(AgentResult.Status.TIMEOUT, result.status());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executeRound_stdoutAndStderrCaptured() throws IOException {
        Path script;
        List<String> command;

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            script = scriptsDir.resolve("output.bat");
            Files.writeString(script, """
                @echo off
                setlocal
                set OUTPUT_FILE=%~1
                for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul
                echo stdout message
                echo stderr message 1>&2
                echo # Review > "%OUTPUT_FILE%"
                exit /b 0
                """);
            command = List.of("cmd", "/c", script.toString(), "@output");
        } else {
            script = scriptsDir.resolve("output.sh");
            Files.writeString(script, """
                #!/bin/bash
                OUTPUT_FILE="$1"
                mkdir -p "$(dirname "$OUTPUT_FILE")"
                echo "stdout message"
                echo "stderr message" >&2
                echo "# Review" > "$OUTPUT_FILE"
                exit 0
                """);
            script.toFile().setExecutable(true);
            command = List.of("bash", script.toString(), "@output");
        }

        Map<String, AgentConfig> agents = Map.of(
            "output-agent", new AgentConfig("output-agent", command, Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 0);
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        assertTrue(results.get("output-agent").isSuccess());

        // Verify log files exist
        Path agentDir = workspace.getAgentDir(0, "output-agent");
        Path stdoutLog = agentDir.resolve("stdout.log");
        Path stderrLog = agentDir.resolve("stderr.log");

        assertTrue(Files.exists(stdoutLog), "stdout.log should exist");
        assertTrue(Files.exists(stderrLog), "stderr.log should exist");

        // Give time for stream drain to complete
        try { Thread.sleep(200); } catch (InterruptedException e) { }

        String stdout = Files.readString(stdoutLog);
        String stderr = Files.readString(stderrLog);

        assertTrue(stdout.contains("stdout"), "stdout should contain message");
        assertTrue(stderr.contains("stderr"), "stderr should contain message");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executeRound_emptyOutput_returnsInvalidOutput() throws IOException {
        Path script;
        List<String> command;

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            script = scriptsDir.resolve("empty.bat");
            Files.writeString(script, """
                @echo off
                setlocal
                set OUTPUT_FILE=%~1
                for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul
                type nul > "%OUTPUT_FILE%"
                exit /b 0
                """);
            command = List.of("cmd", "/c", script.toString(), "@output");
        } else {
            script = scriptsDir.resolve("empty.sh");
            Files.writeString(script, """
                #!/bin/bash
                OUTPUT_FILE="$1"
                mkdir -p "$(dirname "$OUTPUT_FILE")"
                touch "$OUTPUT_FILE"
                exit 0
                """);
            script.toFile().setExecutable(true);
            command = List.of("bash", script.toString(), "@output");
        }

        Map<String, AgentConfig> agents = Map.of(
            "empty-agent", new AgentConfig("empty-agent", command, Map.of(), true)
        );

        ArenaConfig config = createConfig(agents, 0);
        WorkspaceManager workspace = new WorkspaceManager(tempDir, config);
        workspace.initialize("test-commit", "", "");

        AgentExecutor executor = new AgentExecutor(config, workspace);
        Map<String, AgentResult> results = executor.executeRound(0);

        AgentResult result = results.get("empty-agent");
        assertFalse(result.isSuccess());
        assertEquals(AgentResult.Status.INVALID_OUTPUT, result.status());
    }

    private ArenaConfig createConfig(Map<String, AgentConfig> agents, int maxConcurrent) {
        return new ArenaConfig(
            1, // maxRounds
            500, // maxOutputSizeKb
            maxConcurrent,
            10_000, // agentTimeoutMs
            30_000, // roundTimeoutMs
            1_000, // gracePeriodMs
            1, // minAgents
            Path.of(".arena"),
            agents
        );
    }
}
