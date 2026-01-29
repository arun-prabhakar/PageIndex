package im.arun.pageindex.summary;

import im.arun.pageindex.llm.OpenAIClient;
import im.arun.pageindex.llm.PromptBuilder;
import im.arun.pageindex.model.TreeNode;
import im.arun.pageindex.util.JsonLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Generates summaries for tree nodes and document descriptions.
 * Python equivalent: generate_node_summary() and generate_summaries_for_structure() 
 * from utils.py lines 605-659
 */
public class SummaryGenerator {
    private static final Logger logger = LoggerFactory.getLogger(SummaryGenerator.class);
    
    private final OpenAIClient openAIClient;
    private final PromptBuilder promptBuilder;
    private final JsonLogger jsonLogger;

    public SummaryGenerator(OpenAIClient openAIClient) {
        this.openAIClient = openAIClient;
        this.promptBuilder = new PromptBuilder();
        this.jsonLogger = new JsonLogger();
    }

    /**
     * Generate summary for a single node.
     * Python equivalent: generate_node_summary() lines 605-613
     */
    public String generateNodeSummary(TreeNode node, String model) {
        if (node.getText() == null || node.getText().isEmpty()) {
            logger.warn("Node has no text, skipping summary generation: {}", node.getTitle());
            return "";
        }
        
        String prompt = promptBuilder.buildNodeSummaryPrompt(node.getText());
        
        try {
            return openAIClient.chat(model, prompt);
        } catch (Exception e) {
            logger.error("Failed to generate summary for node: {}", node.getTitle(), e);
            return "";
        }
    }

    /**
     * Generate summaries for all nodes in structure concurrently.
     * Python equivalent: generate_summaries_for_structure() lines 616-623
     */
    public TreeNode generateSummariesForStructure(TreeNode structure, String model) {
        jsonLogger.info("Generating summaries for structure");
        
        // Get all nodes (flatten tree)
        List<TreeNode> nodes = structureToList(structure);
        
        jsonLogger.info("Found nodes to summarize", Map.of("count", nodes.size()));
        
        // Generate summaries concurrently
        List<CompletableFuture<String>> futures = nodes.stream()
            .map(node -> CompletableFuture.supplyAsync(() -> 
                generateNodeSummary(node, model)))
            .collect(Collectors.toList());
        
        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results and update nodes
        List<String> summaries = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).setSummary(summaries.get(i));
        }
        
        jsonLogger.info("Summary generation complete");
        return structure;
    }

    /**
     * Generate summaries for multiple root nodes.
     */
    public List<TreeNode> generateSummariesForStructure(List<TreeNode> structure, String model) {
        for (TreeNode node : structure) {
            generateSummariesForStructure(node, model);
        }
        return structure;
    }

    /**
     * Generate document description from structure.
     * Python equivalent: generate_doc_description() from utils.py
     */
    public String generateDocDescription(TreeNode structure, String model) {
        jsonLogger.info("Generating document description");
        
        // Convert structure to clean format (without text/summary for brevity)
        String cleanStructure = formatStructureForDescription(structure);
        
        String prompt = promptBuilder.buildDocDescriptionPrompt(cleanStructure);
        
        try {
            String description = openAIClient.chat(model, prompt);
            jsonLogger.info("Document description generated");
            return description;
        } catch (Exception e) {
            logger.error("Failed to generate document description", e);
            return "";
        }
    }

    /**
     * Flatten tree structure to list of all nodes.
     * Python equivalent: structure_to_list() from utils.py lines 185-196
     */
    private List<TreeNode> structureToList(TreeNode structure) {
        List<TreeNode> nodes = new ArrayList<>();
        nodes.add(structure);
        
        if (structure.getNodes() != null) {
            for (TreeNode child : structure.getNodes()) {
                nodes.addAll(structureToList(child));
            }
        }
        
        return nodes;
    }

    /**
     * Format structure for description prompt (exclude text/summary).
     */
    private String formatStructureForDescription(TreeNode node) {
        StringBuilder sb = new StringBuilder();
        formatNode(node, sb, 0);
        return sb.toString();
    }

    private void formatNode(TreeNode node, StringBuilder sb, int indent) {
        String indentStr = "  ".repeat(indent);
        sb.append(indentStr).append("- ").append(node.getTitle());
        
        if (node.getStartIndex() != null && node.getEndIndex() != null) {
            sb.append(" (pages ").append(node.getStartIndex())
              .append("-").append(node.getEndIndex()).append(")");
        }
        
        sb.append("\n");
        
        if (node.getNodes() != null) {
            for (TreeNode child : node.getNodes()) {
                formatNode(child, sb, indent + 1);
            }
        }
    }

    /**
     * Add node text from PDF pages to all nodes in structure.
     */
    public void addNodeText(TreeNode node, List<im.arun.pageindex.model.PdfPage> pdfPages) {
        if (node.getStartIndex() != null && node.getEndIndex() != null) {
            StringBuilder text = new StringBuilder();
            for (int i = node.getStartIndex() - 1; i < node.getEndIndex() && i < pdfPages.size(); i++) {
                if (i >= 0) {
                    text.append(pdfPages.get(i).getText());
                }
            }
            node.setText(text.toString());
        }
        
        if (node.getNodes() != null) {
            for (TreeNode child : node.getNodes()) {
                addNodeText(child, pdfPages);
            }
        }
    }

    /**
     * Add node text to all nodes in tree.
     */
    public void addNodeText(List<TreeNode> tree, List<im.arun.pageindex.model.PdfPage> pdfPages) {
        for (TreeNode node : tree) {
            addNodeText(node, pdfPages);
        }
    }
}
