package im.arun.pageindex.toc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.arun.pageindex.llm.JsonResponseParser;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.util.JsonLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts physical page indices for TOC items from document pages.
 * Maps TOC section titles to their actual page locations in the document.
 * 
 * Python equivalent: toc_index_extractor() from page_index.py lines 240-266
 */
public class TocIndexExtractor {
    private static final Logger logger = LoggerFactory.getLogger(TocIndexExtractor.class);
    
    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final JsonResponseParser jsonParser;
    private final JsonLogger jsonLogger;
    private final ObjectMapper objectMapper;

    public TocIndexExtractor(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
        this.jsonParser = new JsonResponseParser();
        this.jsonLogger = new JsonLogger();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Extract physical indices for TOC items from document pages.
     * 
     * @param tocItems List of TOC items to map
     * @param pages Document pages with physical_index tags
     * @param model OpenAI model to use
     * @return Updated list of TOC items with physical_index populated
     */
    public List<TocItem> extractPhysicalIndices(List<TocItem> tocItems, List<PdfPage> pages, String model) {
        jsonLogger.info("Starting TOC physical index extraction");
        
        // Convert TOC items to JSON
        String tocJson = convertTocItemsToJson(tocItems);
        
        // Build content with physical_index tags
        String content = buildTaggedContent(pages);
        
        // Call LLM to extract physical indices
        String prompt = promptBuilder.buildTocIndexExtractionPrompt(tocJson, content);
        String response = openAIClient.chat(model, prompt);
        
        // Parse response
        JsonNode jsonResponse = jsonParser.extractJson(response);
        List<TocItem> updatedItems = parseUpdatedTocItems(jsonResponse);
        
        jsonLogger.info("Physical index extraction complete", Map.of(
            "total_items", tocItems.size(),
            "mapped_items", updatedItems.size()
        ));
        
        return updatedItems;
    }

    /**
     * Convert TOC items to JSON string for LLM prompt.
     */
    private String convertTocItemsToJson(List<TocItem> items) {
        List<Map<String, Object>> jsonItems = new ArrayList<>();
        
        for (TocItem item : items) {
            Map<String, Object> jsonItem = new java.util.HashMap<>();
            jsonItem.put("structure", item.getStructure());
            jsonItem.put("title", item.getTitle());
            
            if (item.getPage() != null) {
                jsonItem.put("page", item.getPage());
            }
            
            jsonItems.add(jsonItem);
        }
        
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonItems);
        } catch (Exception e) {
            logger.error("Failed to convert TOC items to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * Build document content with physical_index tags.
     * Format: <physical_index_X>page content</physical_index_X>
     */
    private String buildTaggedContent(List<PdfPage> pages) {
        StringBuilder content = new StringBuilder();
        
        for (PdfPage page : pages) {
            int pageIndex = page.getPageNumber();
            content.append(String.format("<physical_index_%d>", pageIndex));
            content.append(page.getText());
            content.append(String.format("</physical_index_%d>", pageIndex));
            content.append("\n");
        }
        
        return content.toString();
    }

    /**
     * Parse updated TOC items from LLM response.
     */
    private List<TocItem> parseUpdatedTocItems(JsonNode jsonResponse) {
        List<TocItem> items = new ArrayList<>();
        
        JsonNode itemsNode = jsonResponse;
        
        // If response is wrapped in an object, try to extract array
        if (jsonResponse.isObject()) {
            if (jsonResponse.has("table_of_contents")) {
                itemsNode = jsonResponse.get("table_of_contents");
            } else if (jsonResponse.has("items")) {
                itemsNode = jsonResponse.get("items");
            }
        }
        
        if (!itemsNode.isArray()) {
            logger.warn("Expected array in TOC index extraction response");
            return items;
        }
        
        for (JsonNode itemNode : itemsNode) {
            TocItem item = new TocItem();
            
            if (itemNode.has("structure") && !itemNode.get("structure").isNull()) {
                item.setStructure(itemNode.get("structure").asText());
            }
            
            if (itemNode.has("title")) {
                item.setTitle(itemNode.get("title").asText());
            }
            
            if (itemNode.has("page") && !itemNode.get("page").isNull()) {
                item.setPage(itemNode.get("page").asInt());
            }
            
            if (itemNode.has("physical_index") && !itemNode.get("physical_index").isNull()) {
                String physicalIndex = itemNode.get("physical_index").asText();
                
                // Extract integer from <physical_index_X> format
                Integer physicalIndexInt = extractPhysicalIndexInt(physicalIndex);
                if (physicalIndexInt != null) {
                    item.setPhysicalIndex(String.format("physical_index_%d", physicalIndexInt));
                } else {
                    item.setPhysicalIndex(physicalIndex);
                }
            }
            
            items.add(item);
        }
        
        return items;
    }

    /**
     * Extract integer from physical_index string.
     * Handles formats: "physical_index_5", "<physical_index_5>", "5"
     */
    private Integer extractPhysicalIndexInt(String physicalIndex) {
        if (physicalIndex == null || physicalIndex.trim().isEmpty()) {
            return null;
        }
        
        // Try to extract number from various formats
        String numStr = physicalIndex
            .replaceAll("[<>]", "")              // Remove < >
            .replaceAll("physical_index_", "")   // Remove prefix
            .trim();
        
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            logger.debug("Failed to parse physical index: {}", physicalIndex);
            return null;
        }
    }
}
