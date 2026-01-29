package im.arun.pageindex.verification;

import com.fasterxml.jackson.databind.JsonNode;
import im.arun.pageindex.llm.JsonResponseParser;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.util.JsonLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import im.arun.pageindex.util.ExecutorProvider;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * Verifies TOC items concurrently using LLM to check if titles appear on mapped pages.
 * Python equivalent: verify_toc() and check_title_appearance() from page_index.py lines 13-45, 892-944
 */
public class TocVerifier {
    private static final Logger logger = LoggerFactory.getLogger(TocVerifier.class);
    
    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final JsonResponseParser jsonParser;
    private final JsonLogger jsonLogger;

    public TocVerifier(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
        this.jsonParser = new JsonResponseParser();
        this.jsonLogger = new JsonLogger();
    }

    /**
     * Verification result containing accuracy and incorrect items.
     */
    public static class VerificationResult {
        public final double accuracy;
        public final List<Map<String, Object>> incorrectResults;
        
        public VerificationResult(double accuracy, List<Map<String, Object>> incorrectResults) {
            this.accuracy = accuracy;
            this.incorrectResults = incorrectResults;
        }
    }

    /**
     * Verify TOC items concurrently.
     * Python equivalent: verify_toc() lines 892-944
     */
    public VerificationResult verifyToc(
            List<PdfPage> pageList,
            List<TocItem> tocItems,
            int startIndex,
            String model,
            Integer sampleSize) {
        
        jsonLogger.info("Starting TOC verification");
        
        // Find last valid physical index
        Integer lastPhysicalIndex = null;
        for (int i = tocItems.size() - 1; i >= 0; i--) {
            if (tocItems.get(i).getStartIndex() != null) {
                lastPhysicalIndex = tocItems.get(i).getStartIndex();
                break;
            }
        }
        
        // Early return if insufficient data
        if (lastPhysicalIndex == null || lastPhysicalIndex < pageList.size() / 2) {
            return new VerificationResult(0.0, new ArrayList<>());
        }
        
        // Determine which items to check
        List<Integer> sampleIndices;
        if (sampleSize == null) {
            logger.info("Checking all {} items", tocItems.size());
            sampleIndices = new ArrayList<>();
            for (int i = 0; i < tocItems.size(); i++) {
                sampleIndices.add(i);
            }
        } else {
            int N = Math.min(sampleSize, tocItems.size());
            logger.info("Checking {} random items", N);
            sampleIndices = new Random().ints(0, tocItems.size())
                .distinct()
                .limit(N)
                .boxed()
                .collect(Collectors.toList());
        }
        
        // Prepare items with indices
        List<Map<String, Object>> indexedSampleList = new ArrayList<>();
        for (Integer idx : sampleIndices) {
            TocItem item = tocItems.get(idx);
            if (item.getStartIndex() != null) {
                Map<String, Object> itemWithIndex = new HashMap<>();
                itemWithIndex.put("title", item.getTitle());
                itemWithIndex.put("physical_index", item.getStartIndex());
                itemWithIndex.put("list_index", idx);
                indexedSampleList.add(itemWithIndex);
            }
        }
        
        // Run checks concurrently using CompletableFuture
        List<CompletableFuture<Map<String, Object>>> futures = indexedSampleList.stream()
            .map(item -> CompletableFuture.supplyAsync(() ->
                checkTitleAppearance(item, pageList, startIndex, model), ExecutorProvider.getExecutor()))
            .collect(Collectors.toList());
        
        // Wait for all futures to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        
        allFutures.join();
        
        // Collect results
        List<Map<String, Object>> results = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        
        // Process results
        int correctCount = 0;
        List<Map<String, Object>> incorrectResults = new ArrayList<>();
        
        for (Map<String, Object> result : results) {
            if ("yes".equals(result.get("answer"))) {
                correctCount++;
            } else {
                incorrectResults.add(result);
            }
        }
        
        // Calculate accuracy
        double accuracy = results.isEmpty() ? 0.0 : (double) correctCount / results.size();
        logger.info("Verification accuracy: {:.2f}%", accuracy * 100);
        
        return new VerificationResult(accuracy, incorrectResults);
    }

