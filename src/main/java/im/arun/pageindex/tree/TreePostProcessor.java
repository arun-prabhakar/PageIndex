package im.arun.pageindex.tree;

import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.model.TreeNode;
import im.arun.pageindex.util.JsonLogger;
import im.arun.pageindex.util.TreeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Post-processing for TOC items - converts flat list to hierarchical tree.
 * Python equivalent: post_processing() from utils.py lines 460-479
 */
public class TreePostProcessor {
    private static final Logger logger = LoggerFactory.getLogger(TreePostProcessor.class);
    private final JsonLogger jsonLogger;

    public TreePostProcessor() {
        this.jsonLogger = new JsonLogger();
    }

    /**
     * Post-process flat TOC items into hierarchical tree structure.
     * 
     * @param structure Flat list of TOC items
     * @param endPhysicalIndex Last page index of the document
     * @return Hierarchical tree structure
     */
    public List<TreeNode> postProcessing(List<TocItem> structure, int endPhysicalIndex) {
        if (structure == null || structure.isEmpty()) {
            return new ArrayList<>();
        }
        
        jsonLogger.info("Starting post-processing", Map.of(
            "toc_items", structure.size(),
            "end_index", endPhysicalIndex
        ));
        
        // First convert physical_index to start_index and calculate end_index
        for (int i = 0; i < structure.size(); i++) {
            TocItem item = structure.get(i);
            
            // Set start_index from physical_index
            if (item.getPhysicalIndex() != null) {
                Integer startIdx = TreeUtils.parsePhysicalIndex(item.getPhysicalIndex());
                if (startIdx != null) {
                    item.setStartIndex(startIdx);
                }
            }
            
            // Calculate end_index
            if (i < structure.size() - 1) {
                TocItem nextItem = structure.get(i + 1);
                Integer nextStartIdx = null;
                
                if (nextItem.getPhysicalIndex() != null) {
                    nextStartIdx = TreeUtils.parsePhysicalIndex(nextItem.getPhysicalIndex());
                }
                
                if (nextStartIdx != null) {
                    // Check if next section starts at the beginning of the page
                    if ("yes".equalsIgnoreCase(nextItem.getAppearStart())) {
                        item.setEndIndex(nextStartIdx - 1);
                    } else {
                        item.setEndIndex(nextStartIdx);
                    }
                }
            } else {
                // Last item ends at document end
                item.setEndIndex(endPhysicalIndex);
            }
        }
        
        // Convert flat list to tree structure
        List<TreeNode> tree = TreeUtils.listToTree(structure);
        
        if (!tree.isEmpty()) {
            jsonLogger.info("Post-processing complete", Map.of(
                "tree_nodes", tree.size()
            ));
            return tree;
        } else {
            // If tree conversion failed, return flat structure as TreeNodes
            jsonLogger.warn("Tree conversion failed, returning flat structure");
            List<TreeNode> flatTree = new ArrayList<>();
            
            for (TocItem item : structure) {
                TreeNode node = new TreeNode();
                node.setTitle(item.getTitle());
                node.setStartIndex(item.getStartIndex());
                node.setEndIndex(item.getEndIndex());
                flatTree.add(node);
            }
            
            return flatTree;
        }
    }

    /**
     * Post-process with custom end index calculation.
     * Useful when processing sub-trees where end might be mid-document.
     */
    public List<TreeNode> postProcessingCustom(List<TocItem> structure, 
                                               int endPhysicalIndex,
                                               boolean checkAppearStart) {
        if (structure == null || structure.isEmpty()) {
            return new ArrayList<>();
        }
        
        // First convert physical_index to start_index and calculate end_index
        for (int i = 0; i < structure.size(); i++) {
            TocItem item = structure.get(i);
            
            // Set start_index from physical_index
            if (item.getPhysicalIndex() != null) {
                Integer startIdx = TreeUtils.parsePhysicalIndex(item.getPhysicalIndex());
                if (startIdx != null) {
                    item.setStartIndex(startIdx);
                }
            }
            
            // Calculate end_index
            if (i < structure.size() - 1) {
                TocItem nextItem = structure.get(i + 1);
                Integer nextStartIdx = nextItem.getStartIndex();
                
                if (nextStartIdx != null) {
                    if (checkAppearStart && "yes".equalsIgnoreCase(nextItem.getAppearStart())) {
                        item.setEndIndex(nextStartIdx - 1);
                    } else {
                        item.setEndIndex(nextStartIdx);
                    }
                } else {
                    item.setEndIndex(endPhysicalIndex);
                }
            } else {
                item.setEndIndex(endPhysicalIndex);
            }
        }
        
        return TreeUtils.listToTree(structure);
    }
}
