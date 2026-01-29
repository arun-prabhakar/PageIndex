package im.arun.pageindex.util;

import im.arun.pageindex.model.TocItem;
import im.arun.pageindex.model.TreeNode;

import java.util.*;

/**
 * Utility methods for tree operations and data conversions.
 * Python equivalents from utils.py lines 350-510
 */
public class TreeUtils {

    /**
     * Convert flat TOC list to hierarchical tree structure.
     * Python equivalent: list_to_tree() from utils.py lines 350-396
     */
    public static List<TreeNode> listToTree(List<TocItem> data) {
        if (data == null || data.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Create nodes and track parent-child relationships
        Map<String, TreeNode> nodes = new LinkedHashMap<>();
        List<TreeNode> rootNodes = new ArrayList<>();
        
        for (TocItem item : data) {
            String structure = item.getStructure();
            
            TreeNode node = new TreeNode();
            node.setTitle(item.getTitle());
            node.setStartIndex(item.getStartIndex());
            node.setEndIndex(item.getEndIndex());
            node.setNodes(new ArrayList<>());
            
            nodes.put(structure, node);
            
            // Find parent
            String parentStructure = getParentStructure(structure);
            
            if (parentStructure != null) {
                // Add as child to parent if parent exists
                if (nodes.containsKey(parentStructure)) {
                    nodes.get(parentStructure).getNodes().add(node);
                } else {
                    rootNodes.add(node);
                }
            } else {
                // No parent, this is a root node
                rootNodes.add(node);
            }
        }
        
        // Clean empty children arrays
        for (TreeNode node : rootNodes) {
            cleanNode(node);
        }
        
        return rootNodes;
    }

    /**
     * Get parent structure code from a structure string.
     * e.g., "1.2.3" -> "1.2", "1" -> null
     */
    private static String getParentStructure(String structure) {
        if (structure == null || structure.isEmpty()) {
            return null;
        }
        
        String[] parts = structure.split("\\.");
        if (parts.length > 1) {
            return String.join(".", Arrays.copyOf(parts, parts.length - 1));
        }
        
        return null;
    }

    /**
     * Clean empty children arrays from nodes.
     */
    private static void cleanNode(TreeNode node) {
        if (node.getNodes() == null || node.getNodes().isEmpty()) {
            node.setNodes(null);
        } else {
            for (TreeNode child : node.getNodes()) {
                cleanNode(child);
            }
        }
    }

    /**
     * Add preface node if first node doesn't start at page 1.
     * Python equivalent: add_preface_if_needed() from utils.py lines 398-409
     */
    public static List<TocItem> addPrefaceIfNeeded(List<TocItem> data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        
        TocItem firstItem = data.get(0);
        if (firstItem.getPhysicalIndex() != null) {
            Integer physicalIndex = parsePhysicalIndex(firstItem.getPhysicalIndex());
            if (physicalIndex != null && physicalIndex > 1) {
                TocItem preface = new TocItem();
                preface.setStructure("0");
                preface.setTitle("Preface");
                preface.setPhysicalIndex("physical_index_1");
                preface.setStartIndex(1);
                
                data.add(0, preface);
            }
        }
        
        return data;
    }

    /**
     * Parse physical index from string format.
     * Handles: "physical_index_5", "<physical_index_5>", "5"
     */
    public static Integer parsePhysicalIndex(String physicalIndex) {
        if (physicalIndex == null || physicalIndex.trim().isEmpty()) {
            return null;
        }
        
        String numStr = physicalIndex
            .replaceAll("[<>]", "")
            .replaceAll("physical_index_", "")
            .trim();
        
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Convert physical index to integer in TocItem list.
     * Python equivalent: convert_physical_index_to_int() from utils.py lines 545-565
     */
    public static List<TocItem> convertPhysicalIndexToInt(List<TocItem> data) {
        if (data == null) {
            return data;
        }
        
        for (TocItem item : data) {
            String physicalIndex = item.getPhysicalIndex();
            if (physicalIndex != null && physicalIndex instanceof String) {
                Integer intValue = parsePhysicalIndex(physicalIndex);
                if (intValue != null) {
                    item.setStartIndex(intValue);
                }
            }
        }
        
        return data;
    }

    /**
     * Validate and truncate physical indices to fit within document bounds.
     * Python equivalent from page_index.py lines 1114-1144
     */
    public static List<TocItem> validateAndTruncatePhysicalIndices(
            List<TocItem> tocItems, 
            int totalPages, 
            int startIndex) {
        
        List<TocItem> validItems = new ArrayList<>();
        
        for (TocItem item : tocItems) {
            Integer physicalIndex = item.getStartIndex();
            
            if (physicalIndex != null && physicalIndex >= startIndex && 
                physicalIndex <= totalPages + startIndex - 1) {
                validItems.add(item);
            }
        }
        
        return validItems;
    }

    /**
     * Remove page numbers from TOC structure.
     * Python equivalent: remove_page_number() from page_index.py lines 360-369
     */
    public static List<TocItem> removePageNumber(List<TocItem> data) {
        if (data == null) {
            return data;
        }
        
        List<TocItem> result = new ArrayList<>();
        for (TocItem item : data) {
            TocItem newItem = new TocItem();
            newItem.setStructure(item.getStructure());
            newItem.setTitle(item.getTitle());
            newItem.setPhysicalIndex(item.getPhysicalIndex());
            newItem.setStartIndex(item.getStartIndex());
            newItem.setEndIndex(item.getEndIndex());
            // Explicitly don't copy page
            result.add(newItem);
        }
        
        return result;
    }

    /**
     * Convert flat list to TreeNode structure.
     */
    public static List<TreeNode> tocItemsToTreeNodes(List<TocItem> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        
        return listToTree(items);
    }
}
