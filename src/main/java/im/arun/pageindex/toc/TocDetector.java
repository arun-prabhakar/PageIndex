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

import im.arun.pageindex.util.ExecutorProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        logger.info("Starting TOC page detection (parallel batch)");

        int checkLimit = Math.min(config.getTocCheckPageNum(), pages.size());

        // Parallel batch: check all candidate pages concurrently
        ExecutorService executor = ExecutorProvider.getExecutor();
        List<CompletableFuture<Boolean>> futures = IntStream.range(0, checkLimit)
            .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                detectTocOnPage(pages.get(i), config.getModel()), executor))
            .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Collect results in order
        boolean[] results = new boolean[checkLimit];
        for (int i = 0; i < checkLimit; i++) {
            results[i] = futures.get(i).join();
        }

        // Find contiguous TOC page range
        List<Integer> tocPageList = new ArrayList<>();
        boolean foundToc = false;
        for (int i = 0; i < checkLimit; i++) {
            if (results[i]) {
                tocPageList.add(i);
                foundToc = true;
                if (jsonLogger != null) {
                    jsonLogger.info("Page " + i + " has toc");
                }
            } else if (foundToc) {
                // TOC pages ended
                if (jsonLogger != null) {
                    jsonLogger.info("Found the last page with toc: " + (i - 1));
                }
                break;
            }
        }

        // If last checked page was TOC, continue checking beyond the initial limit
        if (foundToc && !tocPageList.isEmpty() && tocPageList.get(tocPageList.size() - 1) == checkLimit - 1) {
            for (int i = checkLimit; i < pages.size(); i++) {
                boolean detected = detectTocOnPage(pages.get(i), config.getModel());
                if (detected) {
                    tocPageList.add(i);
                    if (jsonLogger != null) {
                        jsonLogger.info("Page " + i + " has toc");
                    }
                } else {
                    if (jsonLogger != null) {
                        jsonLogger.info("Found the last page with toc: " + (i - 1));
                    }
                    break;
                }
            }
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
