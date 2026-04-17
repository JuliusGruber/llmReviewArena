package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.config.ConfigLoader;
import dev.reviewarena.config.ConfigLoader.CliOverrides;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that the project's real arena.yaml produces subprocess
 * commands pinned to Claude Opus 4.7.
 *
 * <p>Loads the actual arena.yaml from the repo root, runs it through the same
 * ConfigLoader + CommandBuilder pipeline the runtime uses, and asserts the final
 * argv list that would be passed to ProcessBuilder contains --model claude-opus-4-7.
 */
class OpusModelIntegrationTest {

    private static final String EXPECTED_MODEL = "claude-opus-4-7";

    @Test
    void arenaYaml_claudeReviewAgent_spawnsWithOpus4_7() {
        List<String> command = buildRealCommandFor("claude-1");

        assertModelFlag(command);
    }

    @Test
    void arenaYaml_synthesisAgent_spawnsWithOpus4_7() {
        List<String> command = buildRealCommandFor("synthesis");

        assertModelFlag(command);
    }

    private List<String> buildRealCommandFor(String agentName) {
        Path projectRoot = Paths.get("").toAbsolutePath();
        Path arenaYaml = projectRoot.resolve("arena.yaml");
        assertTrue(arenaYaml.toFile().exists(),
            "arena.yaml missing at " + arenaYaml);

        ArenaConfig config = new ConfigLoader(projectRoot)
            .load(arenaYaml, CliOverrides.none());

        AgentConfig agent = config.agents().get(agentName);
        assertNotNull(agent, "Agent '" + agentName + "' not found in loaded config");

        return new CommandBuilder().build(
            agent,
            projectRoot.resolve("prompt.md"),
            projectRoot.resolve("review.md")
        );
    }

    private void assertModelFlag(List<String> command) {
        int idx = command.indexOf("--model");
        assertTrue(idx >= 0,
            "Command missing --model flag. Full command: " + command);
        assertTrue(idx + 1 < command.size(),
            "--model flag has no value. Full command: " + command);
        assertEquals(EXPECTED_MODEL, command.get(idx + 1),
            "Expected model " + EXPECTED_MODEL + " but got " + command.get(idx + 1)
                + ". Full command: " + command);
    }
}
