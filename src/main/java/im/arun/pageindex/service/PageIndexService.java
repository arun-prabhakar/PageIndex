package im.arun.pageindex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.arun.pageindex.config.PageIndexConfig;
import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.model.TreeNode;
import im.arun.pageindex.model.TreeStructure;
import im.arun.pageindex.pdf.PdfExtractor;
import im.arun.pageindex.summary.SummaryGenerator;
import im.arun.pageindex.toc.TocDetector;
import im.arun.pageindex.toc.TocExtractor;
import im.arun.pageindex.tree.NodeSplitter;
import im.arun.pageindex.tree.TreeBuilder;
import im.arun.pageindex.tree.TreePostProcessor;
import im.arun.pageindex.util.JsonLogger;
import im.arun.pageindex.util.TreeUtils;
import im.arun.pageindex.verification.TocFixer;
import im.arun.pageindex.verification.TocVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Main service orchestrator for PageIndex processing.
 * Python equivalent: tree_parser() and page_index_main() from page_index.py lines 1021-1100
 */
public class PageIndexService {
    private static final Logger logger = LoggerFactory.getLogger(PageIndexService.class);
    
    private final OpenAIClient openAIClient;
    private final PdfExtractor pdfExtractor;
    private final TocDetector tocDetector;
    private final TocExtractor tocExtractor;
    private final TreeBuilder treeBuilder;
    private final TreePostProcessor treePostProcessor;
    private final TocVerifier tocVerifier;
    private final TocFixer tocFixer;
    private final NodeSplitter nodeSplitter;
    private final SummaryGenerator summaryGenerator;
    private final JsonLogger jsonLogger;
    private final ObjectMapper objectMapper;

    private String currentDocumentPath = "document"; // Default for logging

