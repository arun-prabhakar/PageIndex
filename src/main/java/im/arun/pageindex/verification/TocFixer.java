package im.arun.pageindex.verification;

import com.fasterxml.jackson.databind.JsonNode;
import im.arun.pageindex.llm.JsonResponseParser;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.util.JsonLogger;
import im.arun.pageindex.util.TreeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import im.arun.pageindex.util.ExecutorProvider;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * Fixes incorrect TOC mappings by finding correct physical indices.
 * Python equivalent: fix_incorrect_toc() and fix_incorrect_toc_with_retries() 
 * from page_index.py lines 752-886
 */
public class TocFixer {
    private static final Logger logger = LoggerFactory.getLogger(TocFixer.class);
    
    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final JsonResponseParser jsonParser;
    private final TocVerifier tocVerifier;
    private final JsonLogger jsonLogger;

    public TocFixer(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
        this.jsonParser = new JsonResponseParser();
        this.tocVerifier = new TocVerifier(openAIClient);
        this.jsonLogger = new JsonLogger();
    }

    /**
     * Fix result containing updated TOC and remaining incorrect items.
     */
    public static class FixResult {
        public final List<TocItem> tocItems;
        public final List<Map<String, Object>> incorrectResults;
        
        public FixResult(List<TocItem> tocItems, List<Map<String, Object>> incorrectResults) {
            this.tocItems = tocItems;
            this.incorrectResults = incorrectResults;
        }
    }

    /**
     * Fix incorrect TOC with retries.
     * Python equivalent: fix_incorrect_toc_with_retries() lines 870-886
     */
    public FixResult fixIncorrectTocWithRetries(
            List<TocItem> tocItems,
            List<PdfPage> pageList,
            List<Map<String, Object>> incorrectResults,
            int startIndex,
            int maxAttempts,
            String model) {
        
        jsonLogger.info("Starting TOC fixing with retries", Map.of("max_attempts", maxAttempts));
        
        List<TocItem> currentToc = new ArrayList<>(tocItems);
        List<Map<String, Object>> currentIncorrect = new ArrayList<>(incorrectResults);
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            logger.info("Fix attempt {}/{}", attempt + 1, maxAttempts);
            
            FixResult result = fixIncorrectToc(currentToc, pageList, currentIncorrect, startIndex, model);
            currentToc = result.tocItems;
            currentIncorrect = result.incorrectResults;
            
            if (currentIncorrect.isEmpty()) {
                logger.info("All items fixed successfully");
                break;
            }
            
            logger.info("Remaining incorrect items: {}", currentIncorrect.size());
        }
        
