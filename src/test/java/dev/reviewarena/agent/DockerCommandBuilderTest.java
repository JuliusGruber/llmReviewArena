package dev.reviewarena.agent;

import dev.reviewarena.config.ConfigException;
import dev.reviewarena.config.DockerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        DockerConfig docker = new DockerConfig(true, "test-image:latest", null, null);
        List<String> command = List.of("agent", "-p", "prompt.md");

        List<String> result = builder.build("test", docker, command, tempDir);

        assertTrue(result.contains("docker"));
        assertTrue(result.contains("run"));
        assertTrue(result.contains("--rm"));
        assertTrue(result.contains("-i"));  // stdin forwarding
        assertTrue(result.contains("--network"));
        assertTrue(result.contains("host"));
    }

    @Test
    void build_mountsWorkingDirectoryAsWorkspace() {
        DockerConfig docker = new DockerConfig(true, "test-image:latest", null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", docker, command, tempDir);

        assertTrue(result.contains("-v"));
        String volumeMount = result.get(result.indexOf("-v") + 1);
        assertTrue(volumeMount.contains(tempDir.toAbsolutePath().toString()));
        assertTrue(volumeMount.endsWith(":/workspace"));

        assertTrue(result.contains("-w"));
        assertEquals("/workspace", result.get(result.indexOf("-w") + 1));
    }

    @Test
    void build_usesConfiguredImage() {
        DockerConfig docker = new DockerConfig(true, "custom-image:v1.0", null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("claude", docker, command, tempDir);

        assertTrue(result.contains("custom-image:v1.0"));
        assertFalse(result.contains("ghcr.io/zeeno-atl/claude-code:latest"));
    }

    @Test
    void build_usesDefaultImageForClaude() {
        DockerConfig docker = new DockerConfig(true, null, null, null);
        List<String> command = List.of("claude", "-p", "prompt.md");

        List<String> result = builder.build("claude", docker, command, tempDir);

        assertTrue(result.contains("ghcr.io/zeeno-atl/claude-code:latest"));
    }

    @Test
    void build_usesDefaultImageForGemini() {
        DockerConfig docker = new DockerConfig(true, null, null, null);
        List<String> command = List.of("gemini", "-p", "prompt.md");

        List<String> result = builder.build("gemini", docker, command, tempDir);

        assertTrue(result.contains("naoyoshinori/gemini-cli:node"));
    }

    @Test
    void build_throwsForCodexWithoutConfiguredImage() {
        DockerConfig docker = new DockerConfig(true, null, null, null);
        List<String> command = List.of("codex", "exec", "prompt.md");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.build("codex", docker, command, tempDir));

        assertTrue(ex.getMessage().contains("No Docker image specified for agent 'codex'"));
        assertTrue(ex.getMessage().contains("no default available"));
        assertTrue(ex.getMessage().contains("Build locally"));
    }

    @Test
    void build_addsMemoryLimit() {
        DockerConfig docker = new DockerConfig(true, "test-image:latest", "4g", null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", docker, command, tempDir);

        assertTrue(result.contains("--memory"));
        assertEquals("4g", result.get(result.indexOf("--memory") + 1));
    }

    @Test
    void build_addsCpuLimit() {
        DockerConfig docker = new DockerConfig(true, "test-image:latest", null, "2");
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", docker, command, tempDir);

        assertTrue(result.contains("--cpus"));
        assertEquals("2", result.get(result.indexOf("--cpus") + 1));
    }

    @Test
    void build_addsResourceLimits() {
        DockerConfig docker = new DockerConfig(true, "test-image:latest", "8g", "4");
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", docker, command, tempDir);

        assertTrue(result.contains("--memory"));
        assertEquals("8g", result.get(result.indexOf("--memory") + 1));
        assertTrue(result.contains("--cpus"));
        assertEquals("4", result.get(result.indexOf("--cpus") + 1));
    }

    @Test
    void build_returnsImmutableList() {
        DockerConfig docker = new DockerConfig(true, "test-image:latest", null, null);
        List<String> command = List.of("agent");

        List<String> result = builder.build("test", docker, command, tempDir);

        assertThrows(UnsupportedOperationException.class, () -> result.add("extra"));
    }

    @Test
    void build_agentNameIsCaseInsensitive() {
        DockerConfig docker = new DockerConfig(true, null, null, null);
        List<String> command = List.of("claude");

        List<String> result = builder.build("CLAUDE", docker, command, tempDir);

        assertTrue(result.contains("ghcr.io/zeeno-atl/claude-code:latest"));
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
    void translatePathsInCommand_throwsForExternalAbsolutePath() {
        // Use a path that's clearly outside tempDir
        List<String> command = List.of("agent", "-p", "/etc/passwd");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.translatePathsInCommand(command, tempDir));

        assertTrue(ex.getMessage().contains("/etc/passwd"));
        assertTrue(ex.getMessage().contains("outside the project directory"));
    }

    @Test
    void translatePathsInCommand_throwsForWindowsExternalPath() {
        List<String> command = List.of("agent", "-p", "C:\\Windows\\System32\\config");

        ConfigException ex = assertThrows(ConfigException.class,
            () -> builder.translatePathsInCommand(command, tempDir));

        assertTrue(ex.getMessage().contains("C:\\Windows\\System32\\config"));
        assertTrue(ex.getMessage().contains("outside the project directory"));
    }

    // looksLikeAbsolutePath tests

    @Test
    void looksLikeAbsolutePath_detectsUnixPaths() {
        assertTrue(builder.looksLikeAbsolutePath("/home/user/file.txt"));
        assertTrue(builder.looksLikeAbsolutePath("/etc/passwd"));
        assertTrue(builder.looksLikeAbsolutePath("/tmp/test"));
    }

    @Test
    void looksLikeAbsolutePath_detectsWindowsPaths() {
        assertTrue(builder.looksLikeAbsolutePath("C:\\Users\\test"));
        assertTrue(builder.looksLikeAbsolutePath("D:/projects/app"));
        assertTrue(builder.looksLikeAbsolutePath("E:\\"));
    }

    @Test
    void looksLikeAbsolutePath_ignoresFlags() {
        assertFalse(builder.looksLikeAbsolutePath("--flag"));
        assertFalse(builder.looksLikeAbsolutePath("--option=value"));
        assertFalse(builder.looksLikeAbsolutePath("-p"));
    }

    @Test
    void looksLikeAbsolutePath_ignoresRelativePaths() {
        assertFalse(builder.looksLikeAbsolutePath("relative/path.md"));
        assertFalse(builder.looksLikeAbsolutePath("./local/file"));
        assertFalse(builder.looksLikeAbsolutePath("../parent/file"));
        assertFalse(builder.looksLikeAbsolutePath("filename.txt"));
    }

    @Test
    void looksLikeAbsolutePath_ignoresPlainStrings() {
        assertFalse(builder.looksLikeAbsolutePath("value"));
        assertFalse(builder.looksLikeAbsolutePath(""));
        assertFalse(builder.looksLikeAbsolutePath("agent"));
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
}
