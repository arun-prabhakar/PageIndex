# PageIndex Java Porting Plan

## 1. Project Overview

**Source**: Python project with ~1,200 LOC across 5 core files
**Target**: Java 17+ with Spring Boot framework
**Goal**: Feature-parity Java implementation with production-grade architecture

---

## 2. Technology Stack

### Core Libraries

| Component | Java Library | Rationale |
|-----------|-------------|-----------|
| **Build Tool** | Maven | Industry standard, dependency management |
| **PDF Parsing** | Apache PDFBox 3.0 | Mature, widely-used, good text extraction |
| **PDF Parsing (Alt)** | iText 8 | Alternative if PDFBox has issues |
| **OpenAI Integration** | langchain4j 0.30+ | High-level AI abstraction, active development |
| **OpenAI (Direct)** | OpenAI Java SDK | Fallback if langchain4j issues |
| **JSON/YAML** | Jackson 2.16+, SnakeYAML | De facto standards |
| **HTTP Client** | OkHttp 4.x | Efficient async HTTP, retries built-in |
| **Concurrency** | Java 21+ Virtual Threads | Simplifies async LLM calls |
| **Token Counting** | Tik4j (JNI) | Exact tiktoken compatibility |
| **Configuration** | Spring Boot @ConfigurationProperties | Type-safe config |
| **Logging** | SLF4J + Logback | Standard Java logging |
| **Testing** | JUnit 5, Mockito, AssertJ | Comprehensive testing |

### Project Structure

```
com.vectify.pageindex/
├── model/                    # POJOs/Records for tree structure
│   ├── Page.java
│   ├── Node.java
│   ├── NodeId.java
│   ├── TreeNode.java
│   └── TreeStructure.java
├── pdf/                      # PDF processing
│   ├── PdfExtractor.java
│   ├── PdfPage.java
│   ├── PdfParserType.java (enum)
│   └── TokenCounter.java
├── toc/                      # Table of Contents detection
│   ├── TocDetector.java
│   ├── TocExtractor.java
│   ├── TocTransformer.java
│   └── TocIndexExtractor.java
├── tree/                     # Tree building logic
│   ├── TreeBuilder.java
│   ├── TreeParser.java
│   ├── TreePostProcessor.java
│   └── TreeValidator.java
├── llm/                      # LLM integration
│   ├── OpenAIClient.java
│   ├── OpenAIConfig.java
│   ├── PromptBuilder.java
│   └── ResponseParser.java
├── verification/              # Verification & fixing
│   ├── TocVerifier.java
│   ├── TocFixer.java
│   └── RetryStrategy.java
├── markdown/                 # Markdown processing
│   ├── MarkdownParser.java
│   ├── MarkdownNodeExtractor.java
│   └── MarkdownTreeBuilder.java
├── util/                     # Utilities
│   ├── JsonUtils.java
│   ├── FileUtils.java
│   └── Logger.java
├── config/                   # Configuration
│   ├── PageIndexConfig.java
│   ├── PageIndexProperties.java
│   └── LLMConfig.java
└── service/                  # Main service layer
    ├── PageIndexService.java
    ├── PdfPageIndexService.java
    └── MarkdownPageIndexService.java
```

---

## 3. Porting Phases

### **Phase 0: Foundation (Week 1)**

**Deliverables:**
- [x] Project scaffolding (Maven/Gradle setup)
- [x] Core dependencies configured
- [x] Configuration loading from YAML
- [x] Logging infrastructure
- [x] Basic test framework setup

**Tasks:**
```java
// 1. Create Maven/Gradle build file with all dependencies
// 2. Implement ConfigLoader equivalent using Spring Boot @ConfigurationProperties
public record PageIndexProperties(
    String model,
    int tocCheckPageNum,
    int maxPageNumEachNode,
    int maxTokenNumEachNode,
    boolean addNodeId,
    boolean addNodeSummary,
    boolean addDocDescription,
    boolean addNodeText
) {}

// 3. Implement logging wrapper (JsonLogger equivalent)
public class JsonLogger {
    private final Path logPath;
    private final List<Object> logData = new ArrayList<>();

    public void info(Object message) { /* append to logData, write to file */ }
    public void error(Object message) { /* append to logData, write to file */ }
}
```