        return new FixResult(currentToc, currentIncorrect);
    }

    /**
     * Fix incorrect TOC items concurrently.
     * Python equivalent: fix_incorrect_toc() lines 752-866
     */
    public FixResult fixIncorrectToc(
            List<TocItem> tocItems,
            List<PdfPage> pageList,
            List<Map<String, Object>> incorrectResults,
            int startIndex,
            String model) {
        
        logger.info("Starting TOC fix with {} incorrect results", incorrectResults.size());
        
        Set<Integer> incorrectIndices = incorrectResults.stream()
            .map(result -> (Integer) result.get("list_index"))
            .collect(Collectors.toSet());
        
        int endIndex = pageList.size() + startIndex - 1;
        
        // Process incorrect items concurrently using shared executor
        ExecutorService executor = ExecutorProvider.getExecutor();
        List<CompletableFuture<Map<String, Object>>> futures = incorrectResults.stream()
            .map(incorrectItem -> CompletableFuture.supplyAsync(() ->
                processAndCheckItem(incorrectItem, tocItems, pageList, incorrectIndices,
                                   startIndex, endIndex, model), executor))
            .collect(Collectors.toList());
        
        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        List<Map<String, Object>> results = futures.stream()
            .map(future -> {
                try {
                    return future.join();
                } catch (Exception e) {
                    logger.error("Error processing item", e);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        // Update TOC with fixed indices
        List<Map<String, Object>> invalidResults = new ArrayList<>();
        
        for (Map<String, Object> result : results) {
            Boolean isValid = (Boolean) result.get("is_valid");
            Integer listIdx = (Integer) result.get("list_index");
            
            if (Boolean.TRUE.equals(isValid)) {
                if (listIdx >= 0 && listIdx < tocItems.size()) {
                    Integer physicalIndex = (Integer) result.get("physical_index");
                    tocItems.get(listIdx).setStartIndex(physicalIndex);
                    tocItems.get(listIdx).setPhysicalIndex(String.format("physical_index_%d", physicalIndex));
                }
            } else {
                invalidResults.add(result);
            }
        }
        
        logger.info("Fixed {} items, {} remain invalid", 
            results.size() - invalidResults.size(), invalidResults.size());
        
        return new FixResult(tocItems, invalidResults);
    }

    /**
     * Process and check a single incorrect item.
     */
    private Map<String, Object> processAndCheckItem(
            Map<String, Object> incorrectItem,
            List<TocItem> tocItems,
            List<PdfPage> pageList,
            Set<Integer> incorrectIndices,
            int startIndex,
            int endIndex,
            String model) {
        
        Integer listIndex = (Integer) incorrectItem.get("list_index");
        String title = (String) incorrectItem.get("title");
        
        // Validate list index
        if (listIndex < 0 || listIndex >= tocItems.size()) {
            Map<String, Object> result = new HashMap<>();
            result.put("list_index", listIndex);
            result.put("title", title);
            result.put("physical_index", incorrectItem.get("physical_index"));
            result.put("is_valid", false);
            return result;
        }
        
        // Find previous correct item
        Integer prevCorrect = null;
        for (int i = listIndex - 1; i >= 0; i--) {
            if (!incorrectIndices.contains(i) && i < tocItems.size()) {
                Integer physicalIndex = tocItems.get(i).getStartIndex();
                if (physicalIndex != null) {
                    prevCorrect = physicalIndex;
                    break;
                }
            }
        }
        if (prevCorrect == null) {
            prevCorrect = startIndex - 1;
        }
        
        // Find next correct item
        Integer nextCorrect = null;
        for (int i = listIndex + 1; i < tocItems.size(); i++) {
            if (!incorrectIndices.contains(i)) {
                Integer physicalIndex = tocItems.get(i).getStartIndex();
                if (physicalIndex != null) {
                    nextCorrect = physicalIndex;
                    break;
                }
            }
        }
        if (nextCorrect == null) {
            nextCorrect = endIndex;
        }
        
        // Build content between prev and next
        StringBuilder contentBuilder = new StringBuilder();
        for (int pageIndex = prevCorrect; pageIndex <= nextCorrect; pageIndex++) {
            int listIdx = pageIndex - startIndex;
            if (listIdx >= 0 && listIdx < pageList.size()) {
                String pageText = String.format("<physical_index_%d>\n%s\n</physical_index_%d>\n\n",
                    pageIndex, pageList.get(listIdx).getText(), pageIndex);
                contentBuilder.append(pageText);
            }
        }
        
        String contentRange = contentBuilder.toString();
        
        // Fix the item using LLM
        Integer physicalIndexInt = singleTocItemIndexFixer(title, contentRange, model);
        
        // Verify the fix
        Map<String, Object> checkItem = new HashMap<>();
        checkItem.put("title", title);
        checkItem.put("physical_index", physicalIndexInt);
        checkItem.put("list_index", listIndex);
        
        Map<String, Object> checkResult = checkTitleAppearance(checkItem, pageList, startIndex, model);
        
        Map<String, Object> result = new HashMap<>();
        result.put("list_index", listIndex);
        result.put("title", title);
        result.put("physical_index", physicalIndexInt);
        result.put("is_valid", "yes".equals(checkResult.get("answer")));
        
        return result;
    }

    /**
     * Fix a single TOC item's physical index using LLM.
     * Python equivalent: single_toc_item_index_fixer() lines 732-748
     */
    private Integer singleTocItemIndexFixer(String sectionTitle, String content, String model) {
        String prompt = promptBuilder.buildTocItemFixerPrompt(sectionTitle, content);
        String response = openAIClient.chat(model, prompt);
        
        JsonNode jsonResponse = jsonParser.extractJson(response);
        if (jsonResponse.has("physical_index")) {
            String physicalIndex = jsonResponse.get("physical_index").asText();
            return TreeUtils.parsePhysicalIndex(physicalIndex);
        }
        
        return null;
    }

    /**
     * Check if title appears on page (reuse TocVerifier logic).
     */
    private Map<String, Object> checkTitleAppearance(
            Map<String, Object> item,
            List<PdfPage> pageList,
            int startIndex,
            String model) {
        
        String title = (String) item.get("title");
        Integer listIndex = (Integer) item.get("list_index");
        Integer pageNumber = (Integer) item.get("physical_index");
        
        if (pageNumber == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("answer", "no");
            result.put("list_index", listIndex);
            return result;
        }
        
        int pageIndex = pageNumber - startIndex;
        if (pageIndex < 0 || pageIndex >= pageList.size()) {
            Map<String, Object> result = new HashMap<>();
            result.put("answer", "no");
            result.put("list_index", listIndex);
            return result;
        }
        
        String pageText = pageList.get(pageIndex).getText();
        String prompt = promptBuilder.buildTitleCheckPrompt(title, pageText);
        String response = openAIClient.chat(model, prompt);
        
        JsonNode jsonResponse = jsonParser.extractJson(response);
        String answer = jsonResponse.has("answer") ? jsonResponse.get("answer").asText() : "no";
        
        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("list_index", listIndex);
        
        return result;
    }
}
