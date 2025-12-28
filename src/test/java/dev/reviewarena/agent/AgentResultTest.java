package dev.reviewarena.agent;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AgentResultTest {

    @Test
    void success_createsSuccessResult() {
        Path output = Path.of("/test/review.md");
        AgentResult result = AgentResult.success("claude", 0, 0, 1000, output);

        assertEquals("claude", result.agentName());
        assertEquals(0, result.round());
        assertEquals(AgentResult.Status.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertEquals(1000, result.durationMs());
        assertEquals(output, result.outputFile());
        assertNull(result.failureReason());
        assertTrue(result.isSuccess());
    }

    @Test
    void failed_createsFailedResult() {
        AgentResult result = AgentResult.failed("codex", 1, 1, 500, "crash");

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertEquals("crash", result.failureReason());
        assertNull(result.outputFile());
        assertFalse(result.isSuccess());
    }

    @Test
    void timeout_createsTimeoutResult() {
        AgentResult result = AgentResult.timeout("gemini", 2, 30000);

        assertEquals(AgentResult.Status.TIMEOUT, result.status());
        assertEquals(-1, result.exitCode());
        assertEquals("Process timed out", result.failureReason());
        assertFalse(result.isSuccess());
    }

    @Test
    void invalidOutput_createsInvalidOutputResult() {
        AgentResult result = AgentResult.invalidOutput("claude", 0, 0, 100, "empty");

        assertEquals(AgentResult.Status.INVALID_OUTPUT, result.status());
        assertFalse(result.isSuccess());
    }

    @Test
    void constructor_rejectsNullAgentName() {
        assertThrows(NullPointerException.class, () ->
            new AgentResult(null, 0, AgentResult.Status.SUCCESS, 0, 0, null, null));
    }

    @Test
    void constructor_rejectsNegativeRound() {
        assertThrows(IllegalArgumentException.class, () ->
            AgentResult.success("test", -1, 0, 0, null));
    }

    @Test
    void constructor_rejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () ->
            AgentResult.success("test", 0, 0, -1, null));
    }
}