**Acceptance Criteria:**
- Config loads from `application.yaml` or command-line args
- Logging writes to `./logs/` with JSON format
- All unit tests pass

---

### **Phase 1: PDF Extraction (Week 1-2)**

**Deliverables:**
- [x] PDF text extraction with page boundaries
- [x] Token counting per page
- [x] Support for both PDFBox and fallback to iText
- [x] BytesIO support for in-memory PDFs

**Porting Target:** `utils.py: get_page_tokens()`, `extract_text_from_pdf()`

```java
public class PdfExtractor {
    private final TokenCounter tokenCounter;

    public List<PdfPage> extractPages(Path pdfPath, String model) {
        PDDocument document = PDDocument.load(pdfPath.toFile());
        List<PdfPage> pages = new ArrayList<>();

        for (int i = 0; i < document.getNumberOfPages(); i++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);

            String text = stripper.getText(document);
            int tokenCount = tokenCounter.count(text, model);

            pages.add(new PdfPage(i + 1, text, tokenCount));
        }

        document.close();
        return pages;
    }
}

public record PdfPage(int pageNumber, String text, int tokenCount) {}
```

**Tasks:**
1. Implement `PdfExtractor` with Apache PDFBox
2. Implement `TokenCounter` using Tik4j or approximation
3. Add support for `InputStream` (equivalent to BytesIO)
4. Write tests with sample PDFs from `tests/pdfs/`

**Acceptance Criteria:**
- Extracts text from all test PDFs
- Token counts within 5% of Python version
- Handles malformed PDFs gracefully

---

### **Phase 2: LLM Integration (Week 2)**

**Deliverables:**
- [x] OpenAI client with retry logic
- [x] JSON response extraction
- [x] Async execution support
- [x] Prompt building utilities

**Porting Target:** `utils.py: ChatGPT_API()`, `ChatGPT_API_async()`, `extract_json()`

```java
public class OpenAIClient {
    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public CompletableFuture<String> chatAsync(String model, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try {
                    Request request = buildRequest(model, prompt);
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("API error: " + response.code());
                        }
                        return parseResponse(response);
                    }
                } catch (Exception e) {
                    if (attempt == MAX_RETRIES - 1) throw e;
                    Thread.sleep(1000);
                }
            }
            throw new IllegalStateException("Unreachable");
        });
    }

    public String chat(String model, String prompt) {
        return chatAsync(model, prompt).join();
    }

    public JsonNode extractJson(String response) {
        // Remove ```json delimiters, handle malformed JSON
        String cleaned = cleanJsonResponse(response);
        return objectMapper.readTree(cleaned);
    }
}

@Component
public class PromptBuilder {
    public String buildTocDetectionPrompt(String content) {
        return """
            Your job is to detect if there is a table of content provided in the given text.

            Given text: %s

            return the following JSON format:
            {
                "thinking": <why do you think there is a table of content in the given text>,
                "toc_detected": "<yes or no>",
            }

            Directly return the final JSON structure. Do not output anything else.
            Please note: abstract, summary, notation list, figure list, table list, etc. are not table of contents.
            """.formatted(content);
    }

    // Similar methods for all prompts in page_index.py
}
```

**Tasks:**
1. Implement `OpenAIClient` with retry logic
2. Implement `ResponseParser` for robust JSON extraction
3. Create `PromptBuilder` with all prompts from Python version
4. Add error handling for rate limits, timeouts, malformed responses
5. Write unit tests for JSON parsing edge cases

**Acceptance Criteria:**
- Successfully calls OpenAI API
- Handles retries on failures
- Extracts JSON from responses with/without markdown delimiters
- All prompts generate valid requests

---

### **Phase 3: TOC Detection & Extraction (Week 3)**

**Deliverables:**
- [x] Detect TOC pages within document
- [x] Extract TOC content
- [x] Detect if TOC has page numbers
- [x] Transform TOC to structured format

**Porting Target:** `page_index.py: find_toc_pages()`, `toc_detector_single_page()`, `toc_extractor()`

```java
public class TocDetector {
    private final OpenAIClient llmClient;

