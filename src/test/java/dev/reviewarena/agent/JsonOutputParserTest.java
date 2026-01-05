package dev.reviewarena.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JsonOutputParser}.
 */
class JsonOutputParserTest {

    private static final String VALID_JSON = """
        {
          "type": "result",
          "subtype": "success",
          "is_error": false,
          "duration_ms": 5579,
          "num_turns": 1,
          "result": "The assistant response text",
          "session_id": "abc-123",
          "total_cost_usd": 0.0616
        }
        """;

    private static final String ERROR_JSON = """
        {
          "type": "result",
          "subtype": "error",
          "is_error": true,
          "result": null
        }
        """;

    private static final String NO_RESULT_JSON = """
        {
          "type": "result",
          "subtype": "success",
          "is_error": false
        }
        """;

    @Test
    void extractResult_validJson_returnsResult() {
        String result = JsonOutputParser.extractResult(VALID_JSON);
        assertEquals("The assistant response text", result);
    }

    @Test
    void extractResult_nullInput_returnsNull() {
        assertNull(JsonOutputParser.extractResult(null));
    }

    @Test
    void extractResult_emptyInput_returnsNull() {
        assertNull(JsonOutputParser.extractResult(""));
        assertNull(JsonOutputParser.extractResult("   "));
    }

    @Test
    void extractResult_malformedJson_returnsNull() {
        assertNull(JsonOutputParser.extractResult("not json"));
        assertNull(JsonOutputParser.extractResult("{broken"));
    }

    @Test
    void extractResult_noResultField_returnsNull() {
        assertNull(JsonOutputParser.extractResult(NO_RESULT_JSON));
    }

    @Test
    void extractResult_nullResultField_returnsNull() {
        assertNull(JsonOutputParser.extractResult(ERROR_JSON));
    }

    @Test
    void extractResult_jsonArray_returnsNull() {
        assertNull(JsonOutputParser.extractResult("[1, 2, 3]"));
    }

    @Test
    void isError_errorResponse_returnsTrue() {
        assertTrue(JsonOutputParser.isError(ERROR_JSON));
    }

    @Test
    void isError_successResponse_returnsFalse() {
        assertFalse(JsonOutputParser.isError(VALID_JSON));
    }

    @Test
    void isError_nullInput_returnsFalse() {
        assertFalse(JsonOutputParser.isError(null));
    }

    @Test
    void isError_emptyInput_returnsFalse() {
        assertFalse(JsonOutputParser.isError(""));
    }

    @Test
    void isError_malformedJson_returnsFalse() {
        assertFalse(JsonOutputParser.isError("not json"));
    }

    @Test
    void isError_noIsErrorField_returnsFalse() {
        String json = """
            {
              "type": "result",
              "result": "some text"
            }
            """;
        assertFalse(JsonOutputParser.isError(json));
    }
}
