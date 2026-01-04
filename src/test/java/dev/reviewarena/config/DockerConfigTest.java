package dev.reviewarena.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DockerConfigTest {

    @Test
    void testDisabled_createsDisabledConfig() {
        DockerConfig config = DockerConfig.disabled();

        assertFalse(config.enabled());
        assertNull(config.image());
        assertNull(config.memory());
        assertNull(config.cpus());
    }

    @Test
    void testConstructor_allFields() {
        DockerConfig config = new DockerConfig(true, "my-image:latest", "4g", "2");

        assertTrue(config.enabled());
        assertEquals("my-image:latest", config.image());
        assertEquals("4g", config.memory());
        assertEquals("2", config.cpus());
    }

    @Test
    void testConstructor_enabledWithNullOptionals() {
        DockerConfig config = new DockerConfig(true, null, null, null);

        assertTrue(config.enabled());
        assertNull(config.image());
        assertNull(config.memory());
        assertNull(config.cpus());
    }

    @Test
    void testConstructor_partialFields() {
        DockerConfig config = new DockerConfig(true, "image:tag", "2g", null);

        assertTrue(config.enabled());
        assertEquals("image:tag", config.image());
        assertEquals("2g", config.memory());
        assertNull(config.cpus());
    }

    @Test
    void testEquals_sameValues() {
        DockerConfig config1 = new DockerConfig(true, "img", "1g", "1");
        DockerConfig config2 = new DockerConfig(true, "img", "1g", "1");

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void testEquals_disabled() {
        DockerConfig config1 = DockerConfig.disabled();
        DockerConfig config2 = DockerConfig.disabled();

        assertEquals(config1, config2);
    }
}
