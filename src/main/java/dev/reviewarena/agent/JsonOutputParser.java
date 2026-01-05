package dev.reviewarena.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses Claude CLI's --output-format json output.
 *
 * <p>When Claude Code is run with {@code --output-format json}, it outputs a single JSON object
 * containing the result and metadata:
 * <pre>{@code
 * {
 *   "type": "result",
 *   "subtype": "success",
 *   "result": "The assistant's text response",
 *   "is_error": false,
 *   "total_cost_usd": 0.0616,
 *   "duration_ms": 5579,
 *   "usage": {...}
 * }
 * }</pre>
 *
 * <p>This class extracts the useful fields from this JSON output.
 */
public class JsonOutputParser {

    private static final Logger log = LoggerFactory.getLogger(JsonOutputParser.class);

    private JsonOutputParser() {
        // Utility class
    }

    /**
     * Extracts the .result field from Claude's JSON output.
     *
     * @param jsonContent The JSON string from stdout
     * @return The assistant's text response, or null if parsing fails or result is missing
     */
    public static String extractResult(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(jsonContent.trim()).getAsJsonObject();
            if (json.has("result") && !json.get("result").isJsonNull()) {
                return json.get("result").getAsString();
            }
            return null;
        } catch (JsonSyntaxException e) {
            // Debug level: non-JSON output is expected for agents like codex
            log.debug("Output is not JSON (expected for non-JSON agents): {}", e.getMessage());
            return null;
        } catch (IllegalStateException e) {
            log.debug("JSON output is not an object: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Checks if the JSON output indicates an error.
     *
     * @param jsonContent The JSON string from stdout
     * @return true if is_error field is true, false otherwise
     */
    public static boolean isError(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return false;
        }
        try {
            JsonObject json = JsonParser.parseString(jsonContent.trim()).getAsJsonObject();
            return json.has("is_error") && json.get("is_error").getAsBoolean();
        } catch (Exception e) {
            // If we can't parse, assume not an error
            return false;
        }
    }
}
