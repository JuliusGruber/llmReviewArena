package dev.reviewarena.cli;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.config.ConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.MissingParameterException;
import picocli.CommandLine.ParameterException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewArenaCliTest {

    private ReviewArenaCli cli;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        cli = new ReviewArenaCli();
        // Use mock GitService to avoid needing real git repository/commits
        cli.setGitServiceFactory(MockGitService::new);
        // Use mock ConfigLoader that returns a config with minAgents = 0
        // so tests don't fail when no agents succeed
        cli.setConfigLoader(createMockConfigLoader());
        commandLine = new CommandLine(cli);
    }

    private ConfigLoader createMockConfigLoader() {
        return new ConfigLoader() {
            @Override
            public ArenaConfig load(Path configFile, CliOverrides overrides) {
                // Create a config with mock agents for dry-run testing
                // For actual execution tests, we use --dry-run mode
                Map<String, AgentConfig> agents = Map.of(
                    "claude", AgentConfig.of("claude", List.of("claude", "-p", "@prompt.md")),
                    "codex", AgentConfig.of("codex", List.of("codex", "exec", "@prompt.md")),
                    "gemini", AgentConfig.of("gemini", List.of("gemini", "-p", "@prompt.md"))
                );
                return new ArenaConfig(
                    overrides.maxRounds() != null ? overrides.maxRounds() : 5,
                    500, // maxOutputSizeKb
                    overrides.maxConcurrent() != null ? overrides.maxConcurrent() : 0,
                    overrides.showAgentOutput() != null ? overrides.showAgentOutput() : true, // showAgentOutput
                    300_000, // agentTimeoutMs
                    900_000, // roundTimeoutMs
                    5_000, // gracePeriodMs
                    1, // minAgents
                    overrides.outputDir() != null ? overrides.outputDir() : Path.of(".arena"),
                    agents
                );
            }
        };
    }

    @Nested
    class PositionalArguments {

        @Test
        void parsesSingleCommit() {
            commandLine.parseArgs("abc1234");

            assertNotNull(cli.getReviewTarget());
            assertNotNull(cli.getReviewTarget().commitRefs);
            assertEquals("abc1234", cli.getReviewTarget().commitRefs.ref1);
            assertNull(cli.getReviewTarget().commitRefs.ref2);
            assertFalse(cli.getReviewTarget().staged);
        }

        @Test
        void parsesCommitRange() {
            commandLine.parseArgs("abc1234", "def5678");

            assertNotNull(cli.getReviewTarget().commitRefs);
            assertEquals("abc1234", cli.getReviewTarget().commitRefs.ref1);
            assertEquals("def5678", cli.getReviewTarget().commitRefs.ref2);
        }

        @Test
        void normalizeHashesToLowercase() {
            commandLine.parseArgs("ABC1234", "DEF5678");

            assertEquals("abc1234", cli.getReviewTarget().commitRefs.ref1);
            assertEquals("def5678", cli.getReviewTarget().commitRefs.ref2);
        }

        @Test
        void acceptsFullSha() {
            String fullSha = "abc1234567890abcdef1234567890abcdef12345";  // 40 chars
            commandLine.parseArgs(fullSha);

            assertEquals(fullSha, cli.getReviewTarget().commitRefs.ref1);
        }
    }

    @Nested
    class StagedFlag {

        @Test
        void parsesStaged() {
            commandLine.parseArgs("--staged");

            assertNotNull(cli.getReviewTarget());
            assertTrue(cli.getReviewTarget().staged);
            assertNull(cli.getReviewTarget().commitRefs);
        }

        @Test
        void stagedWithOptionsWorks() {
            commandLine.parseArgs("--staged", "-r", "10", "--dry-run");

            assertTrue(cli.getReviewTarget().staged);
            assertEquals(10, cli.getMaxRounds());
            assertTrue(cli.isDryRun());
        }
    }

    @Nested
    class MutualExclusivity {

        @Test
        void rejectsStagedWithCommit() {
            assertThrows(ParameterException.class, () ->
                commandLine.parseArgs("--staged", "abc1234"));
        }

        @Test
        void rejectsCommitWithStaged() {
            assertThrows(ParameterException.class, () ->
                commandLine.parseArgs("abc1234", "--staged"));
        }

        @Test
        void rejectsNoReviewTarget() {
            assertThrows(MissingParameterException.class, () ->
                commandLine.parseArgs("--dry-run"));
        }

        @Test
        void rejectsParallelAndSequential() {
            assertThrows(ParameterException.class, () ->
                commandLine.parseArgs("--parallel", "--sequential", "abc1234"));
        }

        @Test
        void acceptsParallelAlone() {
            commandLine.parseArgs("--parallel", "abc1234");

            assertNotNull(cli.getExecutionMode());
            assertTrue(cli.getExecutionMode().parallel);
            assertFalse(cli.getExecutionMode().sequential);
        }

        @Test
        void acceptsSequentialAlone() {
            commandLine.parseArgs("--sequential", "abc1234");

            assertNotNull(cli.getExecutionMode());
            assertFalse(cli.getExecutionMode().parallel);
            assertTrue(cli.getExecutionMode().sequential);
        }
    }

    @Nested
    class ConfigOptions {

        @Test
        void defaultConfigFile() {
            commandLine.parseArgs("abc1234");

            assertEquals(Path.of("arena.yaml"), cli.getConfigFile());
        }

        @Test
        void customConfigFile() {
            commandLine.parseArgs("-c", "custom.yaml", "abc1234");

            assertEquals(Path.of("custom.yaml"), cli.getConfigFile());
        }

        @Test
        void longConfigOption() {
            commandLine.parseArgs("--config", "my-config.yaml", "abc1234");

            assertEquals(Path.of("my-config.yaml"), cli.getConfigFile());
        }

        @Test
        void defaultRounds() {
            commandLine.parseArgs("abc1234");

            assertEquals(5, cli.getMaxRounds());
        }

        @Test
        void customRounds() {
            commandLine.parseArgs("-r", "10", "abc1234");

            assertEquals(10, cli.getMaxRounds());
        }

        @Test
        void longRoundsOption() {
            commandLine.parseArgs("--rounds", "3", "abc1234");

            assertEquals(3, cli.getMaxRounds());
        }

        @Test
        void defaultOutputDir() {
            commandLine.parseArgs("abc1234");

            assertEquals(Path.of(".arena"), cli.getOutputDir());
        }

        @Test
        void customOutputDir() {
            commandLine.parseArgs("-o", "output", "abc1234");

            assertEquals(Path.of("output"), cli.getOutputDir());
        }

        @Test
        void longOutputOption() {
            commandLine.parseArgs("--output", "./reviews", "abc1234");

            assertEquals(Path.of("./reviews"), cli.getOutputDir());
        }

        @Test
        void defaultMaxConcurrent() {
            commandLine.parseArgs("abc1234");

            assertEquals(0, cli.getMaxConcurrent());
        }

        @Test
        void customMaxConcurrent() {
            commandLine.parseArgs("--max-concurrent", "4", "abc1234");

            assertEquals(4, cli.getMaxConcurrent());
        }
    }

    @Nested
    class QuietMode {

        @Test
        void quietDefault() {
            commandLine.parseArgs("abc1234");

            assertFalse(cli.isQuiet());
        }

        @Test
        void quietEnabled() {
            commandLine.parseArgs("--quiet", "abc1234");

            assertTrue(cli.isQuiet());
        }

        @Test
        void quietShortEnabled() {
            commandLine.parseArgs("-q", "abc1234");

            assertTrue(cli.isQuiet());
        }

        @Test
        void quietWithOtherOptions() {
            commandLine.parseArgs("-q", "--dry-run", "abc1234");

            assertTrue(cli.isQuiet());
            assertTrue(cli.isDryRun());
        }
    }

    @Nested
    class DryRun {

        @Test
        void dryRunDefault() {
            commandLine.parseArgs("abc1234");

            assertFalse(cli.isDryRun());
        }

        @Test
        void dryRunEnabled() {
            commandLine.parseArgs("--dry-run", "abc1234");

            assertTrue(cli.isDryRun());
        }

        @Test
        void dryRunExitsZero() {
            int exitCode = commandLine.execute("--dry-run", "abc1234");

            assertEquals(0, exitCode);
        }

        @Test
        void dryRunPrintsSummaryWithSingleCommit() {
            var originalOut = System.out;
            try {
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                commandLine.execute("--dry-run", "abc1234");

                String output = baos.toString();
                assertTrue(output.contains("Dry run - would execute:"));
                assertTrue(output.contains("Review target: abc1234"));
                assertTrue(output.contains("Config file: arena.yaml"));
                assertTrue(output.contains("Output directory: .arena"));
                assertTrue(output.contains("Cross-pollination rounds: 5"));
                assertTrue(output.contains("Concurrency: unlimited"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        void dryRunPrintsSummaryWithCommitRange() {
            var originalOut = System.out;
            try {
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                commandLine.execute("--dry-run", "abc1234", "def5678");

                String output = baos.toString();
                assertTrue(output.contains("Review target: abc1234..def5678"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        void dryRunPrintsSummaryWithStaged() {
            var originalOut = System.out;
            try {
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                commandLine.execute("--dry-run", "--staged");

                String output = baos.toString();
                assertTrue(output.contains("Review target: --staged"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        void dryRunPrintsSummaryWithCustomConfig() {
            var originalOut = System.out;
            try {
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                commandLine.execute("--dry-run", "-c", "custom.yaml", "-r", "10", "-o", "output", "abc1234");

                String output = baos.toString();
                assertTrue(output.contains("Config file: custom.yaml"));
                assertTrue(output.contains("Output directory: output"));
                assertTrue(output.contains("Cross-pollination rounds: 10"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        void dryRunPrintsSequentialMode() {
            var originalOut = System.out;
            try {
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                commandLine.execute("--dry-run", "--sequential", "abc1234");

                String output = baos.toString();
                assertTrue(output.contains("Concurrency: 1"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        void dryRunPrintsMaxConcurrent() {
            var originalOut = System.out;
            try {
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                commandLine.execute("--dry-run", "--max-concurrent", "4", "abc1234");

                String output = baos.toString();
                assertTrue(output.contains("Concurrency: 4"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        void dryRunPrintsParallelAsUnlimited() {
            var originalOut = System.out;
            try {
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                commandLine.execute("--dry-run", "--parallel", "abc1234");

                String output = baos.toString();
                assertTrue(output.contains("Concurrency: unlimited"));
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    @Nested
    class HashValidation {

        @ParameterizedTest
        @ValueSource(strings = {"abc12", "12345", "ab"})
        void rejectsTooShortHash(String hash) {
            ParameterException ex = assertThrows(ParameterException.class, () ->
                commandLine.parseArgs(hash));
            assertTrue(ex.getMessage().contains("Invalid commit hash format"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "abc1234567890abcdef1234567890abcdef1234567",  // 41 chars
            "abc1234567890abcdef1234567890abcdef12345678"  // 42 chars
        })
        void rejectsTooLongHash(String hash) {
            ParameterException ex = assertThrows(ParameterException.class, () ->
                commandLine.parseArgs(hash));
            assertTrue(ex.getMessage().contains("Invalid commit hash format"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"xyz1234", "ghijklm", "abc123g"})
        void rejectsInvalidHexChars(String hash) {
            ParameterException ex = assertThrows(ParameterException.class, () ->
                commandLine.parseArgs(hash));
            assertTrue(ex.getMessage().contains("Invalid commit hash format"));
        }
    }

    @Nested
    class HelpAndVersion {

        @Test
        void helpExitsWithZero() {
            int exitCode = commandLine.execute("--help");
            assertEquals(0, exitCode);
        }

        @Test
        void versionExitsWithZero() {
            int exitCode = commandLine.execute("--version");
            assertEquals(0, exitCode);
        }

        @Test
        void helpContainsDescription() {
            StringWriter sw = new StringWriter();
            commandLine.setOut(new PrintWriter(sw));
            commandLine.execute("--help");

            String output = sw.toString();
            assertTrue(output.contains("Multi-round code review tournament"));
            assertTrue(output.contains("review-arena"));
        }

        @Test
        void versionShowsVersion() {
            StringWriter sw = new StringWriter();
            commandLine.setOut(new PrintWriter(sw));
            commandLine.execute("--version");

            assertTrue(sw.toString().contains("review-arena 1.0"));
        }

        @Test
        void helpShowsAllOptions() {
            StringWriter sw = new StringWriter();
            commandLine.setOut(new PrintWriter(sw));
            commandLine.execute("--help");

            String output = sw.toString();
            assertTrue(output.contains("--staged"));
            assertTrue(output.contains("--config"));
            assertTrue(output.contains("--rounds"));
            assertTrue(output.contains("--output"));
            assertTrue(output.contains("--parallel"));
            assertTrue(output.contains("--sequential"));
            assertTrue(output.contains("--max-concurrent"));
            assertTrue(output.contains("--dry-run"));
            assertTrue(output.contains("--quiet"));
        }
    }

    @Nested
    class CallMethod {

        @Test
        void callWithDryRunReturnsZero() {
            // Use --dry-run to skip agent execution (agent execution is covered by integration tests)
            commandLine.parseArgs("--dry-run", "abc1234");

            assertEquals(0, cli.call());
        }

        @Test
        void executeWithDryRunReturnsZero() {
            // Use --dry-run to skip agent execution
            int exitCode = commandLine.execute("--dry-run", "abc1234");

            assertEquals(0, exitCode);
        }

        @Test
        void executeWithStagedAndDryRunReturnsZero() {
            // Use --dry-run to skip agent execution
            int exitCode = commandLine.execute("--dry-run", "--staged");

            assertEquals(0, exitCode);
        }
    }

    @Nested
    class CombinedOptions {

        @Test
        void allOptionsWithCommit() {
            commandLine.parseArgs(
                "-c", "custom.yaml",
                "-r", "10",
                "-o", "output",
                "--max-concurrent", "3",
                "--parallel",
                "--dry-run",
                "abc1234", "def5678"
            );

            assertEquals(Path.of("custom.yaml"), cli.getConfigFile());
            assertEquals(10, cli.getMaxRounds());
            assertEquals(Path.of("output"), cli.getOutputDir());
            assertEquals(3, cli.getMaxConcurrent());
            assertTrue(cli.getExecutionMode().parallel);
            assertTrue(cli.isDryRun());
            assertEquals("abc1234", cli.getReviewTarget().commitRefs.ref1);
            assertEquals("def5678", cli.getReviewTarget().commitRefs.ref2);
        }

        @Test
        void allOptionsWithStaged() {
            commandLine.parseArgs(
                "--config", "my.yaml",
                "--rounds", "7",
                "--output", "./out",
                "--sequential",
                "--staged"
            );

            assertEquals(Path.of("my.yaml"), cli.getConfigFile());
            assertEquals(7, cli.getMaxRounds());
            assertEquals(Path.of("./out"), cli.getOutputDir());
            assertTrue(cli.getExecutionMode().sequential);
            assertTrue(cli.getReviewTarget().staged);
        }
    }
}