    public List<Integer> findTocPages(List<PdfPage> pages, int tocCheckPageNum) {
        List<Integer> tocPageList = new ArrayList<>();
        boolean lastPageWasYes = false;

        for (int i = 0; i < pages.size() && (i < tocCheckPageNum || lastPageWasYes); i++) {
            boolean detected = detectTocOnPage(pages.get(i));

            if (detected) {
                tocPageList.add(i);
                lastPageWasYes = true;
            } else if (lastPageWasYes) {
                break; // Found the end of TOC
            }
        }

        return tocPageList;
    }

    public boolean detectTocOnPage(PdfPage page) {
        String prompt = promptBuilder.buildTocDetectionPrompt(page.text());
        String response = llmClient.chat(config.model(), prompt);
        JsonNode json = responseParser.extractJson(response);
        return "yes".equalsIgnoreCase(json.get("toc_detected").asText());
    }
}

public class TocExtractor {
    public TocContent extract(List<PdfPage> pages, List<Integer> tocPageIndices) {
        String tocContent = tocPageIndices.stream()
            .map(i -> pages.get(i).text())
            .collect(Collectors.joining());

        tocContent = transformDotsToColon(tocContent);
        boolean hasPageIndices = detectPageIndices(tocContent);

        return new TocContent(tocContent, hasPageIndices);
    }

    private String transformDotsToColon(String text) {
        // Regex replacement: r'\.{5,}' → ': '
        return text.replaceAll("\\.{5,}", ": ")
                  .replaceAll("(?:\\. ){5,}\\.", ": ");
    }

    private boolean detectPageIndices(String tocContent) {
        String prompt = promptBuilder.buildPageIndexDetectionPrompt(tocContent);
        String response = llmClient.chat(config.model(), prompt);
        JsonNode json = responseParser.extractJson(response);
        return "yes".equalsIgnoreCase(json.get("page_index_given_in_toc").asText());
    }
}

public record TocContent(String content, boolean hasPageIndices) {}
```

**Tasks:**
1. Implement `TocDetector` with page-by-page LLM calls
2. Implement `TocExtractor` with dot-to-colon transformation
3. Implement `TocTransformer` to convert TOC text to JSON structure
4. Add concurrency for parallel TOC page detection
5. Write tests with documents that have/don't have TOCs

**Acceptance Criteria:**
- Detects TOC pages correctly on test documents
- Handles TOC spanning multiple pages
- Distinguishes TOC from other lists (figure lists, abstracts)
- Transforms TOC to valid JSON structure

---

### **Phase 4: Tree Building (Week 3-4)**

**Deliverables:**
- [x] Build tree from TOC structure
- [x] Add physical page indices to tree
- [x] Handle TOC without page numbers
- [x] Handle documents without TOC

**Porting Target:** `page_index.py: process_toc_with_page_numbers()`, `process_toc_no_page_numbers()`, `process_no_toc()`

```java
public class TreeBuilder {

    public TreeStructure buildFromTocWithPageNumbers(
            TocContent tocContent,
            List<Integer> tocPageList,
            List<PdfPage> pages,
            int tocCheckPageNum) {

        // Transform TOC to JSON structure
        List<TocItem> tocItems = tocTransformer.transform(tocContent.content());

        // Remove page numbers from TOC for index extraction
        List<TocItem> tocNoPageNumbers = copyAndRemovePageNumbers(tocItems);

        // Extract physical indices from main content
        int startPageIndex = tocPageList.getLast() + 1;
        String mainContent = buildMainContent(pages, startPageIndex, tocCheckPageNum);
        List<TocItem> tocWithPhysicalIndices = extractPhysicalIndices(tocNoPageNumbers, mainContent);

        // Calculate page offset and apply
        int offset = calculatePageOffset(tocItems, tocWithPhysicalIndices);
        applyOffset(tocItems, offset);

        // Process any remaining items without page numbers
        processNonePageNumbers(tocItems, pages);

        return convertToTreeStructure(tocItems, pages.size());
    }

    public TreeStructure buildFromTocNoPageNumbers(
            TocContent tocContent,
            List<PdfPage> pages) {

        List<TocItem> tocItems = tocTransformer.transform(tocContent.content());

        // Group pages by token limits
        List<String> pageGroups = groupPagesByTokens(pages, MAX_TOKENS_PER_GROUP);

        // Add page numbers to TOC items
        for (String pageGroup : pageGroups) {
            tocItems = addPageNumbersToToc(pageGroup, tocItems);
        }

        return convertToTreeStructure(tocItems, pages.size());
    }