    public PageIndexService(String apiKey) {
        this.openAIClient = new OpenAIClient(apiKey);
        this.pdfExtractor = new PdfExtractor();
        this.tocDetector = new TocDetector(openAIClient);
        this.tocExtractor = new TocExtractor(openAIClient);
        this.treeBuilder = new TreeBuilder(openAIClient);
        this.treePostProcessor = new TreePostProcessor();
        this.tocVerifier = new TocVerifier(openAIClient);
        this.tocFixer = new TocFixer(openAIClient);
        this.nodeSplitter = new NodeSplitter(treeBuilder, tocVerifier);
        this.summaryGenerator = new SummaryGenerator(openAIClient);
        this.jsonLogger = new JsonLogger(currentDocumentPath);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Main processing pipeline for PDF documents.
     */
    public TreeStructure processDocument(Path pdfPath, PageIndexConfig config) throws IOException {
        // Update document path for logging
        currentDocumentPath = pdfPath.toString();
        
        jsonLogger.info("Starting PageIndex processing", Map.of("pdf", pdfPath.toString()));
        
        // Extract PDF pages with text and tokens
        List<PdfPage> pageList = pdfExtractor.extractPages(pdfPath, config.getModel());
        jsonLogger.info("Extracted pages", Map.of("count", pageList.size()));
        
        // Check for TOC
        Map<String, Object> tocCheckResult = checkToc(pageList, config);
        jsonLogger.info("TOC check result", tocCheckResult);
        
        // Build TOC structure
        List<TocItem> tocWithPageNumber;
        
        String tocContent = (String) tocCheckResult.get("toc_content");
        List<Integer> tocPageList = (List<Integer>) tocCheckResult.get("toc_page_list");
        String pageIndexGiven = (String) tocCheckResult.get("page_index_given_in_toc");
        
        if (tocContent != null && !tocContent.trim().isEmpty() && "yes".equals(pageIndexGiven)) {
            tocWithPageNumber = metaProcessor(
                pageList,
                "process_toc_with_page_numbers",
                tocContent,
                tocPageList,
                1,
                config
            );
        } else {
            tocWithPageNumber = metaProcessor(
                pageList,
                "process_no_toc",
                null,
                null,
                1,
                config
            );
        }
        
        // Add preface if needed
        tocWithPageNumber = TreeUtils.addPrefaceIfNeeded(tocWithPageNumber);
        
        // Check title appearance at start
        tocWithPageNumber = tocVerifier.checkTitleAppearanceInStartConcurrent(
            tocWithPageNumber, pageList, config.getModel());
        
        // Filter valid items
        tocWithPageNumber = tocWithPageNumber.stream()
            .filter(item -> item.getStartIndex() != null)
            .toList();
        
        // Post-process to tree structure
        List<TreeNode> tree = treePostProcessor.postProcessing(
            tocWithPageNumber, pageList.size());
        
        // Process large nodes recursively
        tree = nodeSplitter.processTreeRecursively(tree, pageList, config);
        
        // Add node text if requested
        if (config.isAddNodeText()) {
            summaryGenerator.addNodeText(tree, pageList);
        }
        
        // Generate summaries if requested
        if (config.isAddNodeSummary()) {
            summaryGenerator.generateSummariesForStructure(tree, config.getModel());
        }
        
        // Add node IDs if requested
        if (config.isAddNodeId()) {
            addNodeIds(tree, 0);
        }
        
        // Create final structure
        TreeStructure result = new TreeStructure();
        result.setDocName(pdfPath.getFileName().toString());
        result.setStructure(tree);
        
        // Generate doc description if requested
        if (config.isAddDocDescription() && !tree.isEmpty()) {
            String description = summaryGenerator.generateDocDescription(tree.get(0), config.getModel());
            result.setDocDescription(description);
        }
        
        jsonLogger.info("PageIndex processing complete");
        return result;
    }

    /**
     * Check for table of contents in document.
     * Python equivalent: check_toc() lines 688-724
     */
    private Map<String, Object> checkToc(List<PdfPage> pageList, PageIndexConfig config) {
        List<Integer> tocPageList = tocDetector.findTocPages(pageList, config, jsonLogger);
        
        if (tocPageList.isEmpty()) {
            return Map.of(
                "toc_content", "",
                "toc_page_list", List.of(),
                "page_index_given_in_toc", "no"
            );
        }
        
        Map<String, Object> tocJson = tocExtractor.extractToc(pageList, tocPageList, config.getModel());
        
        return Map.of(
            "toc_content", tocJson.get("toc_content"),
            "toc_page_list", tocPageList,
            "page_index_given_in_toc", tocJson.get("page_index_given_in_toc")
        );
    }

    /**
     * Meta processor - orchestrates tree building with fallback modes.
     * Python equivalent: meta_processor() lines 951-989
     */
    private List<TocItem> metaProcessor(
            List<PdfPage> pageList,
            String mode,
            String tocContent,
            List<Integer> tocPageList,
            int startIndex,
            PageIndexConfig config) {
        
        logger.info("Meta processor mode: {}, start_index: {}", mode, startIndex);
        
        // Build TOC based on mode
        List<TocItem> tocWithPageNumber;
        
        switch (mode) {
            case "process_toc_with_page_numbers":
                tocWithPageNumber = treeBuilder.processTocWithPageNumbers(
                    tocContent, tocPageList, pageList, config.getTocCheckPageNum(), config.getModel());
                break;
            case "process_toc_no_page_numbers":
                tocWithPageNumber = treeBuilder.processTocNoPageNumbers(
                    tocContent, tocPageList, pageList, startIndex, config.getModel());
                break;
            default:
                tocWithPageNumber = treeBuilder.processNoToc(pageList, startIndex, config.getModel());
                break;
        }
        
        // Filter items with valid physical indices
        tocWithPageNumber = tocWithPageNumber.stream()
            .filter(item -> item.getStartIndex() != null)
            .toList();
        
        // Validate and truncate physical indices
        tocWithPageNumber = TreeUtils.validateAndTruncatePhysicalIndices(
            tocWithPageNumber, pageList.size(), startIndex);
        
        // Verify TOC
        TocVerifier.VerificationResult verificationResult = tocVerifier.verifyToc(
            pageList, tocWithPageNumber, startIndex, config.getModel(), null);
        
        double accuracy = verificationResult.accuracy;
        List<Map<String, Object>> incorrectResults = verificationResult.incorrectResults;
        
        jsonLogger.info("Verification result", Map.of(
            "mode", mode,
            "accuracy", accuracy,
            "incorrect_count", incorrectResults.size()
        ));
        
        // Perfect accuracy
        if (accuracy == 1.0 && incorrectResults.isEmpty()) {
            return tocWithPageNumber;
        }
        
        // Good accuracy - try to fix
        if (accuracy > 0.6 && !incorrectResults.isEmpty()) {
            TocFixer.FixResult fixResult = tocFixer.fixIncorrectTocWithRetries(
                tocWithPageNumber, pageList, incorrectResults, startIndex, 3, config.getModel());
            return fixResult.tocItems;
        }
        
        // Poor accuracy - fallback to next mode
        if ("process_toc_with_page_numbers".equals(mode)) {
            return metaProcessor(pageList, "process_toc_no_page_numbers", 
                tocContent, tocPageList, startIndex, config);
        } else if ("process_toc_no_page_numbers".equals(mode)) {
            return metaProcessor(pageList, "process_no_toc", 
                null, null, startIndex, config);
        } else {
            throw new RuntimeException("Processing failed - all modes exhausted");
        }
    }

    /**
     * Add sequential node IDs to tree structure.
     */
    private int addNodeIds(List<TreeNode> tree, int startId) {
        int currentId = startId;
        for (TreeNode node : tree) {
            node.setNodeId(String.format("%04d", currentId));
            currentId++;
            if (node.getNodes() != null && !node.getNodes().isEmpty()) {
                currentId = addNodeIds(node.getNodes(), currentId);
            }
        }
        return currentId;
    }
}
