package im.arun.pageindex.toc;

import com.fasterxml.jackson.databind.JsonNode;
import im.arun.pageindex.config.PageIndexConfig;
import im.arun.pageindex.llm.JsonResponseParser;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.util.JsonLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects table of contents pages within a PDF document.
 * Equivalent to Python's find_toc_pages() and toc_detector_single_page().
 */
public class TocDetector {
    private static final Logger logger = LoggerFactory.getLogger(TocDetector.class);
    
    private final OpenAIClient llmClient;
    private final PromptBuilder promptBuilder;
    private final JsonResponseParser responseParser;
    
    public TocDetector(OpenAIClient llmClient) {
        this.llmClient = llmClient;
        this.promptBuilder = new PromptBuilder();
        this.responseParser = new JsonResponseParser();
    }
    
    /**
     * Find TOC pages in the document.
     * 
     * @param pages List of all pages
     * @param config Configuration
     * @param jsonLogger Optional logger
     * @return List of page indices that contain TOC
     */
    public List<Integer> findTocPages(List<PdfPage> pages, PageIndexConfig config, JsonLogger jsonLogger) {
        logger.info("Starting TOC page detection");
        List<Integer> tocPageList = new ArrayList<>();
        boolean lastPageWasYes = false;
        int i = 0;
        
        while (i < pages.size()) {
            // Only check beyond max_pages if we're still finding TOC pages
            if (i >= config.getTocCheckPageNum() && !lastPageWasYes) {
                break;
            }
            
            boolean detected = detectTocOnPage(pages.get(i), config.getModel());
            
            if (detected) {
                if (jsonLogger != null) {
                    jsonLogger.info("Page " + i + " has toc");
                }
                tocPageList.add(i);
                lastPageWasYes = true;
            } else if (lastPageWasYes) {
                if (jsonLogger != null) {
                    jsonLogger.info("Found the last page with toc: " + (i - 1));
                }
                break;
            }
            
            i++;
        }
        
        if (tocPageList.isEmpty() && jsonLogger != null) {
            jsonLogger.info("No toc found");
        }
        
        return tocPageList;
    }
    
    /**
     * Detect if a single page contains TOC.
     * 
     * @param page The page to check
     * @param model Model name
     * @return true if TOC detected
     */
    public boolean detectTocOnPage(PdfPage page, String model) {
        String prompt = promptBuilder.buildTocDetectionPrompt(page.getText());
        String response = llmClient.chat(model, prompt);
        JsonNode json = responseParser.extractJson(response);
        
        if (json.has("toc_detected")) {
            String tocDetected = json.get("toc_detected").asText();
            return "yes".equalsIgnoreCase(tocDetected);
        }
        
        return false;
    }
    
    /**
     * Detect if TOC has page indices/numbers.
     * 
     * @param tocContent The TOC content
     * @param model Model name
     * @return true if page indices are present in TOC
     */
    public boolean detectPageIndices(String tocContent, String model) {
        logger.info("Detecting page indices in TOC");
        String prompt = promptBuilder.buildPageIndexDetectionPrompt(tocContent);
        String response = llmClient.chat(model, prompt);
        JsonNode json = responseParser.extractJson(response);
        
        if (json.has("page_index_given_in_toc")) {
            String result = json.get("page_index_given_in_toc").asText();
            return "yes".equalsIgnoreCase(result);
        }
        
        return false;
    }
}
