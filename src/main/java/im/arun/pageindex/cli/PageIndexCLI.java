package im.arun.pageindex.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import im.arun.pageindex.config.PageIndexConfig;
import im.arun.pageindex.model.TreeStructure;
import im.arun.pageindex.service.PageIndexService;
import im.arun.pageindex.util.ExecutorProvider;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Command-line interface for PageIndex using Picocli.
 * Python equivalent: run_pageindex.py
 */
@Command(
    name = "pageindex",
    description = "Generate hierarchical tree index from PDF documents using LLM-based analysis",
    mixinStandardHelpOptions = true,
    version = "PageIndex 1.0"
)
public class PageIndexCLI implements Callable<Integer> {

    @Option(names = {"--pdf-path"}, description = "Path to PDF file", required = true)
    private String pdfPath;

    @Option(names = {"--model"}, description = "OpenAI model to use", defaultValue = "gpt-4o-2024-11-20")
    private String model;

    @Option(names = {"--toc-check-pages"}, description = "Pages to check for table of contents", defaultValue = "20")
    private int tocCheckPages;

    @Option(names = {"--max-pages-per-node"}, description = "Max pages per node", defaultValue = "10")
    private int maxPagesPerNode;

    @Option(names = {"--max-tokens-per-node"}, description = "Max tokens per node", defaultValue = "20000")
    private int maxTokensPerNode;

    @Option(names = {"--if-add-node-id"}, description = "Add node ID (yes/no)", defaultValue = "yes")
    private String ifAddNodeId;

    @Option(names = {"--if-add-node-summary"}, description = "Add node summary (yes/no)", defaultValue = "yes")
    private String ifAddNodeSummary;

    @Option(names = {"--if-add-doc-description"}, description = "Add doc description (yes/no)", defaultValue = "yes")
    private String ifAddDocDescription;

    @Option(names = {"--if-add-node-text"}, description = "Add node text (yes/no)", defaultValue = "no")
    private String ifAddNodeText;

    @Option(names = {"--output"}, description = "Output JSON file path")
    private String outputPath;

    @Option(names = {"--api-key"}, description = "OpenAI API key (or set CHATGPT_API_KEY env var)")
    private String apiKey;

    @Override
    public Integer call() throws Exception {
        // Get API key from argument or environment
        String effectiveApiKey = apiKey != null ? apiKey : System.getenv("CHATGPT_API_KEY");
        
        if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
            System.err.println("Error: OpenAI API key must be provided via --api-key or CHATGPT_API_KEY environment variable");
            return 1;
        }

        // Validate PDF path
        Path pdfFilePath = Paths.get(pdfPath);
        if (!Files.exists(pdfFilePath)) {
            System.err.println("Error: PDF file not found: " + pdfPath);
            return 1;
        }

        if (!pdfPath.toLowerCase().endsWith(".pdf")) {
            System.err.println("Error: File must be a PDF: " + pdfPath);
            return 1;
        }

        System.out.println("PageIndex - Hierarchical Tree Index Generator");
        System.out.println("=".repeat(50));
        System.out.println("PDF: " + pdfPath);
        System.out.println("Model: " + model);
        System.out.println();

        // Create config
        PageIndexConfig config = new PageIndexConfig();
        config.setModel(model);
        config.setTocCheckPageNum(tocCheckPages);
        config.setMaxPageNumEachNode(maxPagesPerNode);
        config.setMaxTokenNumEachNode(maxTokensPerNode);
        config.setAddNodeId("yes".equalsIgnoreCase(ifAddNodeId));
        config.setAddNodeSummary("yes".equalsIgnoreCase(ifAddNodeSummary));
        config.setAddDocDescription("yes".equalsIgnoreCase(ifAddDocDescription));
        config.setAddNodeText("yes".equalsIgnoreCase(ifAddNodeText));

        // Process document
        System.out.println("Starting processing...");
        PageIndexService service = new PageIndexService(effectiveApiKey);
        
        TreeStructure result;
        try {
            result = service.processDocument(pdfFilePath, config);
        } catch (Exception e) {
            System.err.println("Error processing document: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }

        // Output result
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String jsonOutput = mapper.writeValueAsString(result);

        if (outputPath != null) {
            // Write to file
            Path outputFilePath = Paths.get(outputPath);
            Files.writeString(outputFilePath, jsonOutput);
            System.out.println("\nOutput written to: " + outputPath);
        } else {
            // Print to console
            System.out.println("\nResult:");
            System.out.println(jsonOutput);
        }

        System.out.println("\nProcessing complete!");
        return 0;
    }

    public static void main(String[] args) {
        try {
            int exitCode = new CommandLine(new PageIndexCLI()).execute(args);
            System.exit(exitCode);
        } finally {
            ExecutorProvider.shutdown();
        }
    }
}
