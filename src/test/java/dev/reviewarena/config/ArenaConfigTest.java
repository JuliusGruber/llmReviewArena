package dev.reviewarena.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArenaConfigTest {

    @Test
    void testDefaults_hasExpectedValues() {
        ArenaConfig config = ArenaConfig.defaults();

        // Values from application.yaml (single source of truth)
        assertEquals(3, config.maxRounds());
        assertEquals(500, config.maxOutputSizeKb());
        assertEquals(0, config.maxConcurrent());
        assertTrue(config.showAgentOutput());
        assertEquals(600_000, config.agentTimeoutMs());
        assertEquals(900_000, config.roundTimeoutMs());
        assertEquals(5_000, config.gracePeriodMs());
        assertEquals(1, config.minAgents()); // Changed from 2 to 1 for self-consistency
        assertEquals(Path.of(".arena"), config.outputDir());
        // Default agents are now loaded from application.yaml
        assertFalse(config.agents().isEmpty());
    }

    @Test
    void testValidation_negativeMaxRounds_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.maxRounds = -1));

        assertTrue(ex.getMessage().contains("maxRounds must be at least 1"));
    }

    @Test
    void testValidation_zeroMaxOutputSizeKb_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.maxOutputSizeKb = 0));

        assertTrue(ex.getMessage().contains("maxOutputSizeKb must be positive"));
    }

    @Test
    void testValidation_negativeMaxConcurrent_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.maxConcurrent = -1));

        assertTrue(ex.getMessage().contains("maxConcurrent must be non-negative"));
    }

    @Test
    void testValidation_zeroAgentTimeout_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.agentTimeoutMs = 0));

        assertTrue(ex.getMessage().contains("agentTimeoutMs must be positive"));
    }

    @Test
    void testValidation_zeroRoundTimeout_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.roundTimeoutMs = 0));

        assertTrue(ex.getMessage().contains("roundTimeoutMs must be positive"));
    }

    @Test
    void testValidation_negativeGracePeriod_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.gracePeriodMs = -1));

        assertTrue(ex.getMessage().contains("gracePeriodMs must be non-negative"));
    }

    @Test
    void testValidation_zeroMinAgents_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.minAgents = 0));

        assertTrue(ex.getMessage().contains("minAgents must be at least 1"));
    }

    @Test
    void testValidation_nullOutputDir_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.outputDir = null));

        assertTrue(ex.getMessage().contains("outputDir must not be null"));
    }

    @Test
    void testValidation_nullAgents_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.agents = null));

        assertTrue(ex.getMessage().contains("agents must not be null"));
    }

    @Test
    void testAgentsMap_isImmutable() {
        Map<String, AgentConfig> mutableAgents = new HashMap<>();
        mutableAgents.put("claude", AgentConfig.of("claude", List.of("claude", "-p")));

        ArenaConfig config = createConfigWith(b -> b.agents = mutableAgents);

        // Modifying original map doesn't affect config
        mutableAgents.put("codex", AgentConfig.of("codex", List.of("codex")));
        assertEquals(1, config.agents().size());

        // Config's agents map is immutable
        assertThrows(UnsupportedOperationException.class,
            () -> config.agents().put("gemini", AgentConfig.of("gemini", List.of("gemini"))));
    }

    @Test
    void testValidation_zeroMaxRounds_throws() {
        // maxRounds must be at least 1 (cross-pollination requires at least one round)
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.maxRounds = 0));

        assertTrue(ex.getMessage().contains("maxRounds must be at least 1"));
    }

    @Test
    void testValidation_zeroMaxConcurrent_allowed() {
        // 0 means unlimited
        ArenaConfig config = createConfigWith(b -> b.maxConcurrent = 0);
        assertEquals(0, config.maxConcurrent());
    }

    @Test
    void testValidation_zeroGracePeriod_allowed() {
        // 0 means no grace period
        ArenaConfig config = createConfigWith(b -> b.gracePeriodMs = 0);
        assertEquals(0, config.gracePeriodMs());
    }

    // Helper to create config with modifications
    // Values match application.yaml defaults
    private static class ConfigBuilder {
        int maxRounds = 3;
        int maxOutputSizeKb = 500;
        int maxConcurrent = 0;
        boolean showAgentOutput = true;
        long agentTimeoutMs = 600_000;
        long roundTimeoutMs = 900_000;
        long gracePeriodMs = 5_000;
        int minAgents = 1;
        Path outputDir = Path.of(".arena");
        Map<String, AgentConfig> agents = Map.of();

        ArenaConfig build() {
            return new ArenaConfig(
                maxRounds, maxOutputSizeKb, maxConcurrent, showAgentOutput,
                agentTimeoutMs, roundTimeoutMs, gracePeriodMs,
                minAgents, outputDir, agents
            );
        }
    }

    private static ArenaConfig createConfigWith(java.util.function.Consumer<ConfigBuilder> modifier) {
        ConfigBuilder builder = new ConfigBuilder();
        modifier.accept(builder);
        return builder.build();
    }
}
