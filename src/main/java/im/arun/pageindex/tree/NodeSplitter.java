package im.arun.pageindex.tree;

import im.arun.pageindex.config.PageIndexConfig;
import im.arun.pageindex.model.PdfPage;
import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.model.TreeNode;
import im.arun.pageindex.util.JsonLogger;
import im.arun.pageindex.verification.TocVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import im.arun.pageindex.util.ExecutorProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * Recursively splits large nodes into sub-trees when they exceed size limits.
 * Python equivalent: process_large_node_recursively() from page_index.py lines 992-1019
 */
public class NodeSplitter {
    private static final Logger logger = LoggerFactory.getLogger(NodeSplitter.class);
    
    private final TreeBuilder treeBuilder;
    private final TreePostProcessor treePostProcessor;
    private final TocVerifier tocVerifier;
    private final JsonLogger jsonLogger;

    public NodeSplitter(TreeBuilder treeBuilder, TocVerifier tocVerifier) {
        this.treeBuilder = treeBuilder;
        this.treePostProcessor = new TreePostProcessor();
        this.tocVerifier = tocVerifier;
        this.jsonLogger = new JsonLogger();
    }

    /**
     * Process large node recursively, splitting it into sub-nodes if needed.
     * Python equivalent: process_large_node_recursively() lines 992-1019
     */
    public TreeNode processLargeNodeRecursively(
            TreeNode node,
            List<PdfPage> pageList,
            PageIndexConfig config) {
        
        // Get pages for this node
        List<PdfPage> nodePageList = pageList.subList(
            node.getStartIndex() - 1, 
            Math.min(node.getEndIndex(), pageList.size())
        );
        
        // Calculate total tokens
        int tokenNum = nodePageList.stream()
            .mapToInt(PdfPage::getTokenCount)
            .sum();
        
        int pageSpan = node.getEndIndex() - node.getStartIndex();
        
        // Check if node is too large
        if (pageSpan > config.getMaxPageNumEachNode() && tokenNum >= config.getMaxTokenNumEachNode()) {
            logger.info("Large node detected: {}, start: {}, end: {}, tokens: {}", 
                node.getTitle(), node.getStartIndex(), node.getEndIndex(), tokenNum);
            
            // Generate sub-structure for this large node
            List<TocItem> nodeTocTree = treeBuilder.processNoToc(
                nodePageList,
                node.getStartIndex(),
                config.getModel()
            );
            
            // Check title appearance at start
            nodeTocTree = tocVerifier.checkTitleAppearanceInStartConcurrent(
                nodeTocTree,
                pageList,
                config.getModel()
            );
            
            // Filter out items with null physical_index
            List<TocItem> validNodeTocItems = nodeTocTree.stream()
                .filter(item -> item.getStartIndex() != null)
                .collect(Collectors.toList());
            
            if (!validNodeTocItems.isEmpty()) {
                // Check if first item matches current node title
                boolean firstItemMatchesNode = node.getTitle().trim()
                    .equals(validNodeTocItems.get(0).getTitle().trim());
                
                if (firstItemMatchesNode && validNodeTocItems.size() > 1) {
                    // Skip first item as it's the parent node itself
                    List<TocItem> childItems = validNodeTocItems.subList(1, validNodeTocItems.size());
                    List<TreeNode> childNodes = treePostProcessor.postProcessing(
                        childItems, node.getEndIndex());
                    node.setNodes(childNodes);
                    
                    // Update end index
                    if (validNodeTocItems.size() > 1) {
                        node.setEndIndex(validNodeTocItems.get(1).getStartIndex());
                    }
                } else {
                    // Use all items as children
                    List<TreeNode> childNodes = treePostProcessor.postProcessing(
                        validNodeTocItems, node.getEndIndex());
                    node.setNodes(childNodes);
                    
                    // Update end index
                    if (!validNodeTocItems.isEmpty()) {
                        node.setEndIndex(validNodeTocItems.get(0).getStartIndex());
                    }
                }
            }
        }
        
        // Recursively process children
        if (node.getNodes() != null && !node.getNodes().isEmpty()) {
            List<CompletableFuture<TreeNode>> futures = node.getNodes().stream()
                .map(childNode -> CompletableFuture.supplyAsync(() ->
                    processLargeNodeRecursively(childNode, pageList, config), ExecutorProvider.getExecutor()))
                .collect(Collectors.toList());
            
            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // Update children with processed results
            List<TreeNode> processedChildren = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
            
            node.setNodes(processedChildren);
        }
        
        return node;
    }

    /**
     * Process all nodes in a tree structure recursively.
     */
    public List<TreeNode> processTreeRecursively(
            List<TreeNode> tree,
            List<PdfPage> pageList,
            PageIndexConfig config) {
        
        jsonLogger.info("Processing tree recursively for large nodes");
        
        List<CompletableFuture<TreeNode>> futures = tree.stream()
            .map(node -> CompletableFuture.supplyAsync(() -> 
                processLargeNodeRecursively(node, pageList, config)))
            .collect(Collectors.toList());
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        List<TreeNode> processedTree = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        
        jsonLogger.info("Tree processing complete");
        return processedTree;
    }
}
