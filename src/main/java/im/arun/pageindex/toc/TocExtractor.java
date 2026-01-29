package im.arun.pageindex.toc;

import com.fasterxml.jackson.databind.JsonNode;
import im.arun.pageindex.llm.JsonResponseParser;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.util.JsonLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts table of contents content from PDF pages.
 * Transforms dot leaders to colons and detects if page indices are present.
 * 
 * Python equivalent: toc_extractor() from page_index.py lines 219-235
 */
public class TocExtractor {
    private static final Logger logger = LoggerFactory.getLogger(TocExtractor.class);
    
    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final JsonResponseParser jsonParser;
    private final JsonLogger jsonLogger;

    public TocExtractor(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
        this.jsonParser = new JsonResponseParser();
        this.jsonLogger = new JsonLogger();
    }

    /**
     * Extract TOC content from specified pages and detect if page indices are present.
     * 
     * @param pages List of all PDF pages
     * @param tocPageIndices List of page indices containing TOC
     * @param model OpenAI model to use
     * @return Map with "toc_content" and "page_index_given_in_toc" keys
     */
    public Map<String, Object> extractToc(List<PdfPage> pages, List<Integer> tocPageIndices, String model) {
        jsonLogger.info("Starting TOC extraction");
        
        // Concatenate all TOC pages
        StringBuilder tocContent = new StringBuilder();
        for (Integer pageIndex : tocPageIndices) {
            if (pageIndex >= 0 && pageIndex < pages.size()) {
                tocContent.append(pages.get(pageIndex).getText());
            }
        }
        
        // Transform dots to colons (dot leaders)
        String transformedContent = transformDotsToColon(tocContent.toString());
        
        // Detect if page indices are present in TOC
        String hasPageIndex = detectPageIndex(transformedContent, model);
        
        Map<String, Object> result = new HashMap<>();
        result.put("toc_content", transformedContent);
        result.put("page_index_given_in_toc", hasPageIndex);
        
        jsonLogger.info("TOC extraction complete", Map.of(
            "has_page_index", hasPageIndex,
            "content_length", transformedContent.length()
        ));
        
        return result;
    }

    /**
     * Transform dot leaders (......) to colons (:).
     * Handles both consecutive dots and dots separated by spaces.
     * 
     * Python patterns:
     * - \.{5,} -> ': '
     * - (?:\. ){5,}\.? -> ': '
     */
    private String transformDotsToColon(String text) {
        // Handle consecutive dots (5 or more)
        text = text.replaceAll("\\.{5,}", ": ");
        
        // Handle dots separated by spaces (e.g., ". . . . . ")
        text = text.replaceAll("(?:\\. ){5,}\\.?", ": ");
        
        return text;
    }

    /**
     * Detect if page numbers/indices are present in the TOC.
     * Uses LLM to determine if TOC contains page references.
     * 
     * Python equivalent: detect_page_index() from page_index.py lines 199-217
     */
    private String detectPageIndex(String tocContent, String model) {
        jsonLogger.info("Detecting page index presence in TOC");
        
        String prompt = promptBuilder.buildPageIndexDetectionPrompt(tocContent);
        String response = openAIClient.chat(model, prompt);
        
        JsonNode jsonResponse = jsonParser.extractJson(response);
        String hasPageIndex = jsonResponse.has("page_index_given_in_toc") 
            ? jsonResponse.get("page_index_given_in_toc").asText()
            : "no";
        
        jsonLogger.info("Page index detection complete", Map.of(
            "page_index_detected", hasPageIndex
        ));
        
        return hasPageIndex;
    }
}