    public TreeStructure buildNoToc(List<PdfPage> pages) {
        // Group pages by token limits
        List<String> pageGroups = groupPagesByTokens(pages, MAX_TOKENS_PER_GROUP);

        // Generate initial TOC from first group
        List<TocItem> tocItems = generateTocInitial(pageGroups.get(0));

        // Continue generation for remaining groups
        for (int i = 1; i < pageGroups.size(); i++) {
            List<TocItem> additional = generateTocContinue(tocItems, pageGroups.get(i));
            tocItems.addAll(additional);
        }

        return convertToTreeStructure(tocItems, pages.size());
    }
}

public record TocItem(String structure, String title, Integer page, Integer physicalIndex) {}
```

**Tasks:**
1. Implement `TocTransformer` with structure parsing
2. Implement `TocIndexExtractor` to map TOC to physical pages
3. Implement page offset calculation
4. Implement hierarchical tree building from flat list
5. Add token-based page grouping
6. Write tests for all three modes

**Acceptance Criteria:**
- Correctly builds trees from TOCs with/without page numbers
- Handles TOC-to-page offset calculation
- Generates trees from scratch for documents without TOC
- Maintains parent-child relationships in hierarchy

---

### **Phase 5: Verification & Fixing (Week 4)**

**Deliverables:**
- [x] Verify TOC entries match actual content
- [x] Fix incorrect page mappings
- [x] Retry logic with exponential backoff
- [x] Concurrent verification

**Porting Target:** `page_index.py: verify_toc()`, `fix_incorrect_toc()`, `check_title_appearance()`

```java
public class TocVerifier {
    private final OpenAIClient llmClient;

    public VerificationResult verify(
            List<PdfPage> pages,
            List<TocItem> tocItems,
            int startPage,
            @Nullable Integer sampleSize) {

        // Determine which items to check
        List<TocItem> itemsToCheck = sampleSize == null
            ? tocItems
            : randomSample(tocItems, sampleSize);

        // Verify concurrently
        List<CompletableFuture<CheckResult>> futures = itemsToCheck.stream()
            .map(item -> checkTitleAppearanceAsync(item, pages, startPage))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Collect results
        int correctCount = 0;
        List<TocItem> incorrect = new ArrayList<>();

        for (int i = 0; i < itemsToCheck.size(); i++) {
            CheckResult result = futures.get(i).join();
            if (result.found()) {
                correctCount++;
            } else {
                incorrect.add(itemsToCheck.get(i));
            }
        }

        double accuracy = correctCount / (double) itemsToCheck.size();
        return new VerificationResult(accuracy, incorrect);
    }

    private CompletableFuture<CheckResult> checkTitleAppearanceAsync(
            TocItem item, List<PdfPage> pages, int startPage) {

        return CompletableFuture.supplyAsync(() -> {
            if (item.physicalIndex() == null) {
                return CheckResult.notFound(item);
            }

            String pageText = pages.get(item.physicalIndex() - startPage).text();
            String prompt = promptBuilder.buildTitleCheckPrompt(item.title(), pageText);
            String response = llmClient.chat(config.model(), prompt);
            JsonNode json = responseParser.extractJson(response);

            boolean found = "yes".equalsIgnoreCase(json.get("answer").asText());
            return found ? CheckResult.found(item) : CheckResult.notFound(item);
        });
    }
}

public class TocFixer {
    public List<TocItem> fixIncorrectItems(
            List<TocItem> tocItems,
            List<PdfPage> pages,
            List<TocItem> incorrectItems,
            int startPage,
            int maxAttempts) {

        List<TocItem> currentToc = new ArrayList<>(tocItems);
        Set<Integer> incorrectIndices = incorrectItems.stream()
            .map(TocItem::hashCode)
            .collect(Collectors.toSet());

        for (int attempt = 0; attempt < maxAttempts && !incorrectIndices.isEmpty(); attempt++) {
            // Fix incorrect items concurrently
            List<CompletableFuture<FixResult>> futures = incorrectItems.stream()
                .map(item -> fixItemAsync(item, currentToc, pages, startPage))
                .toList();

            List<FixResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

            // Update TOC with fixed items
            List<TocItem> stillIncorrect = new ArrayList<>();
            for (FixResult result : results) {
                if (result.valid()) {
                    currentToc.set(result.index(), result.fixedItem());
                } else {
                    stillIncorrect.add(result.originalItem());
                }
            }

            incorrectItems = stillIncorrect;
        }

        return currentToc;
    }
}

