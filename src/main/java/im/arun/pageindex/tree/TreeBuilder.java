package im.arun.pageindex.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.arun.pageindex.llm.JsonResponseParser;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.pdf.TokenCounter;
import im.arun.pageindex.toc.TocIndexExtractor;
import im.arun.pageindex.toc.TocTransformer;
import im.arun.pageindex.util.JsonLogger;
import im.arun.pageindex.util.TreeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds hierarchical tree structure from TOC and document pages.
 * Implements 3 modes: with page numbers, without page numbers, no TOC.
 * 
 * Python equivalents:
 * - process_toc_with_page_numbers() from page_index.py lines 614-643
 * - process_toc_no_page_numbers() from page_index.py lines 589-610
 * - process_no_toc() from page_index.py lines 568-587
 */
public class TreeBuilder {
    private static final Logger logger = LoggerFactory.getLogger(TreeBuilder.class);
    
    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final JsonResponseParser jsonParser;
    private final TokenCounter tokenCounter;
    private final TocTransformer tocTransformer;
    private final TocIndexExtractor tocIndexExtractor;
    private final JsonLogger jsonLogger;
    private final ObjectMapper objectMapper;

    public TreeBuilder(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
        this.jsonParser = new JsonResponseParser();
        this.tokenCounter = new TokenCounter();
        this.tocTransformer = new TocTransformer(openAIClient);
        this.tocIndexExtractor = new TocIndexExtractor(openAIClient);
        this.jsonLogger = new JsonLogger();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Process TOC when page numbers are present.
     * Python equivalent: process_toc_with_page_numbers() lines 614-643
     */
    public List<TocItem> processTocWithPageNumbers(
            String tocContent,
            List<Integer> tocPageList,
            List<PdfPage> pageList,
            int tocCheckPageNum,
            String model) {
        
        jsonLogger.info("Processing TOC with page numbers");
        
        // Transform TOC to structured format
        List<TocItem> tocWithPageNumber = tocTransformer.transformToc(tocContent, model);
        jsonLogger.info("Transformed TOC", Map.of("items", tocWithPageNumber.size()));
        
        // Remove page numbers for physical index extraction
        List<TocItem> tocNoPageNumber = TreeUtils.removePageNumber(tocWithPageNumber);
        
        // Extract physical indices from main content
        int startPageIndex = tocPageList.get(tocPageList.size() - 1) + 1;
        StringBuilder mainContent = new StringBuilder();
        
        int endPage = Math.min(startPageIndex + tocCheckPageNum, pageList.size());
        for (int pageIndex = startPageIndex; pageIndex < endPage; pageIndex++) {
            mainContent.append(String.format("<physical_index_%d>\n", pageIndex + 1));
            mainContent.append(pageList.get(pageIndex).getText());
            mainContent.append(String.format("\n</physical_index_%d>\n\n", pageIndex + 1));
        }
        
        List<TocItem> tocWithPhysicalIndex = tocIndexExtractor.extractPhysicalIndices(
            tocNoPageNumber, pageList, model);
        jsonLogger.info("Extracted physical indices", Map.of("items", tocWithPhysicalIndex.size()));
        
        // Convert physical indices to integers
        tocWithPhysicalIndex = TreeUtils.convertPhysicalIndexToInt(tocWithPhysicalIndex);
        
        // Extract matching pairs to calculate page offset
        List<Map<String, Object>> matchingPairs = extractMatchingPagePairs(
            tocWithPageNumber, tocWithPhysicalIndex, startPageIndex);
        jsonLogger.info("Found matching pairs", Map.of("count", matchingPairs.size()));
        
        // Calculate page offset
        Integer offset = calculatePageOffset(matchingPairs);
        jsonLogger.info("Calculated offset", Map.of("offset", offset != null ? offset : "null"));
        
        // Add offset to TOC
        if (offset != null) {
            tocWithPageNumber = addPageOffsetToTocJson(tocWithPageNumber, offset);
        }
        
        // Process items with null page numbers
        tocWithPageNumber = processNonePageNumbers(tocWithPageNumber, pageList, model);
        
        return tocWithPageNumber;
    }

    /**
     * Process TOC when page numbers are NOT present.
     * Python equivalent: process_toc_no_page_numbers() lines 589-610
     */
    public List<TocItem> processTocNoPageNumbers(
            String tocContent,
            List<Integer> tocPageList,
            List<PdfPage> pageList,
            int startIndex,
            String model) {
        
        jsonLogger.info("Processing TOC without page numbers");
        
        // Transform TOC to structured format
        List<TocItem> tocContent_transformed = tocTransformer.transformToc(tocContent, model);
        jsonLogger.info("Transformed TOC", Map.of("items", tocContent_transformed.size()));
        
        // Build page contents with tags
        List<String> pageContents = new ArrayList<>();
        List<Integer> tokenLengths = new ArrayList<>();
        
        for (int pageIndex = startIndex; pageIndex < startIndex + pageList.size(); pageIndex++) {
            PdfPage page = pageList.get(pageIndex - startIndex);
            String pageText = String.format("<physical_index_%d>\n%s\n</physical_index_%d>\n\n",
                pageIndex, page.getText(), pageIndex);
            pageContents.add(pageText);
            tokenLengths.add(tokenCounter.countTokens(pageText, model));
        }
        
        // Group pages by token limits
        List<String> groupTexts = pageListToGroupText(pageContents, tokenLengths, 20000, 1);
        jsonLogger.info("Grouped pages", Map.of("groups", groupTexts.size()));
        
        // Add page numbers to TOC using grouped texts
        List<TocItem> tocWithPageNumber = new ArrayList<>(tocContent_transformed);
        for (String groupText : groupTexts) {
            tocWithPageNumber = addPageNumberToToc(groupText, tocWithPageNumber, model);
        }
        jsonLogger.info("Added page numbers to TOC");
        
        // Convert physical indices to integers
        tocWithPageNumber = TreeUtils.convertPhysicalIndexToInt(tocWithPageNumber);
        
        return tocWithPageNumber;
    }

    /**
     * Process document without TOC - generate structure from content.
     * Python equivalent: process_no_toc() lines 568-587
     */
    public List<TocItem> processNoToc(
            List<PdfPage> pageList,
            int startIndex,
            String model) {
        
        jsonLogger.info("Processing without TOC - generating structure");
        
        // Build page contents with tags
        List<String> pageContents = new ArrayList<>();
        List<Integer> tokenLengths = new ArrayList<>();
        
        for (int pageIndex = startIndex; pageIndex < startIndex + pageList.size(); pageIndex++) {
            PdfPage page = pageList.get(pageIndex - startIndex);
            String pageText = String.format("<physical_index_%d>\n%s\n</physical_index_%d>\n\n",
                pageIndex, page.getText(), pageIndex);
            pageContents.add(pageText);
            tokenLengths.add(tokenCounter.countTokens(pageText, model));
        }
        
        // Group pages by token limits
        List<String> groupTexts = pageListToGroupText(pageContents, tokenLengths, 20000, 1);
        jsonLogger.info("Grouped pages", Map.of("groups", groupTexts.size()));
        
        // Generate TOC from first group
        List<TocItem> tocWithPageNumber = generateTocInit(groupTexts.get(0), model);
        
        // Continue generation for remaining groups
        for (int i = 1; i < groupTexts.size(); i++) {
            List<TocItem> additional = generateTocContinue(tocWithPageNumber, groupTexts.get(i), model);
            tocWithPageNumber.addAll(additional);
        }
        jsonLogger.info("Generated TOC from content", Map.of("items", tocWithPageNumber.size()));
        
        // Convert physical indices to integers
        tocWithPageNumber = TreeUtils.convertPhysicalIndexToInt(tocWithPageNumber);
        
        return tocWithPageNumber;
    }

    /**
     * Group page contents by token limits with overlap.
     * Python equivalent: page_list_to_group_text() from page_index.py lines 418-451
     */
    private List<String> pageListToGroupText(
            List<String> pageContents,
            List<Integer> tokenLengths,
            int maxTokens,
            int overlapPage) {
        
        int numTokens = tokenLengths.stream().mapToInt(Integer::intValue).sum();
        
        if (numTokens <= maxTokens) {
            return List.of(String.join("", pageContents));
        }
        
        List<String> subsets = new ArrayList<>();
        List<String> currentSubset = new ArrayList<>();
        int currentTokenCount = 0;
        
        int expectedPartsNum = (int) Math.ceil((double) numTokens / maxTokens);
        int averageTokensPerPart = (int) Math.ceil((numTokens / (double) expectedPartsNum + maxTokens) / 2.0);
        
        for (int i = 0; i < pageContents.size(); i++) {
            String pageContent = pageContents.get(i);
            int pageTokens = tokenLengths.get(i);
            
            if (currentTokenCount + pageTokens > averageTokensPerPart) {
                subsets.add(String.join("", currentSubset));
                
                // Start new subset from overlap
                int overlapStart = Math.max(i - overlapPage, 0);
                currentSubset = new ArrayList<>(pageContents.subList(overlapStart, i));
                currentTokenCount = 0;
                for (int j = overlapStart; j < i; j++) {
                    currentTokenCount += tokenLengths.get(j);
                }
            }
            
            currentSubset.add(pageContent);
            currentTokenCount += pageTokens;
        }
        
        if (!currentSubset.isEmpty()) {
            subsets.add(String.join("", currentSubset));
        }
        
        logger.info("Divided page list into {} groups", subsets.size());
        return subsets;
    }

    /**
     * Generate initial TOC structure from document part.
     * Python equivalent: generate_toc_init() lines 534-566
     */
    private List<TocItem> generateTocInit(String part, String model) {
        jsonLogger.info("Generating initial TOC structure");
        
        String prompt = promptBuilder.buildGenerateTocInitPrompt(part);
        OpenAIClient.ChatResponse response = openAIClient.chatWithFinishReason(model, prompt, null);
        
        if ("finished".equals(response.finishReason)) {
            JsonNode jsonResponse = jsonParser.extractJson(response.content);
            return parseTocItems(jsonResponse);
        } else {
            throw new RuntimeException("Failed to generate TOC: finish reason " + response.finishReason);
        }
    }

    /**
     * Continue TOC generation for additional document parts.
     * Python equivalent: generate_toc_continue() lines 499-531
     */
    private List<TocItem> generateTocContinue(List<TocItem> existingToc, String part, String model) {
        jsonLogger.info("Continuing TOC generation");
        
        String prompt = promptBuilder.buildGenerateTocContinuePrompt(
            tocItemsToJson(existingToc), part);
        OpenAIClient.ChatResponse response = openAIClient.chatWithFinishReason(model, prompt, null);
        
        if ("finished".equals(response.finishReason)) {
            JsonNode jsonResponse = jsonParser.extractJson(response.content);
            return parseTocItems(jsonResponse);
        } else {
            throw new RuntimeException("Failed to continue TOC: finish reason " + response.finishReason);
        }
    }

    /**
     * Add page numbers to TOC items using LLM.
     * Python equivalent: add_page_number_to_toc() lines 453-483
     */
    private List<TocItem> addPageNumberToToc(String part, List<TocItem> structure, String model) {
        String prompt = promptBuilder.buildAddPageNumberPrompt(part, tocItemsToJson(structure));
        String response = openAIClient.chat(model, prompt);
        
        JsonNode jsonResult = jsonParser.extractJson(response);
        List<TocItem> result = parseTocItems(jsonResult);
        
        // Remove 'start' field if present
        for (TocItem item : result) {
            // The 'start' field was used temporarily, we don't need it in the model
        }
        
        return result;
    }

    /**
     * Extract matching page pairs between TOC page numbers and physical indices.
     * Python equivalent: extract_matching_page_pairs() lines 371-383
     */
    private List<Map<String, Object>> extractMatchingPagePairs(
            List<TocItem> tocPage,
            List<TocItem> tocPhysicalIndex,
            int startPageIndex) {
        
        List<Map<String, Object>> pairs = new ArrayList<>();
        
        for (TocItem phyItem : tocPhysicalIndex) {
            for (TocItem pageItem : tocPage) {
                if (phyItem.getTitle() != null && phyItem.getTitle().equals(pageItem.getTitle())) {
                    if (phyItem.getStartIndex() != null && phyItem.getStartIndex() >= startPageIndex) {
                        Map<String, Object> pair = new HashMap<>();
                        pair.put("title", phyItem.getTitle());
                        pair.put("page", pageItem.getPage());
                        pair.put("physical_index", phyItem.getStartIndex());
                        pairs.add(pair);
                    }
                }
            }
        }
        
        return pairs;
    }

    /**
     * Calculate page offset from matching pairs.
     * Python equivalent: calculate_page_offset() lines 386-406
     */
    private Integer calculatePageOffset(List<Map<String, Object>> pairs) {
        List<Integer> differences = new ArrayList<>();
        
        for (Map<String, Object> pair : pairs) {
            try {
                Integer physicalIndex = (Integer) pair.get("physical_index");
                Integer pageNumber = (Integer) pair.get("page");
                
                if (physicalIndex != null && pageNumber != null) {
                    differences.add(physicalIndex - pageNumber);
                }
            } catch (Exception e) {
                logger.debug("Failed to calculate difference for pair: {}", pair);
            }
        }
        
        if (differences.isEmpty()) {
            return null;
        }
        
        // Find most common difference
        Map<Integer, Integer> differenceCounts = new HashMap<>();
        for (Integer diff : differences) {
            differenceCounts.put(diff, differenceCounts.getOrDefault(diff, 0) + 1);
        }
        
        return differenceCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Add page offset to TOC JSON.
     * Python equivalent: add_page_offset_to_toc_json() lines 408-414
     */
    private List<TocItem> addPageOffsetToTocJson(List<TocItem> data, int offset) {
        for (TocItem item : data) {
            if (item.getPage() != null) {
                item.setStartIndex(item.getPage() + offset);
                item.setPhysicalIndex(String.format("physical_index_%d", item.getPage() + offset));
            }
        }
        return data;
    }

    /**
     * Process TOC items with null page numbers.
     * Python equivalent: process_none_page_numbers() lines 648-683
     */
    private List<TocItem> processNonePageNumbers(List<TocItem> tocItems, List<PdfPage> pageList, String model) {
        for (int i = 0; i < tocItems.size(); i++) {
            TocItem item = tocItems.get(i);
            
            if (item.getStartIndex() == null) {
                // Find previous and next physical indices
                Integer prevPhysicalIndex = 0;
                for (int j = i - 1; j >= 0; j--) {
                    if (tocItems.get(j).getStartIndex() != null) {
                        prevPhysicalIndex = tocItems.get(j).getStartIndex();
                        break;
                    }
                }
                
                Integer nextPhysicalIndex = pageList.size();
                for (int j = i + 1; j < tocItems.size(); j++) {
                    if (tocItems.get(j).getStartIndex() != null) {
                        nextPhysicalIndex = tocItems.get(j).getStartIndex();
                        break;
                    }
                }
                
                // Build content between prev and next
                StringBuilder content = new StringBuilder();
                for (int pageIndex = prevPhysicalIndex; pageIndex <= nextPhysicalIndex && pageIndex < pageList.size(); pageIndex++) {
                    if (pageIndex >= 0 && pageIndex < pageList.size()) {
                        content.append(String.format("<physical_index_%d>\n%s\n</physical_index_%d>\n\n",
                            pageIndex, pageList.get(pageIndex).getText(), pageIndex));
                    }
                }
                
                // Use LLM to find physical index
                String prompt = promptBuilder.buildTocItemFixerPrompt(item.getTitle(), content.toString());
                String response = openAIClient.chat(model, prompt);
                JsonNode jsonResponse = jsonParser.extractJson(response);
                
                if (jsonResponse.has("physical_index")) {
                    String physicalIndex = jsonResponse.get("physical_index").asText();
                    Integer physicalIndexInt = TreeUtils.parsePhysicalIndex(physicalIndex);
                    if (physicalIndexInt != null) {
                        item.setStartIndex(physicalIndexInt);
                        item.setPhysicalIndex(String.format("physical_index_%d", physicalIndexInt));
                    }
                }
            }
        }
        
        return tocItems;
    }

    /**
     * Convert TocItem list to JSON string.
     */
    private String tocItemsToJson(List<TocItem> items) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        } catch (Exception e) {
            logger.error("Failed to serialize TOC items", e);
            return "[]";
        }
    }

    /**
     * Parse JSON node to TocItem list.
     */
    private List<TocItem> parseTocItems(JsonNode node) {
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
            
            if (itemNode.has("physical_index") && !itemNode.get("physical_index").isNull()) {
                item.setPhysicalIndex(itemNode.get("physical_index").asText());
            }
            
            items.add(item);
        }
        
        return items;
    }
}
