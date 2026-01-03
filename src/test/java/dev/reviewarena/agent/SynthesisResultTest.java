package dev.reviewarena.agent;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SynthesisResultTest {

    @Test
    void success_createsValidResult() {
        Path output = Path.of("/test/champion_review.md");
        SynthesisResult result = SynthesisResult.success("claude", 1000, output);

        assertEquals("claude", result.agentName());
        assertTrue(result.success());
        assertEquals(1000, result.durationMs());
        assertEquals(output, result.outputFile());
        assertNull(result.failureReason());
    }

    @Test
    void success_nullOutputFile_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            SynthesisResult.success("claude", 100, null));
        assertTrue(ex.getMessage().contains("outputFile"));
    }

    @Test
    void failed_createsValidResult() {
        SynthesisResult result = SynthesisResult.failed("claude", 500, "Exit code: 1");

        assertEquals("claude", result.agentName());
        assertFalse(result.success());
        assertEquals(500, result.durationMs());
        assertNull(result.outputFile());
        assertEquals("Exit code: 1", result.failureReason());
    }

    @Test
    void failed_nullReason_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            SynthesisResult.failed("claude", 100, null));
        assertTrue(ex.getMessage().contains("failureReason"));
    }

    @Test
    void timeout_createsValidResult() {
        SynthesisResult result = SynthesisResult.timeout("claude", 300000);

        assertEquals("claude", result.agentName());
        assertFalse(result.success());
        assertEquals(300000, result.durationMs());
        assertNull(result.outputFile());
        assertEquals("Synthesis timed out", result.failureReason());
    }

    @Test
    void constructor_nullAgentName_throws() {
        assertThrows(NullPointerException.class, () ->
            new SynthesisResult(null, true, 0, Path.of("/test"), null));
    }

    @Test
    void constructor_negativeDuration_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            SynthesisResult.success("claude", -1, Path.of("/test")));
        assertTrue(ex.getMessage().contains("durationMs"));
    }

    @Test
    void constructor_successWithoutOutputFile_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisResult("claude", true, 100, null, null));
        assertTrue(ex.getMessage().contains("outputFile"));
    }

    @Test
    void constructor_failedWithoutReason_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new SynthesisResult("claude", false, 100, null, null));
        assertTrue(ex.getMessage().contains("failureReason"));
    }
}
