package dev.reviewarena.config;

import dev.reviewarena.config.ConfigLoader.CliOverrides;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    private ConfigLoader loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new ConfigLoader(tempDir);
    }

    // ===== Loading from defaults =====

    @Test
    void testLoad_noConfigFile_usesDefaults() {
        // Load from non-existent arena.yaml - should use application.yaml defaults
        ArenaConfig config = loader.load(tempDir.resolve("nonexistent.yaml"), CliOverrides.none());

        // Values from application.yaml (single source of truth)
        assertEquals(5, config.maxRounds());
        assertEquals(500, config.maxOutputSizeKb());
        assertEquals(0, config.maxConcurrent());
        assertEquals(600_000, config.agentTimeoutMs());
    }

    @Test
    void testLoad_withApplicationYaml_loadsValues() {
        // application.yaml is always loaded from classpath
        ArenaConfig config = loader.load(tempDir.resolve("nonexistent.yaml"), CliOverrides.none());

        // Verify default agents from application.yaml are loaded
        assertFalse(config.agents().isEmpty());
        assertTrue(config.agents().containsKey("claude"));
        assertTrue(config.agents().containsKey("codex"));
        assertTrue(config.agents().containsKey("gemini"));
    }

    // ===== Loading from arena.yaml =====

    @Test
    void testLoad_arenaYamlExists_overridesDefaults() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            limits:
              rounds: 4
              max-output-size-kb: 1000
            execution:
              max-concurrent: 4
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        assertEquals(4, config.maxRounds());
        assertEquals(1000, config.maxOutputSizeKb());
        assertEquals(4, config.maxConcurrent());
    }

    @Test
    void testLoad_arenaYamlNotExists_usesDefaults() {
        Path nonExistent = tempDir.resolve("arena.yaml");
        assertFalse(Files.exists(nonExistent));

        ArenaConfig config = loader.load(nonExistent, CliOverrides.none());

        // Value from application.yaml (single source of truth)
        assertEquals(5, config.maxRounds());
    }

    @Test
    void testLoad_customConfigPath_loadsFromPath() throws IOException {
        Path customConfig = tempDir.resolve("custom-config.yaml");
        Files.writeString(customConfig, """
            limits:
              rounds: 3
            """);

        ArenaConfig config = loader.load(customConfig, CliOverrides.none());

        assertEquals(3, config.maxRounds());
    }

    // ===== CLI overrides =====

    @Test
    void testLoad_cliOverridesMaxRounds_takePrecedence() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            limits:
              rounds: 4
            """);

        CliOverrides overrides = new CliOverrides(3, null, null, null);
        ArenaConfig config = loader.load(arenaYaml, overrides);

        assertEquals(3, config.maxRounds()); // CLI wins over file
    }

    @Test
    void testLoad_cliOverridesMaxConcurrent_takePrecedence() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            execution:
              max-concurrent: 8
            """);

        CliOverrides overrides = new CliOverrides(null, 2, null, null);
        ArenaConfig config = loader.load(arenaYaml, overrides);

        assertEquals(2, config.maxConcurrent()); // CLI wins
    }

    @Test
    void testLoad_cliOverridesOutputDir_takePrecedence() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            output:
              dir: from-file
            """);

        Path cliOutputDir = Path.of("from-cli");
        CliOverrides overrides = new CliOverrides(null, null, null, cliOutputDir);
        ArenaConfig config = loader.load(arenaYaml, overrides);

        assertEquals(cliOutputDir, config.outputDir()); // CLI wins
    }

    @Test
    void testLoad_cliOverridesNull_usesConfigValue() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            limits:
              rounds: 4
            """);

        CliOverrides overrides = CliOverrides.none();
        ArenaConfig config = loader.load(arenaYaml, overrides);

        assertEquals(4, config.maxRounds()); // File value used when CLI is null
    }

    @Test
    void testLoad_roundsExceedsFive_cappedAtFive() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            limits:
              rounds: 15
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        assertEquals(5, config.maxRounds()); // Capped at 5
    }

    @Test
    void testLoad_cliRoundsExceedsFive_cappedAtFive() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            limits:
              rounds: 3
            """);

        CliOverrides overrides = new CliOverrides(10, null, null, null);
        ArenaConfig config = loader.load(arenaYaml, overrides);

        assertEquals(5, config.maxRounds()); // CLI override also capped at 5
    }

    // ===== Agent loading =====

    @Test
    void testLoad_singleAgent_createsAgentConfig() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              myagent:
                type: claude
                command:
                  - myagent
                  - run
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        assertTrue(config.agents().containsKey("myagent"));
        AgentConfig agent = config.agents().get("myagent");
        assertEquals("myagent", agent.name());
        assertEquals("claude", agent.type());
        assertEquals(2, agent.command().size());
        assertEquals("myagent", agent.command().get(0));
        assertEquals("run", agent.command().get(1));
    }

    @Test
    void testLoad_multipleAgents_createsAllConfigs() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              agent1:
                type: claude
                command:
                  - cmd1
              agent2:
                type: codex
                command:
                  - cmd2
              agent3:
                type: gemini
                command:
                  - cmd3
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        // Should have agents from arena.yaml plus defaults from application.yaml
        assertTrue(config.agents().containsKey("agent1"));
        assertTrue(config.agents().containsKey("agent2"));
        assertTrue(config.agents().containsKey("agent3"));
    }

    @Test
    void testLoad_agentWithFlags_loadsFlags() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              flagged:
                type: claude
                command:
                  - cmd
                flags:
                  auto-approve: true
                  model: gpt-4
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("flagged");
        assertNotNull(agent);
        assertEquals(true, agent.flags().get("auto-approve"));
        assertEquals("gpt-4", agent.flags().get("model"));
    }

    @Test
    void testLoad_agentDisabled_setsEnabledFalse() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              disabled-agent:
                type: claude
                command:
                  - cmd
                enabled: false
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("disabled-agent");
        assertNotNull(agent);
        assertFalse(agent.enabled());
    }

    @Test
    void testLoad_agentEnabledByDefault() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              default-enabled:
                type: claude
                command:
                  - cmd
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("default-enabled");
        assertNotNull(agent);
        assertTrue(agent.enabled()); // Default is enabled
    }

    // ===== Timeout settings =====

    @Test
    void testLoad_customTimeouts_loaded() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            timeouts:
              agent-timeout-ms: 60000
              round-timeout-ms: 180000
              grace-period-ms: 10000
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        assertEquals(60000, config.agentTimeoutMs());
        assertEquals(180000, config.roundTimeoutMs());
        assertEquals(10000, config.gracePeriodMs());
    }

    // ===== Error handling =====

    @Test
    void testLoad_invalidYaml_throwsConfigException() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            this is not valid yaml: [
            """);

        assertThrows(Exception.class, () -> loader.load(arenaYaml, CliOverrides.none()));
    }

    @Test
    void testLoad_defaultMethod_usesDefaults() {
        // The no-arg load() should work and use defaults
        // (looks for arena.yaml in baseDir which is tempDir - empty)
        ArenaConfig config = loader.load();

        assertNotNull(config);
        // Value from application.yaml (single source of truth)
        assertEquals(5, config.maxRounds());
    }

    @Test
    void testCliOverrides_none_hasAllNulls() {
        CliOverrides none = CliOverrides.none();

        assertNull(none.maxRounds());
        assertNull(none.maxConcurrent());
        assertNull(none.showAgentOutput());
        assertNull(none.outputDir());
    }

    @Test
    void testLoad_showAgentOutputDefault_isTrue() {
        ArenaConfig config = loader.load(tempDir.resolve("nonexistent.yaml"), CliOverrides.none());

        assertTrue(config.showAgentOutput()); // Default is true
    }

    @Test
    void testLoad_showAgentOutputFromFile_loaded() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            execution:
              show-agent-output: false
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        assertFalse(config.showAgentOutput());
    }

    @Test
    void testLoad_cliOverridesShowAgentOutput_takePrecedence() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            execution:
              show-agent-output: true
            """);

        CliOverrides overrides = new CliOverrides(null, null, false, null);
        ArenaConfig config = loader.load(arenaYaml, overrides);

        assertFalse(config.showAgentOutput()); // CLI wins
    }

    // ===== Docker config loading =====

    @Test
    void testLoad_agentWithoutDocker_defaultsToDisabled() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              myagent:
                type: claude
                command:
                  - myagent
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("myagent");
        assertNotNull(agent.docker());
        assertFalse(agent.docker().enabled());
    }

    @Test
    void testLoad_agentWithDockerEnabled_loadsConfig() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              dockeragent:
                type: claude
                command:
                  - agent
                docker:
                  enabled: true
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("dockeragent");
        assertNotNull(agent.docker());
        assertTrue(agent.docker().enabled());
    }

    @Test
    void testLoad_agentWithDockerDisabled_remainsDisabled() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              dockeragent:
                type: claude
                command:
                  - agent
                docker:
                  enabled: false
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("dockeragent");
        assertNotNull(agent.docker());
        assertFalse(agent.docker().enabled());
    }

    @Test
    void testLoad_agentWithFullDockerConfig_loadsAllFields() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              fullconfig:
                type: claude
                command:
                  - agent
                docker:
                  enabled: true
                  image: my-image:latest
                  memory: 4g
                  cpus: "2"
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("fullconfig");
        assertNotNull(agent.docker());
        assertTrue(agent.docker().enabled());
        assertEquals("my-image:latest", agent.docker().image());
        assertEquals("4g", agent.docker().memory());
        assertEquals("2", agent.docker().cpus());
    }

    @Test
    void testLoad_agentWithPartialDockerConfig_loadsSpecifiedFields() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              partialconfig:
                type: claude
                command:
                  - agent
                docker:
                  enabled: true
                  memory: 2g
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get("partialconfig");
        assertNotNull(agent.docker());
        assertTrue(agent.docker().enabled());
        assertNull(agent.docker().image()); // Not specified
        assertEquals("2g", agent.docker().memory());
        assertNull(agent.docker().cpus()); // Not specified
    }

    @Test
    void testLoad_agentWithTypeInferred_fromName() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              claude-fast:
                command:
                  - claude
            """);

        ArenaConfig config = loader.load(arenaYaml, CliOverrides.none());

        // Type should be inferred from name prefix "claude"
        AgentConfig agent = config.agents().get("claude-fast");
        assertEquals("claude", agent.type());
    }

    @Test
    void testLoad_agentWithUnknownType_throws() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              custom:
                type: unknown-type
                command:
                  - agent
            """);

        assertThrows(ConfigException.class, () -> loader.load(arenaYaml, CliOverrides.none()));
    }

    @Test
    void testLoad_agentWithNoTypeAndUnrecognizedName_throws() throws IOException {
        Path arenaYaml = tempDir.resolve("arena.yaml");
        Files.writeString(arenaYaml, """
            agents:
              custom-agent:
                command:
                  - agent
            """);

        assertThrows(ConfigException.class, () -> loader.load(arenaYaml, CliOverrides.none()));
    }
}
