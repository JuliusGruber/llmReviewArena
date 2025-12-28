package dev.reviewarena.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentProcessTest {

    @TempDir
    Path tempDir;

    private Path outputDir;
    private Path outputFile;
    private Path stdoutLog;
    private Path stderrLog;
    private OutputValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        outputFile = outputDir.resolve("review.md");
        stdoutLog = outputDir.resolve("stdout.log");
        stderrLog = outputDir.resolve("stderr.log");
        validator = new OutputValidator(500);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_successfulProcess_windows() throws IOException {
        // Create a simple batch script that writes output
        Path script = tempDir.resolve("test.bat");
        Files.writeString(script, """
            @echo off
            echo # Review > "%1\\review.md"
            echo Content >> "%1\\review.md"
            """);

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("cmd", "/c", script.toString(), outputDir.toString()))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.durationMs() >= 0);
        assertEquals(outputFile, result.outputFile());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_successfulProcess_unix() throws IOException {
        // Create a simple shell script that writes output
        Path script = tempDir.resolve("test.sh");
        Files.writeString(script, """
            #!/bin/bash
            echo "# Review" > "$1/review.md"
            echo "Content" >> "$1/review.md"
            """);
        script.toFile().setExecutable(true);

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("bash", script.toString(), outputDir.toString()))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.durationMs() >= 0);
        assertEquals(outputFile, result.outputFile());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_failingProcess_windows() {
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("cmd", "/c", "exit", "1"))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertNotNull(result.failureReason());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_failingProcess_unix() {
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("bash", "-c", "exit 1"))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertNotNull(result.failureReason());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_timeout_windows() throws IOException {
        // Use PowerShell to sleep - works reliably in non-interactive mode
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("powershell", "-Command", "Start-Sleep -Seconds 30"))
            .workingDir(Path.of(System.getProperty("java.io.tmpdir")))
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(500)  // Very short timeout
            .gracePeriodMs(100)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.TIMEOUT, result.status());
        assertEquals(-1, result.exitCode());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_timeout_unix() {
        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("sleep", "30"))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(500)  // Very short timeout
            .gracePeriodMs(100)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.TIMEOUT, result.status());
        assertEquals(-1, result.exitCode());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_invalidOutput_windows() throws IOException {
        // Create a batch script that creates an empty file
        Path script = tempDir.resolve("empty.bat");
        String outputPath = outputFile.toString().replace("\\", "\\\\");
        Files.writeString(script, "@echo off\r\ntype nul > \"" + outputPath + "\"");

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("cmd", "/c", script.toString()))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        // Either INVALID_OUTPUT (empty file) or FAILED (no file)
        assertFalse(result.isSuccess());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_invalidOutput_unix() throws IOException {
        // Create a shell script that creates an empty file
        Path script = tempDir.resolve("empty.sh");
        Files.writeString(script, "#!/bin/bash\ntouch \"" + outputFile + "\"");
        script.toFile().setExecutable(true);

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(List.of("bash", script.toString()))
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        AgentResult result = agent.execute();

        assertEquals(AgentResult.Status.INVALID_OUTPUT, result.status());
        assertFalse(result.isSuccess());
    }

    @Test
    void execute_capturesStdout() throws IOException {
        String testOutput = "Hello from agent";
        Path script;
        List<String> command;

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            script = tempDir.resolve("echo.bat");
            Files.writeString(script, "@echo " + testOutput + "\r\necho x > \"" + outputFile + "\"");
            command = List.of("cmd", "/c", script.toString());
        } else {
            script = tempDir.resolve("echo.sh");
            Files.writeString(script, "#!/bin/bash\necho '" + testOutput + "'\necho x > \"" + outputFile + "\"");
            script.toFile().setExecutable(true);
            command = List.of("bash", script.toString());
        }

        AgentProcess agent = AgentProcess.builder()
            .agentName("test")
            .round(0)
            .command(command)
            .workingDir(tempDir)
            .outputFile(outputFile)
            .stdoutLog(stdoutLog)
            .stderrLog(stderrLog)
            .timeoutMs(10_000)
            .gracePeriodMs(1_000)
            .outputValidator(validator)
            .build();

        agent.execute();

        // Give time for stream drain
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        assertTrue(Files.exists(stdoutLog));
        String stdout = Files.readString(stdoutLog);
        assertTrue(stdout.contains(testOutput) || stdout.contains("Hello"));
    }

    @Test
    void builder_missingAgentName_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .build());
    }

    @Test
    void builder_missingCommand_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .build());
    }

    @Test
    void builder_missingOutputValidator_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                // Missing outputValidator
                .build());
    }

    @Test
    void builder_missingWorkingDir_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of("echo", "test"))
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .build());
    }

    @Test
    void builder_missingOutputFile_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .build());
    }

    @Test
    void builder_missingStdoutLog_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .build());
    }

    @Test
    void builder_missingStderrLog_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .outputValidator(validator)
                .build());
    }

    @Test
    void builder_invalidTimeout_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .timeoutMs(0)
                .build());
    }

    @Test
    void builder_negativeGracePeriod_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .gracePeriodMs(-1)
                .build());
    }

    @Test
    void builder_blankAgentName_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("   ")
                .command(List.of("echo", "test"))
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .build());
    }

    @Test
    void builder_emptyCommand_throws() {
        assertThrows(IllegalStateException.class, () ->
            AgentProcess.builder()
                .agentName("test")
                .command(List.of())
                .workingDir(tempDir)
                .outputFile(outputFile)
                .stdoutLog(stdoutLog)
                .stderrLog(stderrLog)
                .outputValidator(validator)
                .build());
    }
}
