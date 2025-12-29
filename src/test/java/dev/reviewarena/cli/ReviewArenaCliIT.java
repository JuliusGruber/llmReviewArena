package dev.reviewarena.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that run the CLI as a subprocess.
 * These tests build the JAR and execute it via ProcessBuilder.
 */
class ReviewArenaCliIT {

    private static Path jarPath;

    @BeforeAll
    static void buildJar() throws IOException, InterruptedException {
        // Build the JAR before running tests
        String mvnCommand = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        ProcessBuilder pb = new ProcessBuilder(mvnCommand, "package", "-DskipTests", "-q");
        pb.directory(Path.of(System.getProperty("user.dir")).toFile());
        pb.inheritIO();
        Process process = pb.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        assertTrue(finished, "Maven build should complete within 120 seconds");
        assertEquals(0, process.exitValue(), "Maven build should succeed");

        // Find the built JAR
        jarPath = Path.of("target", "review-arena-1.0-SNAPSHOT.jar");
        assertTrue(Files.exists(jarPath), "JAR should exist after build");
    }

    private ProcessResult runCli(Path workingDir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 3];
        command[0] = "java";
        command[1] = "-jar";
        command[2] = jarPath.toAbsolutePath().toString();
        System.arraycopy(args, 0, command, 3, args.length);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(false);

        Process process = pb.start();

        String stdout;
        String stderr;
        try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stdout = outReader.lines().collect(Collectors.joining("\n"));
            stderr = errReader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertTrue(finished, "CLI should complete within 30 seconds");

        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private ProcessResult runCli(String... args) throws IOException, InterruptedException {
        return runCli(null, args);
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {}

    @Nested
    class HelpTests {

        @Test
        void testHelp_showsUsage() throws IOException, InterruptedException {
            ProcessResult result = runCli("--help");

            assertEquals(0, result.exitCode(), "Exit code should be 0");
            assertTrue(result.stdout().contains("Usage:"), "Should show usage text");
            assertTrue(result.stdout().contains("review-arena"), "Should contain program name");
            assertTrue(result.stdout().contains("--staged"), "Should show --staged option");
            assertTrue(result.stdout().contains("--dry-run"), "Should show --dry-run option");
        }

        @Test
        void testVersion_showsVersion() throws IOException, InterruptedException {
            ProcessResult result = runCli("--version");

            assertEquals(0, result.exitCode(), "Exit code should be 0");
            assertTrue(result.stdout().contains("review-arena 1.0"), "Should show version");
        }
    }

    @Nested
    class ArgumentValidationTests {

        @Test
        void testNoArgs_exitsWithError() throws IOException, InterruptedException {
            ProcessResult result = runCli();

            assertEquals(2, result.exitCode(), "Exit code should be 2 for usage error");
            assertTrue(result.stderr().contains("Missing required") || result.stderr().contains("Error"),
                "Should show error message");
        }

        @Test
        void testStagedWithCommit_exitsWithError() throws IOException, InterruptedException {
            ProcessResult result = runCli("--staged", "abc1234");

            assertEquals(2, result.exitCode(), "Exit code should be 2 for mutual exclusivity violation");
        }

        @Test
        void testInvalidHashFormat_exitsWithError() throws IOException, InterruptedException {
            ProcessResult result = runCli("xyz1234");

            assertEquals(2, result.exitCode(), "Exit code should be 2 for invalid hash format");
            assertTrue(result.stderr().contains("Invalid commit hash format"),
                "Should mention invalid hash format");
        }

        @Test
        void testHashTooShort_exitsWithError() throws IOException, InterruptedException {
            ProcessResult result = runCli("abc12");

            assertEquals(2, result.exitCode(), "Exit code should be 2 for hash too short");
            assertTrue(result.stderr().contains("Invalid commit hash format"),
                "Should mention invalid hash format");
        }
    }

    @Nested
    class DryRunTests {

        @Test
        void testDryRunWithStaged_showsSummary() throws IOException, InterruptedException {
            ProcessResult result = runCli("--dry-run", "--staged");

            assertEquals(0, result.exitCode(), "Exit code should be 0");
            assertTrue(result.stdout().contains("Dry run - would execute:"),
                "Should show dry run message");
            assertTrue(result.stdout().contains("Review target: --staged"),
                "Should show staged as review target");
        }

        @Test
        void testDryRunWithCommit_showsSummary() throws IOException, InterruptedException {
            ProcessResult result = runCli("--dry-run", "abc1234");

            assertEquals(0, result.exitCode(), "Exit code should be 0");
            assertTrue(result.stdout().contains("Dry run - would execute:"),
                "Should show dry run message");
            assertTrue(result.stdout().contains("Review target: abc1234"),
                "Should show commit as review target");
        }

        @Test
        void testDryRunWithCustomOptions_showsSummary() throws IOException, InterruptedException {
            ProcessResult result = runCli("--dry-run", "-c", "custom.yaml", "-r", "10", "abc1234");

            assertEquals(0, result.exitCode(), "Exit code should be 0");
            assertTrue(result.stdout().contains("Config file: custom.yaml"),
                "Should show custom config file");
            assertTrue(result.stdout().contains("Cross-pollination rounds: 10"),
                "Should show custom max rounds");
        }
    }

    @Nested
    class GitValidationTests {

        @TempDir
        Path tempDir;

        @Test
        @Disabled("Requires git validation implementation - see startup-validation-plan.md")
        void testNotGitRepo_exitsWithError() throws IOException, InterruptedException {
            // Create a non-git directory
            Path nonGitDir = tempDir.resolve("not-a-repo");
            Files.createDirectories(nonGitDir);

            ProcessResult result = runCli(nonGitDir, "abc1234");

            assertEquals(3, result.exitCode(), "Exit code should be 3 for git error");
            assertTrue(result.stderr().contains("Not a git repository"),
                "Should mention not a git repository");
        }

        @Test
        @Disabled("Requires git validation implementation - see startup-validation-plan.md")
        void testInvalidCommit_exitsWithError() throws IOException, InterruptedException {
            // Run in the current git repo with a non-existent commit
            ProcessResult result = runCli("0000000000000000000000000000000000000000");

            assertEquals(3, result.exitCode(), "Exit code should be 3 for commit not found");
            assertTrue(result.stderr().contains("not found"),
                "Should mention commit not found");
        }

        @Test
        @Disabled("Requires git validation implementation - see startup-validation-plan.md")
        void testValidCommit_succeeds() throws IOException, InterruptedException {
            // Create a temp git repo with a commit
            Path gitDir = tempDir.resolve("test-repo");
            Files.createDirectories(gitDir);

            // Initialize git repo
            runCommand(gitDir, "git", "init");
            runCommand(gitDir, "git", "config", "user.email", "test@test.com");
            runCommand(gitDir, "git", "config", "user.name", "Test User");

            // Create a file and commit
            Files.writeString(gitDir.resolve("test.txt"), "test content");
            runCommand(gitDir, "git", "add", ".");
            runCommand(gitDir, "git", "commit", "-m", "Initial commit");

            // Get the commit hash
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(gitDir.toFile());
            Process process = pb.start();
            String commitHash;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                commitHash = reader.readLine().trim();
            }
            process.waitFor();

            // Run CLI with the valid commit
            ProcessResult result = runCli(gitDir, "--dry-run", commitHash);

            assertEquals(0, result.exitCode(), "Exit code should be 0 for valid commit");
        }

        private void runCommand(Path dir, String... command) throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir.toFile());
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor(30, TimeUnit.SECONDS);
        }
    }
}
