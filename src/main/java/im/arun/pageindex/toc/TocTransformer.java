package im.arun.pageindex.toc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import im.arun.pageindex.llm.JsonResponseParser;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.util.JsonLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Transforms raw TOC text into structured JSON format.
 * Handles continuation when LLM output is truncated due to max token limits.
 * 
 * Python equivalent: toc_transformer() from page_index.py lines 270-340
 */
public class TocTransformer {
    private static final Logger logger = LoggerFactory.getLogger(TocTransformer.class);
    private static final int MAX_RETRIES = 5;
    
    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final JsonResponseParser jsonParser;
    private final JsonLogger jsonLogger;
    private final ObjectMapper objectMapper;

    public TocTransformer(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
        this.jsonParser = new JsonResponseParser();
        this.jsonLogger = new JsonLogger();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Transform raw TOC content to structured JSON.
     * Handles incomplete responses and continuation generation.
     * 
     * @param tocContent Raw TOC text
     * @param model OpenAI model to use
     * @return List of TocItem objects
     */
    public List<TocItem> transformToc(String tocContent, String model) {
        jsonLogger.info("Starting TOC transformation");
        
        String prompt = promptBuilder.buildTocTransformationPrompt(tocContent);
        OpenAIClient.ChatResponse initialResponse = openAIClient.chatWithFinishReason(model, prompt, null);
        
        String lastComplete = initialResponse.content;
        String finishReason = initialResponse.finishReason;
        
        // Check if transformation is complete
        boolean isComplete = checkIfTransformationComplete(tocContent, lastComplete, model);
        
        if (isComplete && "finished".equals(finishReason)) {
            // Successfully completed in one pass
            JsonNode jsonResponse = jsonParser.extractJson(lastComplete);
            if (jsonResponse.has("table_of_contents")) {
                return parseAndConvertTocItems(jsonResponse.get("table_of_contents"));
            }
            return new ArrayList<>();
        }
        
        // Need to continue generation
        lastComplete = jsonParser.getJsonContent(lastComplete);
        int attempts = 0;
        
        while (!(isComplete && "finished".equals(finishReason)) && attempts < MAX_RETRIES) {
            attempts++;
            jsonLogger.info("Continuing TOC transformation", Map.of("attempt", attempts));
            
            // Truncate to last complete JSON object
            lastComplete = truncateToLastCompleteObject(lastComplete);
            
            String continuePrompt = buildContinuationPrompt(tocContent, lastComplete);
            OpenAIClient.ChatResponse continueResponse = openAIClient.chatWithFinishReason(model, continuePrompt, null);
            
            String newContent = continueResponse.content;
            finishReason = continueResponse.finishReason;
            
            // If new content starts with ```json, extract the content
            if (newContent.startsWith("```json")) {
                newContent = jsonParser.getJsonContent(newContent);
                lastComplete = lastComplete + newContent;
            } else {
                // Try to merge JSON structures
                lastComplete = mergeJsonStructures(lastComplete, newContent);
            }
            
            isComplete = checkIfTransformationComplete(tocContent, lastComplete, model);
        }
        
        if (attempts >= MAX_RETRIES) {
            logger.warn("Max retries reached for TOC transformation, using partial result");
        }
        
        // Parse final result
        try {
            String completeJson = ensureCompleteJson(lastComplete);
            JsonNode finalJson = objectMapper.readTree(completeJson);
            
            if (finalJson.has("table_of_contents")) {
                return parseAndConvertTocItems(finalJson.get("table_of_contents"));
            } else if (finalJson.isArray()) {
                return parseAndConvertTocItems(finalJson);
            }
        } catch (Exception e) {
            logger.error("Failed to parse final TOC JSON: {}", e.getMessage());
        }
        
        return new ArrayList<>();
    }

    /**
     * Check if TOC transformation is complete.
     * Python equivalent: check_if_toc_transformation_is_complete() from page_index.py lines 178-196
     */
    private boolean checkIfTransformationComplete(String rawToc, String transformedToc, String model) {
        String prompt = promptBuilder.buildTocTransformationCompletionCheckPrompt(rawToc, transformedToc);
        String response = openAIClient.chat(model, prompt);
        
        JsonNode jsonResponse = jsonParser.extractJson(response);
        String completed = jsonResponse.has("completed") 
            ? jsonResponse.get("completed").asText() 
            : "no";
        
        return "yes".equalsIgnoreCase(completed);
    }

    /**
     * Build continuation prompt for incomplete TOC.
     */
    private String buildContinuationPrompt(String rawToc, String incomplete) {
        return String.format("""
            Your task is to continue the table of contents json structure, directly output the remaining part of the json structure.
            The response should be in the following JSON format:
            
            The raw table of contents json structure is:
            %s
            
            The incomplete transformed table of contents json structure is:
            %s
            
            Please continue the json structure, directly output the remaining part of the json structure.""",
            rawToc, incomplete);
    }

    /**
     * Truncate JSON to last complete object (up to last '}').
     */
    private String truncateToLastCompleteObject(String json) {
        int lastBrace = json.lastIndexOf('}');
        if (lastBrace != -1 && lastBrace < json.length() - 1) {
            return json.substring(0, lastBrace + 2); // +2 to include the brace and potential comma
        }
        return json;
    }

    /**
     * Merge two JSON structures by concatenating array elements.
     */
    private String mergeJsonStructures(String existing, String newContent) {
        try {
            // Try to extract arrays from both and merge
            JsonNode existingNode = objectMapper.readTree(ensureCompleteJson(existing));
            JsonNode newNode = objectMapper.readTree(ensureCompleteJson(newContent));
            
            if (existingNode.has("table_of_contents") && newNode.has("table_of_contents")) {
                ArrayNode existingArray = (ArrayNode) existingNode.get("table_of_contents");
                ArrayNode newArray = (ArrayNode) newNode.get("table_of_contents");
                existingArray.addAll(newArray);
                return objectMapper.writeValueAsString(existingNode);
            }
        } catch (Exception e) {
            logger.warn("Failed to merge JSON structures, concatenating: {}", e.getMessage());
        }
        
        // Fallback: simple concatenation
        return existing + newContent;
    }

    /**
     * Ensure JSON is complete by adding closing brackets if needed.
     */
    private String ensureCompleteJson(String json) {
        json = json.trim();
        
        // Count opening and closing brackets
        long openBraces = json.chars().filter(ch -> ch == '{').count();
        long closeBraces = json.chars().filter(ch -> ch == '}').count();
        long openBrackets = json.chars().filter(ch -> ch == '[').count();
        long closeBrackets = json.chars().filter(ch -> ch == ']').count();
        
        // Add missing closing brackets
        for (long i = closeBrackets; i < openBrackets; i++) {
            json += "]";
        }
        
        // Add missing closing braces
        for (long i = closeBraces; i < openBraces; i++) {
            json += "}";
        }
        
        return json;
    }

    /**
     * Parse JSON node and convert to TocItem objects.
     * Also converts page numbers to integers.
     */
    private List<TocItem> parseAndConvertTocItems(JsonNode node) {
        List<TocItem> items = new ArrayList<>();
        
        if (!node.isArray()) {
            return items;
        }
        
        for (JsonNode itemNode : node) {
            TocItem item = new TocItem();
            
            if (itemNode.has("structure") && !itemNode.get("structure").isNull()) {
                item.setStructure(itemNode.get("structure").asText());
            }
            
            if (itemNode.has("title")) {
                item.setTitle(itemNode.get("title").asText());
            }
            
            if (itemNode.has("page") && !itemNode.get("page").isNull()) {
                Integer pageNum = convertPageToInt(itemNode.get("page"));
                item.setPage(pageNum);
            }
            
            if (itemNode.has("physical_index") && !itemNode.get("physical_index").isNull()) {
                String physicalIndex = itemNode.get("physical_index").asText();
                item.setPhysicalIndex(physicalIndex);
            }
            
            items.add(item);
        }
        
        jsonLogger.info("Parsed TOC items", Map.of("count", items.size()));
        return items;
    }

    /**
     * Convert page value to integer, handling various formats.
     * Python equivalent: convert_page_to_int() from utils.py lines 568-576
     */
    private Integer convertPageToInt(JsonNode pageNode) {
        if (pageNode.isInt()) {
            return pageNode.asInt();
        }
        
        if (pageNode.isTextual()) {
            String pageStr = pageNode.asText().trim();
            // Try to extract first number from string
            try {
                // Remove common prefixes and extract number
                pageStr = pageStr.replaceAll("[^0-9-]", "");
                if (!pageStr.isEmpty()) {
                    return Integer.parseInt(pageStr);
                }
            } catch (NumberFormatException e) {
                logger.debug("Failed to parse page number: {}", pageStr);
            }
        }
        
        return null;
    }
}
