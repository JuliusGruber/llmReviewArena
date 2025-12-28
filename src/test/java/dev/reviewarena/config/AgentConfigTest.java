package dev.reviewarena.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void testOf_createsEnabledAgentWithEmptyFlags() {
        AgentConfig config = AgentConfig.of("claude", List.of("claude", "-p"));

        assertEquals("claude", config.name());
        assertEquals(List.of("claude", "-p"), config.command());
        assertTrue(config.flags().isEmpty());
        assertTrue(config.enabled());
    }

    @Test
    void testOf_withFlags_createsEnabledAgent() {
        Map<String, Object> flags = Map.of("auto-approve", true, "model", "gpt-4");
        AgentConfig config = AgentConfig.of("codex", List.of("codex", "exec"), flags);

        assertEquals("codex", config.name());
        assertEquals(List.of("codex", "exec"), config.command());
        assertEquals(true, config.flags().get("auto-approve"));
        assertEquals("gpt-4", config.flags().get("model"));
        assertTrue(config.enabled());
    }

    @Test
    void testValidation_nullName_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new AgentConfig(null, List.of("cmd"), Map.of(), true));

        assertTrue(ex.getMessage().contains("name must not be null or blank"));
    }

    @Test
    void testValidation_blankName_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new AgentConfig("   ", List.of("cmd"), Map.of(), true));

        assertTrue(ex.getMessage().contains("name must not be null or blank"));
    }

    @Test
    void testValidation_nullCommand_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new AgentConfig("agent", null, Map.of(), true));

        assertTrue(ex.getMessage().contains("command must not be null or empty"));
    }

    @Test
    void testValidation_emptyCommand_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new AgentConfig("agent", List.of(), Map.of(), true));

        assertTrue(ex.getMessage().contains("command must not be null or empty"));
    }

    @Test
    void testCommand_isImmutable() {
        List<String> mutableCommand = new ArrayList<>(List.of("cmd", "arg"));
        AgentConfig config = AgentConfig.of("agent", mutableCommand);

        // Modifying original list doesn't affect config
        mutableCommand.add("new-arg");
        assertEquals(2, config.command().size());

        // Config's command is immutable
        assertThrows(UnsupportedOperationException.class,
            () -> config.command().add("another"));
    }

    @Test
    void testFlags_isImmutable() {
        Map<String, Object> mutableFlags = new HashMap<>();
        mutableFlags.put("key", "value");
        AgentConfig config = AgentConfig.of("agent", List.of("cmd"), mutableFlags);

        // Modifying original map doesn't affect config
        mutableFlags.put("new-key", "new-value");
        assertEquals(1, config.flags().size());

        // Config's flags are immutable
        assertThrows(UnsupportedOperationException.class,
            () -> config.flags().put("another", "value"));
    }

    @Test
    void testFlags_nullBecomesEmptyMap() {
        AgentConfig config = new AgentConfig("agent", List.of("cmd"), null, true);

        assertNotNull(config.flags());
        assertTrue(config.flags().isEmpty());
    }
}