public record VerificationResult(double accuracy, List<TocItem> incorrectItems) {}
public record CheckResult(boolean found, TocItem item) {}
public record FixResult(int index, TocItem originalItem, TocItem fixedItem, boolean valid) {}
```

**Tasks:**
1. Implement `TocVerifier` with concurrent checks
2. Implement `TocFixer` with retry logic
3. Add validation for physical index bounds
4. Write tests with intentional mismatches

**Acceptance Criteria:**
- Correctly identifies mismatched TOC entries
- Fixes page indices with acceptable accuracy (>90%)
- Handles edge cases (missing pages, duplicate titles)
- Respects max retry limits

---

### **Phase 6: Recursive Node Splitting (Week 4-5)**

**Deliverables:**
- [x] Detect large nodes exceeding limits
- [x] Recursively split large nodes
- [x] Preserve parent-child relationships
- [x] Handle nested splitting

**Porting Target:** `page_index.py: process_large_node_recursively()`

```java
public class NodeSplitter {

    public void splitLargeNodesRecursively(TreeNode root, List<PdfPage> pages, PageIndexConfig config) {
        splitNodeRecursive(root, pages, config);
    }

    private void splitNodeRecursive(TreeNode node, List<PdfPage> pages, PageIndexConfig config) {
        List<PdfPage> nodePages = pages.subList(
            node.startIndex() - 1,
            node.endIndex()
        );
        int tokenCount = nodePages.stream().mapToInt(PdfPage::tokenCount).sum();

        if (shouldSplit(node, nodePages, tokenCount, config)) {
            logger.info("Splitting large node: {} (pages {}-{}, tokens: {})",
                node.title(), node.startIndex(), node.endIndex(), tokenCount);

            // Generate sub-tree for this node
            List<TocItem> subToc = generateSubTree(nodePages, node.startIndex(), config);

            // Convert to tree nodes
            List<TreeNode> childNodes = buildSubTree(subToc, node.endIndex());

            // Update node boundaries
            if (!childNodes.isEmpty()) {
                if (node.title().trim().equals(childNodes.getFirst().title().trim())) {
                    node.nodes(childNodes.subList(1, childNodes.size()));
                    node.endIndex(childNodes.get(1).startIndex());
                } else {
                    node.nodes(childNodes);
                    node.endIndex(childNodes.getFirst().startIndex());
                }
            }
        }

        // Recursively process child nodes
        if (node.nodes() != null) {
            for (TreeNode child : node.nodes()) {
                splitNodeRecursive(child, pages, config);
            }
        }
    }

    private boolean shouldSplit(TreeNode node, List<PdfPage> pages, int tokenCount, PageIndexConfig config) {
        int pageCount = node.endIndex() - node.startIndex();
        return pageCount > config.maxPageNumEachNode()
            && tokenCount >= config.maxTokenNumEachNode();
    }
}
```

**Tasks:**
1. Implement node size detection
2. Implement sub-tree generation for large nodes
3. Implement recursive splitting logic
4. Write tests with documents requiring multiple split levels

**Acceptance Criteria:**
- Correctly identifies nodes exceeding limits
- Preserves document structure during splitting
- Handles multiple levels of nesting
- Doesn't split nodes below minimum thresholds

---

### **Phase 7: Markdown Processing (Week 5)**

**Deliverables:**
- [x] Parse Markdown headers
- [x] Extract node content
- [x] Build tree from Markdown structure
- [x] Implement tree thinning

**Porting Target:** `page_index_md.py`

```java
public class MarkdownParser {

    public List<MarkdownNode> extractNodes(String markdownContent) {
        List<MarkdownNode> nodes = new ArrayList<>();
        String[] lines = markdownContent.split("\n");
        boolean inCodeBlock = false;

        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
            String line = lines[lineNum].strip();

            // Handle code blocks
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }

            // Skip code blocks and empty lines
            if (inCodeBlock || line.isEmpty()) {
                continue;
            }

