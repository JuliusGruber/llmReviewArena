package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;
import dev.reviewarena.config.DockerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DockerCommandBuilderTest {

    private DockerCommandBuilder builder;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        builder = new DockerCommandBuilder();
    }

    @Test
    void build_includesDockerRunWithRequiredFlags() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent", "-p", "prompt.md");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertTrue(result.contains("docker"));
        assertTrue(result.contains("run"));
        assertTrue(result.contains("--rm"));
        assertTrue(result.contains("-i"));  // stdin forwarding
    }

    @Test
    void build_usesDefaultBridgeNetworkWhenNotConfigured() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        // Bridge is Docker's default - we omit --network flag
        assertFalse(result.contains("--network"));
    }

    @Test
    void build_usesConfiguredNetworkMode() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, "host");
        List<String> command = List.of("agent");

        // On non-Linux, host networking falls back to bridge (no --network flag)
        // On Linux, it would add --network host
        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            assertTrue(result.contains("--network"));
            int networkIdx = result.indexOf("--network");
            assertEquals("host", result.get(networkIdx + 1));
        } else {
            // On Windows/macOS, host networking is not supported
            assertFalse(result.contains("--network"));
        }
    }

    @Test
    void build_rejectsInvalidNetworkMode() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, "custom-invalid");
        List<String> command = List.of("agent");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.build("test", "claude", docker, command, tempDir, "test-container"));

        assertTrue(ex.getMessage().contains("Invalid Docker networkMode"));
        assertTrue(ex.getMessage().contains("custom-invalid"));
    }

    @Test
    void build_mountsWorkingDirectoryAsWorkspace() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertTrue(result.contains("-v"));
        String volumeMount = result.get(result.indexOf("-v") + 1);
        assertTrue(volumeMount.contains(tempDir.toAbsolutePath().toString()));
        assertTrue(volumeMount.endsWith(":/workspace"));

        assertTrue(result.contains("-w"));
        assertEquals("/workspace", result.get(result.indexOf("-w") + 1));
    }

    @Test
    void build_usesConfiguredImage() {
        DockerConfig docker = new DockerConfig(true, false, "custom-image:v1.0", null, null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("claude", "claude", docker, command, tempDir, "claude-container");

        assertTrue(result.contains("custom-image:v1.0"));
        assertFalse(result.contains("ghcr.io/zeeno-atl/claude-code:latest"));
    }

    @Test
    void build_usesDefaultImageForClaude() {
        DockerConfig docker = new DockerConfig(true, false, null, null, null, null);
        List<String> command = List.of("claude", "-p", "prompt.md");

        List<String> result = builder.build("claude", "claude", docker, command, tempDir, "claude-container");

        assertTrue(result.contains("ghcr.io/zeeno-atl/claude-code:latest"));
    }

    @Test
    void build_usesDefaultImageForGemini() {
        DockerConfig docker = new DockerConfig(true, false, null, null, null, null);
        List<String> command = List.of("gemini", "-p", "prompt.md");

        List<String> result = builder.build("gemini", "gemini", docker, command, tempDir, "gemini-container");

        assertTrue(result.contains("tgagor/gemini-cli:latest"));
    }

    @Test
    void build_throwsForCodexWithoutConfiguredImage() {
        DockerConfig docker = new DockerConfig(true, false, null, null, null, null);
        List<String> command = List.of("codex", "exec", "prompt.md");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.build("codex", "codex", docker, command, tempDir, "codex-container"));

        assertTrue(ex.getMessage().contains("No Docker image specified for agent 'codex'"));
        assertTrue(ex.getMessage().contains("no default available"));
        assertTrue(ex.getMessage().contains("Build locally"));
    }

    @Test
    void build_addsMemoryLimit() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", "4g", null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertTrue(result.contains("--memory"));
        assertEquals("4g", result.get(result.indexOf("--memory") + 1));
    }

    @Test
    void build_addsCpuLimit() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, "2", null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertTrue(result.contains("--cpus"));
        assertEquals("2", result.get(result.indexOf("--cpus") + 1));
    }

    @Test
    void build_addsResourceLimits() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", "8g", "4", null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertTrue(result.contains("--memory"));
        assertEquals("8g", result.get(result.indexOf("--memory") + 1));
        assertTrue(result.contains("--cpus"));
        assertEquals("4", result.get(result.indexOf("--cpus") + 1));
    }

    @Test
    void build_returnsImmutableList() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertThrows(UnsupportedOperationException.class, () -> result.add("extra"));
    }

    @Test
    void build_usesDefaultImageBasedOnType() {
        // Custom agent name with claude type - should use claude's default image
        DockerConfig docker = new DockerConfig(true, false, null, null, null, null);
        List<String> command = List.of("claude");

        List<String> result = builder.build("fast-reviewer", "claude", docker, command, tempDir, "fast-reviewer-container");

        assertTrue(result.contains("ghcr.io/zeeno-atl/claude-code:latest"));
    }

    // Input validation tests (Issue 1: Command injection prevention)

    @Test
    void build_rejectsMemoryWithInjectedFlags() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", "4g --privileged", null, null);
        List<String> command = List.of("agent");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.build("test", "claude", docker, command, tempDir, "test-container"));

        assertTrue(ex.getMessage().contains("Invalid Docker memory format"));
        assertTrue(ex.getMessage().contains("4g --privileged"));
    }

    @Test
    void build_rejectsCpusWithInjectedCommands() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, "2; rm -rf /", null);
        List<String> command = List.of("agent");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.build("test", "claude", docker, command, tempDir, "test-container"));

        assertTrue(ex.getMessage().contains("Invalid Docker cpus format"));
    }

    @Test
    void build_acceptsValidMemoryFormats() {
        // Lowercase
        assertDoesNotThrow(() -> builder.build("test", "claude",
            new DockerConfig(true, false, "test-image:latest", "512m", null, null),
            List.of("agent"), tempDir, "test-container"));

        // Uppercase
        assertDoesNotThrow(() -> builder.build("test", "claude",
            new DockerConfig(true, false, "test-image:latest", "4G", null, null),
            List.of("agent"), tempDir, "test-container"));

        // No suffix (bytes)
        assertDoesNotThrow(() -> builder.build("test", "claude",
            new DockerConfig(true, false, "test-image:latest", "1073741824", null, null),
            List.of("agent"), tempDir, "test-container"));
    }

    @Test
    void build_acceptsValidCpuFormats() {
        // Integer
        assertDoesNotThrow(() -> builder.build("test", "claude",
            new DockerConfig(true, false, "test-image:latest", null, "2", null),
            List.of("agent"), tempDir, "test-container"));

        // Decimal
        assertDoesNotThrow(() -> builder.build("test", "claude",
            new DockerConfig(true, false, "test-image:latest", null, "1.5", null),
            List.of("agent"), tempDir, "test-container"));

        // Small fraction
        assertDoesNotThrow(() -> builder.build("test", "claude",
            new DockerConfig(true, false, "test-image:latest", null, "0.5", null),
            List.of("agent"), tempDir, "test-container"));
    }

    // User mapping tests (Issue 4: File ownership)

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void build_addsUserMappingOnUnix() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertTrue(result.contains("--user"));
        int userIdx = result.indexOf("--user");
        String userMapping = result.get(userIdx + 1);
        assertTrue(userMapping.matches("\\d+:\\d+"), "Expected uid:gid format");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void build_omitsUserMappingOnWindows() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        assertFalse(result.contains("--user"));
    }

    // Path translation tests

    @Test
    void translatePathsInCommand_translatesPathUnderWorkingDir() {
        Path workingDir = tempDir;
        Path promptPath = workingDir.resolve(".arena/rounds/0/claude/prompt.md");
        List<String> command = List.of("claude", "-p", promptPath.toAbsolutePath().toString());

        List<String> translated = builder.translatePathsInCommand(command, workingDir);

        assertEquals("claude", translated.get(0));
        assertEquals("-p", translated.get(1));
        assertEquals("/workspace/.arena/rounds/0/claude/prompt.md", translated.get(2));
    }

    @Test
    void translatePathsInCommand_preservesNonPathArguments() {
        List<String> command = List.of("agent", "--flag", "value", "-p", "relative/path.md");

        List<String> translated = builder.translatePathsInCommand(command, tempDir);

        assertEquals(List.of("agent", "--flag", "value", "-p", "relative/path.md"), translated);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void translatePathsInCommand_throwsForUnixExternalAbsolutePath() {
        // Use a path that's clearly outside tempDir
        List<String> command = List.of("agent", "-p", "/etc/passwd");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.translatePathsInCommand(command, tempDir));

        assertTrue(ex.getMessage().contains("/etc/passwd"));
        assertTrue(ex.getMessage().contains("outside the project directory"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void translatePathsInCommand_throwsForWindowsExternalPath() {
        List<String> command = List.of("agent", "-p", "C:\\Windows\\System32\\config");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.translatePathsInCommand(command, tempDir));

        assertTrue(ex.getMessage().contains("C:\\Windows\\System32\\config"));
        assertTrue(ex.getMessage().contains("outside the project directory"));
    }

    @Test
    void translatePathsInCommand_passesRelativePathsThrough() {
        List<String> command = List.of("agent", "./src/main", "--auto-approve", "../parent");

        List<String> result = builder.translatePathsInCommand(command, tempDir);

        assertEquals(List.of("agent", "./src/main", "--auto-approve", "../parent"), result);
    }

    @Test
    void translatePathsInCommand_handlesInvalidPathSyntax() {
        // Arguments with characters that aren't valid path syntax should pass through
        List<String> command = List.of("agent", "--option=value", "some:text", "flag");

        List<String> result = builder.translatePathsInCommand(command, tempDir);

        assertEquals(command, result);
    }

    // toContainerPath tests

    @Test
    void toContainerPath_translatesPathUnderWorkingDir() {
        Path hostPath = tempDir.resolve(".arena/prompt.md");

        String containerPath = DockerCommandBuilder.toContainerPath(hostPath, tempDir);

        assertEquals("/workspace/.arena/prompt.md", containerPath);
    }

    @Test
    void toContainerPath_handlesNestedPaths() {
        Path hostPath = tempDir.resolve("deep/nested/path/file.txt");

        String containerPath = DockerCommandBuilder.toContainerPath(hostPath, tempDir);

        assertEquals("/workspace/deep/nested/path/file.txt", containerPath);
    }

    @Test
    void toContainerPath_throwsForPathOutsideWorkingDir() {
        Path hostPath = Path.of("/completely/different/path");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> DockerCommandBuilder.toContainerPath(hostPath, tempDir));

        assertTrue(ex.getMessage().contains("is not under working directory"));
    }

    @Test
    void toContainerPath_handlesWorkingDirItself() {
        String containerPath = DockerCommandBuilder.toContainerPath(tempDir, tempDir);

        assertEquals("/workspace/", containerPath);
    }

    // Security tests (Issue 86: API key exposure prevention)

    @Test
    void build_doesNotExposeApiKeyValuesInCommand() {
        // This test verifies that API keys are passed using "-e VAR" (without value)
        // instead of "-e VAR=value", preventing exposure in /proc/<pid>/cmdline
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", "claude", docker, command, tempDir, "test-container");

        // Check that no command argument contains "=" after an API key name
        List<String> apiKeyEnvVars = List.of(
            "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY"
        );

        for (String arg : result) {
            for (String keyName : apiKeyEnvVars) {
                // Verify the pattern "KEY=value" doesn't appear
                assertFalse(arg.startsWith(keyName + "="),
                    "API key value should not be exposed in command: " + arg);
            }
        }

        // If any API key is set in the environment, verify it's passed as just the name
        for (String keyName : apiKeyEnvVars) {
            if (System.getenv(keyName) != null) {
                assertTrue(result.contains(keyName),
                    "API key " + keyName + " should be passed without value");
            }
        }
    }

    @Test
    void buildSandbox_doesNotExposeApiKeyValuesInCommand() {
        // Same security check for sandbox mode
        DockerConfig docker = new DockerConfig(true, true, null, null, null, null);
        List<String> command = List.of("claude", "-p", "prompt.md");

        // containerName is ignored for sandbox mode
        List<String> result = builder.build("claude", "claude", docker, command, tempDir, null);

        List<String> apiKeyEnvVars = List.of(
            "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY"
        );

        for (String arg : result) {
            for (String keyName : apiKeyEnvVars) {
                assertFalse(arg.startsWith(keyName + "="),
                    "API key value should not be exposed in sandbox command: " + arg);
            }
        }
    }

    // Container name uniqueness tests (Issue 89)

    @Test
    void generateContainerName_appendsPidToBaseName() {
        String baseName = "claude";
        long expectedPid = ProcessHandle.current().pid();

        String containerName = DockerCommandBuilder.generateContainerName(baseName);

        assertEquals(baseName + "-" + expectedPid, containerName);
    }

    @Test
    void generateContainerName_worksWithSynthesisSuffix() {
        String baseName = "claude-synthesis";
        long expectedPid = ProcessHandle.current().pid();

        String containerName = DockerCommandBuilder.generateContainerName(baseName);

        assertEquals(baseName + "-" + expectedPid, containerName);
    }

    @Test
    void build_usesProvidedContainerName() {
        DockerConfig docker = new DockerConfig(true, false, "test-image:latest", null, null, null);
        List<String> command = List.of("agent");
        String uniqueContainerName = "claude-12345";

        List<String> result = builder.build("claude", "claude", docker, command, tempDir, uniqueContainerName);

        // Verify the unique container name is used in --name
        int nameIdx = result.indexOf("--name");
        assertTrue(nameIdx >= 0, "Should contain --name flag");
        assertEquals(uniqueContainerName, result.get(nameIdx + 1));
    }
}
