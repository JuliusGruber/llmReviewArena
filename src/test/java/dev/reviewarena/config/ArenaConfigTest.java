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

        assertEquals(ArenaConfig.DEFAULT_MAX_ROUNDS, config.maxRounds());
        assertEquals(ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB, config.maxOutputSizeKb());
        assertEquals(ArenaConfig.DEFAULT_MAX_CONCURRENT, config.maxConcurrent());
        assertEquals(ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS, config.agentTimeoutMs());
        assertEquals(ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS, config.roundTimeoutMs());
        assertEquals(ArenaConfig.DEFAULT_GRACE_PERIOD_MS, config.gracePeriodMs());
        assertEquals(ArenaConfig.DEFAULT_ON_TIMEOUT, config.onTimeout());
        assertEquals(ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT, config.preservePartialOutput());
        assertEquals(ArenaConfig.DEFAULT_MIN_AGENTS, config.minAgents());
        assertEquals(Path.of(ArenaConfig.DEFAULT_OUTPUT_DIR), config.outputDir());
        assertTrue(config.agents().isEmpty());
    }

    @Test
    void testValidation_negativeMaxRounds_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.maxRounds = -1));

        assertTrue(ex.getMessage().contains("maxRounds must be non-negative"));
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
    void testValidation_invalidOnTimeout_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> createConfigWith(b -> b.onTimeout = "invalid"));

        assertTrue(ex.getMessage().contains("onTimeout must be 'kill-and-skip' or 'abort'"));
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
    void testValidation_zeroMaxRounds_allowed() {
        // 0 rounds means no cross-pollination, just initial round
        ArenaConfig config = createConfigWith(b -> b.maxRounds = 0);
        assertEquals(0, config.maxRounds());
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

    @Test
    void testValidation_abortOnTimeout_allowed() {
        ArenaConfig config = createConfigWith(b -> b.onTimeout = "abort");
        assertEquals("abort", config.onTimeout());
    }

    // Helper to create config with modifications
    private static class ConfigBuilder {
        int maxRounds = ArenaConfig.DEFAULT_MAX_ROUNDS;
        int maxOutputSizeKb = ArenaConfig.DEFAULT_MAX_OUTPUT_SIZE_KB;
        int maxConcurrent = ArenaConfig.DEFAULT_MAX_CONCURRENT;
        long agentTimeoutMs = ArenaConfig.DEFAULT_AGENT_TIMEOUT_MS;
        long roundTimeoutMs = ArenaConfig.DEFAULT_ROUND_TIMEOUT_MS;
        long gracePeriodMs = ArenaConfig.DEFAULT_GRACE_PERIOD_MS;
        String onTimeout = ArenaConfig.DEFAULT_ON_TIMEOUT;
        boolean preservePartialOutput = ArenaConfig.DEFAULT_PRESERVE_PARTIAL_OUTPUT;
        int minAgents = ArenaConfig.DEFAULT_MIN_AGENTS;
        Path outputDir = Path.of(ArenaConfig.DEFAULT_OUTPUT_DIR);
        Map<String, AgentConfig> agents = Map.of();

        ArenaConfig build() {
            return new ArenaConfig(
                maxRounds, maxOutputSizeKb, maxConcurrent,
                agentTimeoutMs, roundTimeoutMs, gracePeriodMs,
                onTimeout, preservePartialOutput, minAgents,
                outputDir, agents
            );
        }
    }

    private static ArenaConfig createConfigWith(java.util.function.Consumer<ConfigBuilder> modifier) {
        ConfigBuilder builder = new ConfigBuilder();
        modifier.accept(builder);
        return builder.build();
    }
}