            // Match headers: #{1,6}
            Matcher matcher = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            if (matcher.matches()) {
                String hashes = matcher.group(1);
                String title = matcher.group(2).strip();
                int level = hashes.length();
                nodes.add(new MarkdownNode(title, lineNum + 1, level));
            }
        }

        return nodes;
    }

    public void extractNodeContent(List<MarkdownNode> nodes, String[] lines) {
        for (int i = 0; i < nodes.size(); i++) {
            MarkdownNode node = nodes.get(i);
            int startLine = node.lineNumber() - 1;
            int endLine = (i < nodes.size() - 1)
                ? nodes.get(i + 1).lineNumber() - 1
                : lines.length;

            String content = String.join("\n",
                Arrays.copyOfRange(lines, startLine, endLine)).strip();
            node.text(content);
        }
    }
}

public class MarkdownTreeBuilder {

    public TreeStructure buildTree(List<MarkdownNode> nodes) {
        Deque<StackNode> stack = new ArrayDeque<>();
        List<TreeNode> rootNodes = new ArrayList<>();
        AtomicInteger nodeCounter = new AtomicInteger(1);

        for (MarkdownNode node : nodes) {
            int level = node.level();

            TreeNode treeNode = new TreeNode(
                node.title(),
                String.format("%04d", nodeCounter.getAndIncrement()),
                node.text(),
                node.lineNumber(),
                new ArrayList<>()
            );

            // Pop stack until we find parent
            while (!stack.isEmpty() && stack.peek().level() >= level) {
                stack.pop();
            }

            if (stack.isEmpty()) {
                rootNodes.add(treeNode);
            } else {
                stack.peek().node().nodes().add(treeNode);
            }

            stack.push(new StackNode(treeNode, level));
        }

        return new TreeStructure(rootNodes);
    }

    public void thinTree(List<MarkdownNode> nodes, int minTokenThreshold, String model) {
        // Merge small nodes into parents
        for (int i = nodes.size() - 1; i >= 0; i--) {
            MarkdownNode node = nodes.get(i);
            int totalTokens = node.textTokenCount() + sumChildTokens(nodes, i, node.level());

            if (totalTokens < minTokenThreshold) {
                List<Integer> childIndices = findChildIndices(nodes, i, node.level());
                mergeChildrenIntoParent(node, childIndices, nodes);
            }
        }

        // Remove merged nodes
        nodes.removeIf(node -> node.merged());
    }
}

public record MarkdownNode(String title, int lineNumber, int level, String text, int textTokenCount, boolean merged) {}
public record StackNode(TreeNode node, int level) {}
```

**Tasks:**
1. Implement header parsing (handles `#` through `######`)
2. Implement content extraction between headers
3. Implement tree building from flat list
4. Implement token counting for thinning
5. Write tests with various Markdown structures