    /**
     * Check if a title appears on its mapped page.
     * Python equivalent: check_title_appearance() lines 13-45
     */
    private Map<String, Object> checkTitleAppearance(
            Map<String, Object> item,
            List<PdfPage> pageList,
            int startIndex,
            String model) {
        
        String title = (String) item.get("title");
        Integer listIndex = (Integer) item.get("list_index");
        
        if (!item.containsKey("physical_index") || item.get("physical_index") == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("list_index", listIndex);
            result.put("answer", "no");
            result.put("title", title);
            result.put("page_number", null);
            return result;
        }
        
        Integer pageNumber = (Integer) item.get("physical_index");
        int pageIndex = pageNumber - startIndex;
        
        if (pageIndex < 0 || pageIndex >= pageList.size()) {
            Map<String, Object> result = new HashMap<>();
            result.put("list_index", listIndex);
            result.put("answer", "no");
            result.put("title", title);
            result.put("page_number", pageNumber);
            return result;
        }
        
        String pageText = pageList.get(pageIndex).getText();
        
        String prompt = promptBuilder.buildTitleCheckPrompt(title, pageText);
        String response = openAIClient.chat(model, prompt);
        
        JsonNode jsonResponse = jsonParser.extractJson(response);
        String answer = jsonResponse.has("answer") ? jsonResponse.get("answer").asText() : "no";
        
        Map<String, Object> result = new HashMap<>();
        result.put("list_index", listIndex);
        result.put("answer", answer);
        result.put("title", title);
        result.put("page_number", pageNumber);
        
        return result;
    }

    /**
     * Check if titles appear at the start of their pages (concurrently).
     * Python equivalent: check_title_appearance_in_start_concurrent() lines 74-101
     */
    public List<TocItem> checkTitleAppearanceInStartConcurrent(
            List<TocItem> structure,
            List<PdfPage> pageList,
            String model) {
        
        jsonLogger.info("Checking title appearance at start concurrently");
        
        // Skip items without physical_index
        for (TocItem item : structure) {
            if (item.getStartIndex() == null) {
                item.setAppearStart("no");
            }
        }
        
        // Prepare tasks for items with valid physical_index
        List<TocItem> validItems = structure.stream()
            .filter(item -> item.getStartIndex() != null)
            .collect(Collectors.toList());
        
        ExecutorService executor = ExecutorProvider.getExecutor();
        List<CompletableFuture<String>> futures = validItems.stream()
            .map(item -> CompletableFuture.supplyAsync(() -> {
                int pageIndex = item.getStartIndex() - 1;
                if (pageIndex >= 0 && pageIndex < pageList.size()) {
                    String pageText = pageList.get(pageIndex).getText();
                    return checkTitleAppearanceInStart(item.getTitle(), pageText, model);
                }
                return "no";
            }, executor))
            .collect(Collectors.toList());
        
        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        List<String> results = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        
        // Update items with results
        for (int i = 0; i < validItems.size(); i++) {
            validItems.get(i).setAppearStart(results.get(i));
        }
        
        return structure;
    }

    /**
     * Check if a title appears at the start of a page.
     * Python equivalent: check_title_appearance_in_start() lines 48-71
     */
    private String checkTitleAppearanceInStart(String title, String pageText, String model) {
        String prompt = promptBuilder.buildTitleStartCheckPrompt(title, pageText);
        
        try {
            String response = openAIClient.chat(model, prompt);
            JsonNode jsonResponse = jsonParser.extractJson(response);
            return jsonResponse.has("start_begin") ? jsonResponse.get("start_begin").asText() : "no";
        } catch (Exception e) {
            logger.error("Error checking title start appearance: {}", e.getMessage());
            return "no";
        }
    }
}
