package im.arun.pageindex.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Robust JSON response parser for LLM outputs.
 * Handles various response formats including markdown-wrapped JSON.
 */
public class JsonResponseParser {
    private static final Logger logger = LoggerFactory.getLogger(JsonResponseParser.class);
    private final ObjectMapper objectMapper;

    public JsonResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Extract JSON from LLM response, handling markdown wrappers and common issues.
     */
    public JsonNode extractJson(String response) {
        if (response == null || response.trim().isEmpty()) {
            logger.error("Empty response provided to extractJson");
            return objectMapper.createObjectNode();
        }

        try {
            String cleaned = cleanJsonResponse(response);
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            logger.error("Failed to parse JSON from response: {}", e.getMessage());
            logger.debug("Original response: {}", response);
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Get JSON content from markdown-wrapped response.
     */
    public String getJsonContent(String response) {
        if (response == null) {
            return "{}";
        }

        // Find ```json delimiter
        int startIdx = response.indexOf("```json");
        if (startIdx != -1) {
            startIdx += 7; // Move past ```json
            response = response.substring(startIdx);
        }

        // Find closing ```
        int endIdx = response.lastIndexOf("```");
        if (endIdx != -1) {
            response = response.substring(0, endIdx);
        }

        return response.strip();
    }

    private String cleanJsonResponse(String response) {
        // Remove markdown delimiters
        String cleaned = getJsonContent(response);

        // Replace Python None with JSON null
        cleaned = cleaned.replace("None", "null");

        // Remove newlines and normalize whitespace
        cleaned = cleaned.replaceAll("\\n", " ").replaceAll("\\r", " ");
        cleaned = cleaned.replaceAll("\\s+", " ");

        // Try to clean up common JSON issues
        try {
            // First attempt to parse as-is
            objectMapper.readTree(cleaned);
            return cleaned;
        } catch (Exception e) {
            // If parsing fails, try cleaning trailing commas
            cleaned = cleaned.replaceAll(",\\s*]", "]");
            cleaned = cleaned.replaceAll(",\\s*}", "}");
            return cleaned;
        }
    }

    /**
     * Check if a string is valid JSON.
     */
    public boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