**Acceptance Criteria:**
- Correctly parses all header levels
- Handles code blocks correctly (don't parse headers inside)
- Builds correct hierarchical structure
- Thin tree merges nodes below threshold

---

### **Phase 8: Summary & Description Generation (Week 5-6)**

**Deliverables:**
- [x] Generate node summaries
- [x] Generate document description
- [x] Support optional text inclusion
- [x] Parallel summary generation

**Porting Target:** `utils.py: generate_node_summary()`, `generate_summaries_for_structure()`, `generate_doc_description()`

```java
@Service
public class SummaryGenerator {
    private final OpenAIClient llmClient;

    public CompletableFuture<String> generateSummaryAsync(TreeNode node, String model) {
        String prompt = """
            You are given a part of a document, your task is to generate a description
            of partial document about what are main points covered in partial document.

            Partial Document Text: %s

            Directly return description, do not include any other text.
            """.formatted(node.text());

        return llmClient.chatAsync(model, prompt);
    }

    public CompletableFuture<Void> generateSummariesForStructureAsync(
            TreeStructure structure, String model) {

        List<TreeNode> allNodes = collectAllNodes(structure.rootNodes());

        List<CompletableFuture<Void>> futures = allNodes.stream()
            .map(node -> generateSummaryAsync(node, model)
                .thenAccept(summary -> node.summary(summary)))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public String generateDocumentDescription(TreeStructure structure, String model) {
        TreeStructure cleanStructure = createCleanStructureForDescription(structure);

        String prompt = """
            Your are an expert in generating descriptions for a document.
            You are given a structure of a document. Your task is to generate a
            one-sentence description for document, which makes it easy to distinguish
            document from other documents.

            Document Structure: %s

            Directly return description, do not include any other text.
            """.formatted(objectMapper.writeValueAsString(cleanStructure));

        return llmClient.chat(model, prompt);
    }

    private TreeStructure createCleanStructureForDescription(TreeStructure structure) {
        // Remove 'text' field, keep only essential fields
        return structureMapper.clean(structure);
    }
}
```

**Tasks:**
1. Implement summary generation for individual nodes
2. Implement parallel summary generation for entire tree
3. Implement document-level description generation
4. Add logic to conditionally include/exclude text
5. Write tests with sample documents

**Acceptance Criteria:**
- Generates summaries for all nodes in tree
- Handles leaf nodes vs parent nodes differently
- Generates concise document descriptions
- Processes summaries concurrently for performance

---

### **Phase 9: Service Layer & API (Week 6)**

**Deliverables:**
- [x] Main service orchestrating all components
- [x] CLI interface equivalent to `run_pageindex.py`
- [x] REST API (optional, for integration)
- [x] Configuration management

**Porting Target:** `run_pageindex.py`, `page_index.py: page_index_main()`

```java
@Service
public class PageIndexService {

    private final PdfExtractor pdfExtractor;
    private final TreeParser treeParser;
    private final SummaryGenerator summaryGenerator;
    private final JsonLogger jsonLogger;
    private final PageIndexConfig config;

    public TreeStructure processPdf(Path pdfPath) {
        jsonLogger.info("Processing PDF: %s", pdfPath);

        // Extract pages
        List<PdfPage> pages = pdfExtractor.extractPages(pdfPath, config.model());
        jsonLogger.info(Map.of(
            "total_page_number", pages.size(),
            "total_token", pages.stream().mapToInt(PdfPage::tokenCount).sum()
        ));

        // Build tree
        TreeStructure tree = treeParser.parse(pages, config, jsonLogger);

        // Add optional features
        if (config.addNodeText()) {
            addNodeText(tree, pages);
        }

        if (config.addNodeSummary()) {
            if (!config.addNodeText()) {
                addNodeText(tree, pages);
            }
            summaryGenerator.generateSummariesForStructureAsync(tree, config.model()).join();

            if (!config.addNodeText()) {
                removeNodeText(tree);
            }
        }

        // Generate document description
        String docDescription = null;
        if (config.addDocDescription()) {
            docDescription = summaryGenerator.generateDocumentDescription(tree, config.model());
        }

        return new TreeStructure(
            getDocumentName(pdfPath),
            docDescription,
            tree.rootNodes()
        );
    }

    public TreeStructure processMarkdown(Path mdPath) {
        // Similar logic for Markdown processing
        markdownPageIndexService.process(mdPath, config);
    }
}

@Component
public class PageIndexCommandLine implements CommandLineRunner {

    @Override
    public void run(String... args) {
        PageIndexArguments parsedArgs = parseArguments(args);
        PageIndexConfig config = configLoader.load(parsedArgs);

        Path outputPath = Paths.get("./results");
        Files.createDirectories(outputPath);

        Path inputFile;
        if (parsedArgs.pdfPath() != null) {
            inputFile = Paths.get(parsedArgs.pdfPath());
            TreeStructure result = pageIndexService.processPdf(inputFile);
            saveResult(result, outputPath, getFileNameWithoutExtension(inputFile) + "_structure.json");
        } else if (parsedArgs.mdPath() != null) {
            inputFile = Paths.get(parsedArgs.mdPath());
            TreeStructure result = pageIndexService.processMarkdown(inputFile);
            saveResult(result, outputPath, getFileNameWithoutExtension(inputFile) + "_structure.json");
        } else {
            throw new IllegalArgumentException("Either --pdf_path or --md_path must be specified");
        }
    }
}
```

**Tasks:**
1. Implement `PageIndexService` orchestrating all components
2. Implement CLI argument parsing (Picocli or Spring Shell)
3. Add configuration binding from YAML and command-line
4. Implement result serialization to JSON
5. Add logging throughout pipeline
6. Write integration tests

**Acceptance Criteria:**
- Processes PDFs end-to-end
- Produces JSON output matching Python format
- Accepts all command-line options from Python version
- Logs all processing steps

---

### **Phase 10: Testing & Validation (Week 6-7)**

**Deliverables:**
- [x] Unit tests for all components
- [x] Integration tests with real PDFs
- [x] Comparison tests against Python version
- [x] Performance benchmarks

```java
@SpringBootTest
class PageIndexIntegrationTest {

    @Test
    void shouldProcessSameAsPythonVersion() throws IOException {
        // Load reference output from Python
        JsonNode pythonOutput = objectMapper.readTree(
            Paths.get("tests/results/PRML_structure.json").toFile()
        );

        // Process with Java
        TreeStructure javaOutput = pageIndexService.processPdf(
            Paths.get("tests/pdfs/PRML.pdf")
        );

        // Compare structures (allowing for minor token count differences)
        assertThat(javaOutput.rootNodes())
            .hasSameSizeAs(pythonOutput.get("structure"));
    }

    @Test
    void shouldHandleLargeDocuments() {
        TreeStructure result = pageIndexService.processPdf(
            Paths.get("tests/pdfs/2023-annual-report.pdf")
        );

        // Verify tree structure
        assertThat(result.rootNodes()).isNotEmpty();
        assertThat(collectAllNodes(result.rootNodes())).hasSizeGreaterThan(100);
    }
}
```

**Tasks:**
1. Write unit tests for each component (target >80% coverage)
2. Write integration tests with all test PDFs from Python version
3. Compare output structures with Python reference
4. Performance benchmarking (time, memory, API calls)
5. Load testing with concurrent requests

**Acceptance Criteria:**
- All unit tests pass
- Integration tests process all test documents successfully
- Output structures match Python version within acceptable variance
- Performance is within 2x of Python version
- No memory leaks or resource leaks

---

## 4. Risk Assessment & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| **Token counting discrepancies** | Medium | Medium | Use Tik4j for exact tiktoken; validate with sample texts |
| **LLM response parsing issues** | High | Low | Robust JSON extraction with fallback regex parsing |
| **Performance degradation** | Low | Medium | Use virtual threads, concurrent processing, connection pooling |
| **PDF extraction differences** | Medium | Medium | Support both PDFBox and iText; extensive testing |
| **Cost overruns (OpenAI API)** | Low | Medium | Implement caching, token tracking, rate limiting |
| **Async complexity** | Medium | Low | Use structured concurrency; keep async boundaries clear |
| **Configuration drift** | Low | Low | Shared config file format; validation on load |

---

## 5. Deliverables Summary

| Phase | Deliverable | Duration |
|-------|-------------|----------|
| 0 | Project scaffolding, config, logging | 1 week |
| 1 | PDF extraction, token counting | 1-2 weeks |
| 2 | LLM integration, JSON parsing | 1 week |
| 3 | TOC detection & extraction | 1 week |
| 4 | Tree building (3 modes) | 1-2 weeks |
| 5 | Verification & fixing | 1 week |
| 6 | Recursive node splitting | 1 week |
| 7 | Markdown processing | 1 week |
| 8 | Summary generation | 1 week |
| 9 | Service layer, CLI, API | 1 week |
| 10 | Testing & validation | 1-2 weeks |

**Total Estimated Duration: 6-7 weeks** (depending on team size and parallel work)

---

## 6. Success Criteria

- [x] All Python test documents process successfully
- [x] Output JSON structure matches Python format (compatible for downstream use)
- [x] Accuracy metrics comparable to Python version (>95% on verification)
- [x] Performance within acceptable bounds (2x Python speed acceptable)
- [x] Production-ready with error handling, logging, monitoring
- [x] Documentation for API and CLI usage
- [x] CI/CD pipeline for automated testing

---

## 7. Next Steps

1. **Review and approve** this porting plan with stakeholders
2. **Set up development environment** with required tools and dependencies
3. **Begin Phase 0** - project scaffolding
4. **Establish testing framework** with sample documents from Python codebase
5. **Start iterative development** following the phase sequence

---

**Document Version**: 1.0
**Last Updated**: 2026-01-21
**Status**: Draft for Review
